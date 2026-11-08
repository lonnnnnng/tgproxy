package web

import (
	"errors"
	"context"
	"embed"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"sync"
	"time"

	"tgproxy/internal/media"
	"tgproxy/internal/store"
	"tgproxy/internal/telegram"
)

//go:embed static/*
var staticFS embed.FS

// Deps are the services the web UI needs.
type Deps struct {
	Store    *store.Library
	TGFn     func() *telegram.Client // current client (nil until credentials set)
	StrmBase string                  // e.g. http://192.168.1.2:8080
	MediaDir string

	// SendCode sends the login verification code for the phone.
	SendCode func(ctx context.Context, phone string) error
	// SignIn submits the verification code; returns telegram.ErrPasswordNeeded for 2FA.
	SignIn func(ctx context.Context, code string) error
	// SubmitPassword completes 2FA.
	SubmitPassword func(ctx context.Context, password string) error
	// OnLoginSuccess persists the session and connects the main client.
	// Returns the session bytes (nil on error).
	OnLoginSuccess func() []byte

	// SaveCredentials persists api_id/api_hash and restarts the telegram
	// client with them. Returns error on invalid input.
	SaveCredentials func(ctx context.Context, appID int, appHash string) error

	// HasCredentials reports whether api credentials are configured.
	HasCredentials func() bool
}

// Server is the tgproxy web UI + API.
type Server struct {
	deps Deps
	mux  *http.ServeMux

	mu       sync.Mutex
	scanning bool
	scanLog  []string
	lastErr  string
}

func NewServer(deps Deps) *Server {
	s := &Server{deps: deps, mux: http.NewServeMux()}
	s.routes()
	return s
}

func (s *Server) routes() {
	s.mux.HandleFunc("GET /{$}", s.handleHome)
	s.mux.HandleFunc("GET /static/", s.handleStatic)
	s.mux.HandleFunc("POST /api/send-code", s.handleSendCode)
	s.mux.HandleFunc("POST /api/sign-in", s.handleSignIn)
	s.mux.HandleFunc("POST /api/password", s.handlePassword)
	s.mux.HandleFunc("POST /api/credentials", s.handleCredentials)
	s.mux.HandleFunc("GET /api/status", s.handleStatus)
	s.mux.HandleFunc("GET /api/channels", s.handleChannels)
	s.mux.HandleFunc("POST /api/channels", s.handleAddChannel)
	s.mux.HandleFunc("DELETE /api/channels/{ref}", s.handleDeleteChannel)
	s.mux.HandleFunc("POST /api/scan", s.handleScan)
	s.mux.HandleFunc("POST /api/rebuild", s.handleRebuild)
}

func (s *Server) Handler() http.Handler { return s.mux }

func (s *Server) tg() *telegram.Client {
	if s.deps.TGFn == nil {
		return nil
	}
	return s.deps.TGFn()
}

func (s *Server) connected() bool {
	c := s.tg()
	return c != nil && c.Connected()
}

