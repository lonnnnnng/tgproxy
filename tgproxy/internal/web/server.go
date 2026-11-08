package web

import (
	"context"
	"embed"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
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
	Context        context.Context
	ScanChannel    func(context.Context, int64, int) ([]telegram.MediaItem, error)
	Connected      func() bool
	NotifyJellyfin func(context.Context) error
	Store          *store.Library
	TGFn           func() *telegram.Client // current client (nil until credentials set)
	StrmBase       string                  // e.g. http://192.168.1.2:8080
	MediaDir       string

	// SendCode sends the login verification code for the phone.
	SendCode func(ctx context.Context, phone string) error
	// SignIn submits the verification code; returns telegram.ErrPasswordNeeded for 2FA.
	SignIn func(ctx context.Context, code string) error
	// SubmitPassword completes 2FA.
	SubmitPassword func(ctx context.Context, password string) error

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

	taskMu          sync.Mutex
	authMu          sync.Mutex
	rebuilding      bool
	mu              sync.Mutex
	scanning        bool
	scanLog         []string
	lastErr         string
	activeChannelID int64
	scanProgress    map[int64]channelScanProgress
	jellyfinState   string
	jellyfinMessage string
	jellyfinUpdated string
	strmFiles       int
	strmFilesKnown  bool
}

type channelScanProgress struct {
	Status      string `json:"status"`
	VideosFound int    `json:"videos_found"`
	Error       string `json:"error,omitempty"`
}

func NewServer(deps Deps) *Server {
	if deps.Context == nil {
		deps.Context = context.Background()
	}
	s := &Server{deps: deps, mux: http.NewServeMux(), scanProgress: make(map[int64]channelScanProgress)}
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

func (s *Server) Handler() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "GET" && r.Method != "HEAD" && strings.HasPrefix(r.URL.Path, "/api/") {
			// long: 管理 API 只接受同源浏览器操作，CLI 无 Origin 的请求仍可正常使用。
			if origin := r.Header.Get("Origin"); origin != "" {
				u, err := url.Parse(origin)
				scheme := "http"
				if r.TLS != nil {
					scheme = "https"
				}
				if err != nil || !strings.EqualFold(u.Host, r.Host) || u.Scheme != scheme {
					httpError(w, 403, "不允许跨站管理请求")
					return
				}
			}
			if r.Header.Get("Sec-Fetch-Site") == "cross-site" {
				httpError(w, 403, "不允许跨站管理请求")
				return
			}
			if ct := r.Header.Get("Content-Type"); ct != "" && !strings.HasPrefix(strings.ToLower(ct), "application/json") {
				httpError(w, 415, "请使用 application/json")
				return
			}
			r.Body = http.MaxBytesReader(w, r.Body, 1<<20)
		}
		s.mux.ServeHTTP(w, r)
	})
}

func (s *Server) tg() *telegram.Client {
	if s.deps.TGFn == nil {
		return nil
	}
	return s.deps.TGFn()
}

func (s *Server) connected() bool {
	if s.deps.Connected != nil {
		return s.deps.Connected()
	}
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
	case strings.HasSuffix(name, ".svg"):
		w.Header().Set("Content-Type", "image/svg+xml")
	default:
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	}
	w.Write(data)
}

// ---- API handlers ----