func (s *Server) handleHome(w http.ResponseWriter, r *http.Request) {
	data, err := staticFS.ReadFile("static/index.html")
	if err != nil {
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Write(data)
}

func (s *Server) handleStatic(w http.ResponseWriter, r *http.Request) {
	name := strings.TrimPrefix(r.URL.Path, "/static/")
	if name == "" || strings.Contains(name, "..") {
		http.NotFound(w, r)
		return
	}
	data, err := staticFS.ReadFile("static/" + name)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	switch {
	case strings.HasSuffix(name, ".css"):
		w.Header().Set("Content-Type", "text/css; charset=utf-8")
	case strings.HasSuffix(name, ".js"):
		w.Header().Set("Content-Type", "application/javascript; charset=utf-8")
	default:
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	}
	w.Write(data)
}

// ---- API handlers ----

func (s *Server) handleCredentials(w http.ResponseWriter, r *http.Request) {
	var req struct {
		AppID   int    `json:"app_id"`
		AppHash string `json:"app_hash"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.AppID == 0 || req.AppHash == "" {
		httpError(w, 400, "need {app_id: int, app_hash: string} — 从 my.telegram.org 获取")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 30*time.Second)
	defer cancel()
	if err := s.deps.SaveCredentials(ctx, req.AppID, req.AppHash); err != nil {
		httpError(w, 400, err.Error())
		return
	}
	w.Write([]byte(`{"ok":true}`))
}

func (s *Server) handleSendCode(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Phone string `json:"phone"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.Phone == "" {
		httpError(w, 400, "need {phone: \"+8613800138000\"}")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 60*time.Second)
	defer cancel()
	if err := s.deps.SendCode(ctx, req.Phone); err != nil {
		httpError(w, 400, err.Error())
		return
	}
	writeJSON(w, map[string]any{"ok": true})
}

func (s *Server) handleSignIn(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Code string `json:"code"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.Code == "" {
		httpError(w, 400, "need {code: \"12345\"}")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 60*time.Second)
	defer cancel()
	if err := s.deps.SignIn(ctx, req.Code); err != nil {
		if errors.Is(err, telegram.ErrPasswordNeeded) {
			httpError(w, 401, "password required")
			return
		}
		httpError(w, 400, err.Error())
		return
	}
	if s.deps.OnLoginSuccess() == nil {
		httpError(w, 500, "login ok but session missing")
		return
	}
	writeJSON(w, map[string]any{"ok": true})
}

func (s *Server) handlePassword(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Password string `json:"password"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.Password == "" {
		httpError(w, 400, "need {password: \"...\"}")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 60*time.Second)
	defer cancel()
	if err := s.deps.SubmitPassword(ctx, req.Password); err != nil {
		httpError(w, 400, err.Error())
		return
	}
	if s.deps.OnLoginSuccess() == nil {
		httpError(w, 500, "login ok but session missing")
		return
	}
	writeJSON(w, map[string]any{"ok": true})
}

func (s *Server) handleStatus(w http.ResponseWriter, r *http.Request) {
	s.mu.Lock()
	defer s.mu.Unlock()
	hasCreds := s.deps.HasCredentials == nil || s.deps.HasCredentials()
	writeJSON(w, map[string]any{
		"has_credentials": hasCreds,
		"authorized":      s.connected(),
		"scanning":   s.scanning,
		"scan_log":   s.scanLog,
		"last_error": s.lastErr,
		"channels":   len(s.deps.Store.ListChannels()),
		"items":      len(s.deps.Store.AllItems()),
	})
}

func (s *Server) handleChannels(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, s.deps.Store.ListChannels())
}

func (s *Server) handleAddChannel(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Ref string `json:"ref"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || strings.TrimSpace(req.Ref) == "" {
		httpError(w, 400, "need {ref: \"@channelname\"}")
		return
	}
	if !s.connected() {
		httpError(w, 400, "telegram not connected; login first")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 30*time.Second)
	defer cancel()
	id, title, err := s.tg().ResolveChannel(ctx, req.Ref)
	if err != nil {
		httpError(w, 400, "resolve failed: "+err.Error())
		return
	}
	s.deps.Store.UpsertChannel(store.Channel{ID: id, Ref: req.Ref, Title: title, Added: true})
	writeJSON(w, map[string]any{"ok": true, "id": id, "title": title})
}

func (s *Server) handleDeleteChannel(w http.ResponseWriter, r *http.Request) {
	ref := r.PathValue("ref")
	s.deps.Store.RemoveChannel(ref)
	w.Write([]byte(`{"ok":true}`))
}

func (s *Server) handleScan(w http.ResponseWriter, r *http.Request) {
	s.mu.Lock()
	if s.scanning {
		s.mu.Unlock()
		httpError(w, 409, "scan already running")
		return
	}
	s.scanning = true
	s.scanLog = nil
	s.lastErr = ""
	s.mu.Unlock()

	go func() {
		defer func() {
			s.mu.Lock()
			s.scanning = false
			s.mu.Unlock()
		}()
		s.scanAll()
	}()
	w.Write([]byte(`{"ok":true,"started":true}`))
}

func (s *Server) logf(format string, args ...any) {
	s.mu.Lock()
	s.scanLog = append(s.scanLog, fmt.Sprintf(format, args...))
	s.mu.Unlock()
}

func (s *Server) scanAll() {
	for _, ch := range s.deps.Store.ListChannels() {
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Minute)
		items, err := s.tg().ScanChannel(ctx, ch.ID, 0)
		cancel()
		if err != nil {
			s.logf("ERROR scan %s: %v", ch.Title, err)
			s.mu.Lock()
			s.lastErr = err.Error()
			s.mu.Unlock()
			continue
		}
		conv := make([]store.Item, len(items))
		for i, it := range items {
			conv[i] = store.Item{ChannelID: it.ChannelID, MessageID: it.MessageID, Name: it.Name, Size: it.Size, MIME: it.MIME, Date: it.Date}
		}
		s.deps.Store.SetItems(ch.ID, conv)
		s.logf("OK %s: %d media items", ch.Title, len(items))
	}
	s.mu.Lock()
	s.lastErr = ""
	s.mu.Unlock()
}

func (s *Server) handleRebuild(w http.ResponseWriter, r *http.Request) {
	all := s.deps.Store.AllItems()
	conv := make([]media.Item, len(all))
	for i, it := range all {
		conv[i] = media.Item{ChannelID: it.ChannelID, MessageID: it.MessageID, Name: it.Name, Size: it.Size}
	}
	groups := media.Classify(conv)
	n, err := media.BuildTree(s.deps.MediaDir, groups, s.deps.StrmBase)
	if err != nil {
		httpError(w, 500, err.Error())
		return
	}
	writeJSON(w, map[string]any{"ok": true, "groups": len(groups), "strm_files": n, "media_dir": s.deps.MediaDir})
}

// ---- helpers ----

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	json.NewEncoder(w).Encode(v)
}

func httpError(w http.ResponseWriter, code int, msg string) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(code)
	json.NewEncoder(w).Encode(map[string]string{"error": msg})
}