func (s *Server) handleCredentials(w http.ResponseWriter, r *http.Request) {
	if !s.authMu.TryLock() {
		httpError(w, 409, "另一个登录步骤正在执行")
		return
	}
	defer s.authMu.Unlock()
	if !s.taskMu.TryLock() {
		httpError(w, 409, "频道任务进行中，请稍后登录或修改凭证")
		return
	}
	defer s.taskMu.Unlock()
	var req struct {
		AppID   int    `json:"app_id"`
		AppHash string `json:"app_hash"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.AppID <= 0 || req.AppHash == "" {
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
	if !s.authMu.TryLock() {
		httpError(w, 409, "另一个登录步骤正在执行")
		return
	}
	defer s.authMu.Unlock()
	if !s.taskMu.TryLock() {
		httpError(w, 409, "频道任务进行中，请稍后登录或修改凭证")
		return
	}
	defer s.taskMu.Unlock()
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
	if !s.authMu.TryLock() {
		httpError(w, 409, "另一个登录步骤正在执行")
		return
	}
	defer s.authMu.Unlock()
	if !s.taskMu.TryLock() {
		httpError(w, 409, "频道任务进行中，请稍后登录或修改凭证")
		return
	}
	defer s.taskMu.Unlock()
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
	writeJSON(w, map[string]any{"ok": true})
}

func (s *Server) handlePassword(w http.ResponseWriter, r *http.Request) {
	if !s.authMu.TryLock() {
		httpError(w, 409, "另一个登录步骤正在执行")
		return
	}
	defer s.authMu.Unlock()
	if !s.taskMu.TryLock() {
		httpError(w, 409, "频道任务进行中，请稍后登录或修改凭证")
		return
	}
	defer s.taskMu.Unlock()
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
	writeJSON(w, map[string]any{"ok": true})
}

func (s *Server) handleStatus(w http.ResponseWriter, r *http.Request) {
	rev, items := s.deps.Store.Snapshot()
	built, hasTree := media.TreeRevision(s.deps.MediaDir)
	videos := 0
	channelVideoCounts := make(map[int64]int)
	for _, it := range items {
		if media.IsVideo(it.Name) {
			videos++
			channelVideoCounts[it.ChannelID]++
		}
	}
	s.mu.Lock()
	scanning, rebuilding, lastErr := s.scanning, s.rebuilding, s.lastErr
	log := append([]string(nil), s.scanLog...)
	scanProgress := make(map[int64]channelScanProgress, len(s.scanProgress))
	for id, progress := range s.scanProgress {
		scanProgress[id] = progress
	}
	activeChannelID := s.activeChannelID
	jellyfinState, jellyfinMessage, jellyfinUpdated := s.jellyfinState, s.jellyfinMessage, s.jellyfinUpdated
	strmFiles := s.strmFiles
	strmFilesKnown := s.strmFilesKnown
	s.mu.Unlock()
	hasCreds := s.deps.HasCredentials == nil || s.deps.HasCredentials()
	jellyfinConfigured := s.deps.NotifyJellyfin != nil
	if !jellyfinConfigured {
		jellyfinState = "not_configured"
		if jellyfinMessage == "" {
			jellyfinMessage = "自动通知未配置；媒体树生成后，请在 Jellyfin 中手动刷新媒体库。"
		}
	} else if jellyfinState == "" {
		jellyfinState = "not_run"
		jellyfinMessage = "媒体树生成后会向 Jellyfin 提交刷新请求。"
	}
	writeJSON(w, map[string]any{
		"has_credentials": hasCreds, "authorized": s.connected(), "scanning": scanning, "rebuilding": rebuilding,
		"scan_log": log, "last_error": lastErr, "channels": len(s.deps.Store.ListChannels()), "items": len(items), "videos": videos,
		"needs_rebuild": !hasTree || built != rev, "active_channel_id": activeChannelID, "scan_progress": scanProgress,
		"channel_video_counts": channelVideoCounts, "jellyfin_configured": jellyfinConfigured, "jellyfin_state": jellyfinState,
		"jellyfin_message": jellyfinMessage, "jellyfin_updated_at": jellyfinUpdated, "strm_files": strmFiles, "strm_files_known": strmFilesKnown,
	})
}

func (s *Server) handleChannels(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, s.deps.Store.ListChannels())
}

func (s *Server) handleAddChannel(w http.ResponseWriter, r *http.Request) {
	if !s.taskMu.TryLock() {
		httpError(w, 409, "扫描或重建进行中，请稍后修改频道")
		return
	}
	defer s.taskMu.Unlock()
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
	if err := s.deps.Store.UpsertChannel(store.Channel{ID: id, Ref: strings.TrimSpace(req.Ref), Title: title, Added: true}); err != nil {
		httpError(w, 500, "保存频道失败: "+err.Error())
		return
	}
	writeJSON(w, map[string]any{"ok": true, "id": id, "title": title})
}

func (s *Server) handleDeleteChannel(w http.ResponseWriter, r *http.Request) {
	if !s.taskMu.TryLock() {
		httpError(w, 409, "扫描或重建进行中，请稍后修改频道")
		return
	}
	defer s.taskMu.Unlock()
	ref := r.PathValue("ref")
	removed, err := s.deps.Store.RemoveChannel(ref)
	if err != nil {
		httpError(w, 500, "移除频道失败: "+err.Error())
		return
	}
	if !removed {
		httpError(w, 404, "频道不存在")
		return
	}
	w.Write([]byte(`{"ok":true}`))
}

func (s *Server) handleScan(w http.ResponseWriter, r *http.Request) {
	if !s.connected() {
		httpError(w, 503, "Telegram 未连接，请先登录或等待重连")
		return
	}
	channels := s.deps.Store.ListChannels()
	if len(channels) == 0 {
		httpError(w, 400, "请先添加频道")
		return
	}
	if !s.taskMu.TryLock() {
		httpError(w, 409, "扫描或重建正在执行")
		return
	}
	s.mu.Lock()
	s.scanning = true
	s.scanLog = nil
	s.lastErr = ""
	s.activeChannelID = 0
	s.scanProgress = make(map[int64]channelScanProgress, len(channels))
	for _, ch := range channels {
		s.scanProgress[ch.ID] = channelScanProgress{Status: "queued"}
	}
	s.mu.Unlock()
	go func() {
		defer s.taskMu.Unlock()
		defer func() { s.mu.Lock(); s.scanning = false; s.mu.Unlock() }()
		s.scanAll()
	}()
	writeJSON(w, map[string]any{"ok": true, "started": true})
}
func (s *Server) logf(format string, args ...any) {
	s.mu.Lock()
	s.scanLog = append(s.scanLog, fmt.Sprintf(format, args...))
	if len(s.scanLog) > 200 {
		s.scanLog = append([]string(nil), s.scanLog[len(s.scanLog)-200:]...)
	}
	s.mu.Unlock()
}

func (s *Server) updateChannelProgress(id int64, status string, videosFound int, errMsg string) {
	s.mu.Lock()
	if s.scanProgress == nil {
		s.scanProgress = make(map[int64]channelScanProgress)
	}
	s.scanProgress[id] = channelScanProgress{Status: status, VideosFound: videosFound, Error: errMsg}
	if status == "scanning" {
		s.activeChannelID = id
	} else if s.activeChannelID == id {
		s.activeChannelID = 0
	}
	s.mu.Unlock()
}

func (s *Server) scanAll() {
	var failures []string
	scan := s.deps.ScanChannel
	if scan == nil {
		scan = func(ctx context.Context, ch int64, limit int) ([]telegram.MediaItem, error) {
			c := s.tg()
			if c == nil {
				return nil, errors.New("Telegram 客户端不可用")
			}
			return c.ScanChannelProgress(ctx, ch, limit, func(n int) {
				s.updateChannelProgress(ch, "scanning", n, "")
				s.logf("扫描进度：频道 %d 已发现 %d 个视频", ch, n)
			})
		}
	}
	for _, ch := range s.deps.Store.ListChannels() {
		if s.deps.Context.Err() != nil {
			failures = append(failures, "扫描已取消")
			break
		}
		s.updateChannelProgress(ch.ID, "scanning", 0, "")
		s.logf("正在扫描 %s 的视频历史…", ch.Title)
		ctx, cancel := context.WithTimeout(s.deps.Context, 30*time.Minute)
		items, err := scan(ctx, ch.ID, 0)
		cancel()
		found := len(items)
		if err != nil {
			found = 0
		}
		if err == nil {
			conv := make([]store.Item, len(items))
			for i, it := range items {
				conv[i] = store.Item{ChannelID: it.ChannelID, MessageID: it.MessageID, Name: it.Name, Size: it.Size, MIME: it.MIME, Date: it.Date}
			}
			err = s.deps.Store.SetItems(ch.ID, conv)
		}
		if err != nil {
			msg := fmt.Sprintf("扫描 %s 失败: %v（保留原索引）", ch.Title, err)
			failures = append(failures, msg)
			s.updateChannelProgress(ch.ID, "failed", 0, err.Error())
			s.logf("ERROR %s", msg)
			continue
		}
		s.updateChannelProgress(ch.ID, "completed", found, "")
		s.logf("OK %s: %d 个视频", ch.Title, len(items))
	}
	if s.deps.Context.Err() == nil {
		result, err := s.rebuild(s.deps.Context)
		if err != nil {
			failures = append(failures, "生成媒体树失败: "+err.Error())
			s.logf("ERROR 重建: %v", err)
		} else {
			s.logf("OK 已自动生成 %v 个视频入口；%v", result["strm_files"], result["jellyfin_message"])
			if warning, _ := result["warning"].(string); warning != "" {
				failures = append(failures, warning)
			}
		}
	}
	s.mu.Lock()
	s.lastErr = strings.Join(failures, "\n")
	s.activeChannelID = 0
	s.mu.Unlock()
}

func (s *Server) rebuild(ctx context.Context) (map[string]any, error) {
	s.mu.Lock()
	s.rebuilding = true
	s.mu.Unlock()
	rev, all := s.deps.Store.Snapshot()
	conv := make([]media.Item, len(all))
	for i, it := range all {
		conv[i] = media.Item{ChannelID: it.ChannelID, MessageID: it.MessageID, Name: it.Name, Size: it.Size}
	}
	groups := media.Classify(conv)
	n, err := media.BuildTree(s.deps.MediaDir, groups, s.deps.StrmBase, rev)
	if err != nil {
		s.mu.Lock()
		s.rebuilding = false
		s.mu.Unlock()
		return nil, err
	}
	// long：媒体树与 Jellyfin 刷新是两个阶段，生成完成后先释放重建状态再等待远端响应。
	s.mu.Lock()
	s.rebuilding = false
	s.strmFiles = n
	s.strmFilesKnown = true
	if s.deps.NotifyJellyfin != nil {
		s.jellyfinState = "notifying"
		s.jellyfinMessage = "媒体树已生成，正在向 Jellyfin 提交刷新请求。"
	} else {
		s.jellyfinState = "not_configured"
		s.jellyfinMessage = "媒体树已生成；自动通知未配置，请在 Jellyfin 中手动刷新媒体库。"
	}
	s.mu.Unlock()
	result := map[string]any{"ok": true, "groups": len(groups), "strm_files": n, "media_dir": s.deps.MediaDir, "jellyfin_message": "媒体树已生成；请在 Jellyfin 中确认库扫描结果。"}
	if s.deps.NotifyJellyfin != nil {
		err := s.deps.NotifyJellyfin(ctx)
		s.mu.Lock()
		s.jellyfinUpdated = time.Now().UTC().Format(time.RFC3339)
		if err != nil {
			s.jellyfinState = "failed"
			s.jellyfinMessage = "媒体树已生成，但 Jellyfin 刷新请求失败：" + err.Error()
		} else {
			s.jellyfinState = "notified"
			s.jellyfinMessage = "已向 Jellyfin 发起库刷新请求；入库完成情况请在 Jellyfin 中确认。"
		}
		s.mu.Unlock()
		if err != nil {
			result["warning"] = "媒体树已生成，但通知 Jellyfin 失败: " + err.Error()
		} else {
			result["jellyfin_message"] = "已向 Jellyfin 发起库刷新请求；入库完成情况请在 Jellyfin 中确认。"
		}
	}
	return result, nil
}
func (s *Server) handleRebuild(w http.ResponseWriter, r *http.Request) {
	if !s.taskMu.TryLock() {
		httpError(w, 409, "扫描或重建正在执行，请等待完成")
		return
	}
	defer s.taskMu.Unlock()
	result, err := s.rebuild(r.Context())
	if err != nil {
		s.mu.Lock()
		s.lastErr = err.Error()
		s.mu.Unlock()
		httpError(w, 500, err.Error())
		return
	}
	s.mu.Lock()
	s.lastErr, _ = result["warning"].(string)
	s.mu.Unlock()
	writeJSON(w, result)
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
