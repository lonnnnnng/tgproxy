package web

import (
	"context"
	"embed"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"tgproxy/internal/logbus"
	"tgproxy/internal/media"
	"tgproxy/internal/mediaserver"
	"tgproxy/internal/store"
	"tgproxy/internal/telegram"
)

//go:embed static/*
var staticFS embed.FS

// Deps are the services the web UI needs.
type Deps struct {
	Context     context.Context
	ScanChannel func(context.Context, int64, int) ([]telegram.MediaItem, error)
	Connected   func() bool
	// MediaServer returns the current Emby base URL and API key.
	MediaServer func() (string, string)
	// SaveMediaServer persists the Emby base URL and API key.
	SaveMediaServer func(url, key string) error
	// AutoScan returns the scheduled scan state (enabled, minutes).
	AutoScan func() (bool, int)
	// SaveAutoScan persists the scheduled scan state.
	SaveAutoScan func(enabled bool, minutes int) error
	// TestMediaServer is an optional test hook for refresh notifications.
	TestMediaServer func(context.Context, string, string) error
	// Logs is the shared in-memory log buffer for the console.
	Logs     *logbus.Bus
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
	lastErr         string
	activeChannelID int64
	scanProgress    map[int64]channelScanProgress
	mediaState      string
	mediaMessage    string
	mediaUpdated    string
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
	if deps.AutoScan != nil {
		go s.autoScanLoop()
	}
	go s.ensureAllLibraries()
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
	s.mux.HandleFunc("GET /api/telegram/channels", s.handleJoinedChannels)
	s.mux.HandleFunc("POST /api/channels", s.handleAddChannel)
	s.mux.HandleFunc("DELETE /api/channels/{ref}", s.handleDeleteChannel)
	s.mux.HandleFunc("POST /api/channels/{id}/scan", s.handleScanChannel)
	s.mux.HandleFunc("POST /api/scan", s.handleScan)
	s.mux.HandleFunc("POST /api/rebuild", s.handleRebuild)
	s.mux.HandleFunc("GET /api/logs", s.handleLogs)
	s.mux.HandleFunc("GET /api/media-server", s.handleGetMediaServer)
	s.mux.HandleFunc("POST /api/media-server", s.handleSaveMediaServer)
	s.mux.HandleFunc("GET /api/auto-scan", s.handleGetAutoScan)
	s.mux.HandleFunc("POST /api/auto-scan", s.handleSaveAutoScan)
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
	scanProgress := make(map[int64]channelScanProgress, len(s.scanProgress))
	for id, progress := range s.scanProgress {
		scanProgress[id] = progress
	}
	activeChannelID := s.activeChannelID
	mediaState, mediaMessage, mediaUpdated := s.mediaState, s.mediaMessage, s.mediaUpdated
	strmFiles := s.strmFiles
	strmFilesKnown := s.strmFilesKnown
	s.mu.Unlock()
	hasCreds := s.deps.HasCredentials == nil || s.deps.HasCredentials()
	mediaURL, mediaKey := s.mediaServerConfig()
	mediaConfigured := mediaURL != "" && mediaKey != ""
	autoEnabled, autoMinutes := false, 0
	if s.deps.AutoScan != nil {
		autoEnabled, autoMinutes = s.deps.AutoScan()
	}
	if !mediaConfigured {
		mediaState = "not_configured"
		if mediaMessage == "" {
			mediaMessage = "自动通知未配置；媒体树生成后，请在 Emby 中手动刷新媒体库。"
		}
	} else if mediaState == "" {
		mediaState = "not_run"
		mediaMessage = "媒体树生成后会向 Emby 提交刷新请求。"
	}
	writeJSON(w, map[string]any{
		"has_credentials": hasCreds, "authorized": s.connected(), "scanning": scanning, "rebuilding": rebuilding,
		"last_error": lastErr, "channels": len(s.deps.Store.ListChannels()), "items": len(items), "videos": videos,
		"needs_rebuild": !hasTree || built != rev, "active_channel_id": activeChannelID, "scan_progress": scanProgress,
		"channel_video_counts": channelVideoCounts, "media_server_configured": mediaConfigured, "media_server_state": mediaState,
		"media_server_message": mediaMessage, "media_server_updated_at": mediaUpdated, "media_server_url": mediaURL, "strm_files": strmFiles, "strm_files_known": strmFilesKnown,
		"auto_scan_enabled": autoEnabled, "auto_scan_minutes": autoMinutes,
	})
}

func (s *Server) handleChannels(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, s.deps.Store.ListChannels())
}

func (s *Server) handleJoinedChannels(w http.ResponseWriter, r *http.Request) {
	if !s.connected() {
		httpError(w, 503, "Telegram 未连接，请先登录或等待重连")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 25*time.Second)
	defer cancel()
	list, err := s.tg().JoinedChannels(ctx)
	if err != nil {
		httpError(w, 500, "读取已加入频道失败: "+err.Error())
		return
	}
	writeJSON(w, list)
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
	dir := fmt.Sprintf("%s [%d]", media.SanitizeDirName(title), id)
	if media.SanitizeDirName(title) == "" {
		dir = fmt.Sprintf("频道 [%d]", id)
	}
	library := title
	for _, c := range s.deps.Store.ListChannels() {
		if c.Title == title && c.ID != id {
			library = fmt.Sprintf("%s [%d]", title, id)
			break
		}
	}
	ch := store.Channel{ID: id, Ref: strings.TrimSpace(req.Ref), Title: title, Dir: dir, LibraryName: library, Added: true}
	if err := s.deps.Store.UpsertChannel(ch); err != nil {
		httpError(w, 500, "保存频道失败: "+err.Error())
		return
	}
	ctx2, cancel2 := context.WithTimeout(context.Background(), 20*time.Second)
	err = s.ensureChannelLibrary(ctx2, ch)
	cancel2()
	result := map[string]any{"ok": true, "id": id, "title": title}
	if err != nil {
		s.logf("error", "创建媒体库失败：%v", err)
		result["warning"] = "频道已添加，但创建媒体库失败：" + err.Error()
	}
	writeJSON(w, result)
}

func (s *Server) ensureAllLibraries() {
	if s.deps.Store == nil || s.deps.MediaDir == "" {
		return
	}
	url, key := s.mediaServerConfig()
	if url == "" || key == "" {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	for _, ch := range s.deps.Store.ListChannels() {
		if err := s.ensureChannelLibrary(ctx, ch); err != nil {
			s.logf("error", "同步媒体库失败（%s）：%v", ch.Title, err)
		}
	}
}

func (s *Server) ensureChannelLibrary(ctx context.Context, ch store.Channel) error {
	url, key := s.mediaServerConfig()
	if url == "" || key == "" {
		return nil
	}
	if ch.Dir == "" {
		return nil
	}
	path := filepath.Join(s.deps.MediaDir, ch.Dir)
	if err := os.MkdirAll(path, 0o755); err != nil {
		return err
	}
	var err error
	for attempt := 0; attempt < 2; attempt++ {
		if err = mediaserver.EnsureLibrary(ctx, url, key, ch.LibraryName, path); err == nil {
			break
		}
		if attempt == 0 {
			select {
			case <-ctx.Done():
				return err
			case <-time.After(time.Second):
			}
		}
	}
	if err != nil {
		return err
	}
	s.logf("success", "已创建/配置媒体库 %q → %s", ch.LibraryName, path)
	return nil
}

func (s *Server) deleteChannelLibrary(ctx context.Context, ch store.Channel) error {
	url, key := s.mediaServerConfig()
	if url == "" || key == "" || ch.Dir == "" {
		return nil
	}
	path := filepath.Join(s.deps.MediaDir, ch.Dir)
	if err := mediaserver.DeleteLibraryByPath(ctx, url, key, path); err != nil {
		return err
	}
	s.logf("success", "已删除媒体库 %q", ch.LibraryName)
	return nil
}

func (s *Server) handleDeleteChannel(w http.ResponseWriter, r *http.Request) {
	if !s.taskMu.TryLock() {
		httpError(w, 409, "扫描或重建进行中，请稍后修改频道")
		return
	}
	defer s.taskMu.Unlock()
	ref := r.PathValue("ref")
	ch, found := s.deps.Store.ChannelByRef(ref)
	if !found {
		httpError(w, 404, "频道不存在")
		return
	}
	removed, err := s.deps.Store.RemoveChannel(ref)
	if err != nil {
		httpError(w, 500, "移除频道失败: "+err.Error())
		return
	}
	if !removed {
		httpError(w, 404, "频道不存在")
		return
	}
	ctx2, cancel2 := context.WithTimeout(context.Background(), 20*time.Second)
	err = s.deleteChannelLibrary(ctx2, ch)
	cancel2()
	result := map[string]any{"ok": true}
	if err != nil {
		s.logf("error", "删除媒体库失败：%v", err)
		result["warning"] = "频道已移除，但删除媒体库失败：" + err.Error()
	}
	if ch.Dir != "" {
		if err := os.RemoveAll(filepath.Join(s.deps.MediaDir, ch.Dir)); err != nil {
			s.logf("error", "删除频道媒体目录失败：%v", err)
			result["warning"] = fmt.Sprintf("频道已移除，但删除媒体目录失败：%v", err)
		}
	}
	writeJSON(w, result)
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
	if !s.startScan(channels) {
		httpError(w, 409, "扫描或重建正在执行")
		return
	}
	writeJSON(w, map[string]any{"ok": true, "started": true})
}

func (s *Server) handleScanChannel(w http.ResponseWriter, r *http.Request) {
	if !s.connected() {
		httpError(w, 503, "Telegram 未连接，请先登录或等待重连")
		return
	}
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		httpError(w, 400, "频道 ID 无效")
		return
	}
	ch, ok := s.deps.Store.ChannelByID(id)
	if !ok {
		httpError(w, 404, "频道不存在")
		return
	}
	if !s.startScan([]store.Channel{ch}) {
		httpError(w, 409, "扫描或重建正在执行")
		return
	}
	writeJSON(w, map[string]any{"ok": true, "started": true, "id": id})
}

func (s *Server) startScan(channels []store.Channel) bool {
	if len(channels) == 0 || !s.taskMu.TryLock() {
		return false
	}
	s.mu.Lock()
	s.scanning = true
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
		s.scanChannels(channels)
	}()
	return true
}
func (s *Server) logf(level, format string, args ...any) {
	if s.deps.Logs != nil {
		s.deps.Logs.Add(level, format, args...)
	}
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

func (s *Server) scanChannels(channels []store.Channel) {
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
				s.logf("info", "扫描进度：频道 %d 已发现 %d 个视频", ch, n)
			})
		}
	}
	for _, ch := range channels {
		if s.deps.Context.Err() != nil {
			failures = append(failures, "扫描已取消")
			break
		}
		s.updateChannelProgress(ch.ID, "scanning", 0, "")
		s.logf("info", "正在扫描 %s 的视频历史…", ch.Title)
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
			s.logf("error", "扫描失败：%s", msg)
			continue
		}
		s.updateChannelProgress(ch.ID, "completed", found, "")
		s.logf("success", "%s 扫描完成：%d 个视频", ch.Title, len(items))
	}
	if s.deps.Context.Err() == nil {
		result, err := s.rebuild(s.deps.Context)
		if err != nil {
			failures = append(failures, "生成媒体树失败: "+err.Error())
			s.logf("error", "重建失败：%v", err)
		} else {
			s.logf("success", "媒体树已生成：%v 个入口；%v", result["strm_files"], result["media_server_message"])
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
	dirs := map[int64]string{}
	for _, ch := range s.deps.Store.ListChannels() {
		dirs[ch.ID] = ch.Dir
	}
	conv := make([]media.Item, len(all))
	for i, it := range all {
		conv[i] = media.Item{ChannelID: it.ChannelID, MessageID: it.MessageID, Name: it.Name, Size: it.Size, ChannelDir: dirs[it.ChannelID]}
	}
	groups := media.Classify(conv)
	n, err := media.BuildTree(s.deps.MediaDir, groups, s.deps.StrmBase, rev)
	if err != nil {
		s.mu.Lock()
		s.rebuilding = false
		s.mu.Unlock()
		return nil, err
	}
	// long：媒体树与 Emby 刷新是两个阶段，生成完成后先释放重建状态再等待远端响应。
	s.mu.Lock()
	s.rebuilding = false
	s.strmFiles = n
	s.strmFilesKnown = true
	if s.mediaServerConfigured() {
		s.mediaState = "notifying"
		s.mediaMessage = "媒体树已生成，正在向 Emby 提交刷新请求。"
	} else {
		s.mediaState = "not_configured"
		s.mediaMessage = "媒体树已生成；自动通知未配置，请在 Emby 中手动刷新媒体库。"
	}
	s.mu.Unlock()
	result := map[string]any{"ok": true, "groups": len(groups), "strm_files": n, "media_dir": s.deps.MediaDir, "media_server_message": "媒体树已生成；请在 Emby 中确认库扫描结果。"}
	if s.mediaServerConfigured() {
		err := s.notifyMediaServer(ctx)
		s.mu.Lock()
		s.mediaUpdated = time.Now().UTC().Format(time.RFC3339)
		if err != nil {
			s.mediaState = "failed"
			s.mediaMessage = "媒体树已生成，但 Emby 刷新请求失败：" + err.Error()
		} else {
			s.mediaState = "notified"
			s.mediaMessage = "已向 Emby 发起库刷新请求；入库完成情况请在 Emby 中确认。"
		}
		s.mu.Unlock()
		if err != nil {
			result["warning"] = "媒体树已生成，但通知 Emby 失败: " + err.Error()
		} else {
			result["media_server_message"] = "已向 Emby 发起库刷新请求；入库完成情况请在 Emby 中确认。"
		}
	}
	return result, nil
}
func (s *Server) autoScanLoop() {
	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()
	last := time.Now()
	for {
		select {
		case <-s.deps.Context.Done():
			return
		case now := <-ticker.C:
			if s.deps.AutoScan == nil {
				continue
			}
			enabled, minutes := s.deps.AutoScan()
			if !enabled || minutes < 1 {
				continue
			}
			if now.Sub(last) < time.Duration(minutes)*time.Minute {
				continue
			}
			if !s.connected() {
				continue
			}
			channels := s.deps.Store.ListChannels()
			if len(channels) == 0 {
				continue
			}
			if s.startScan(channels) {
				last = now
				s.logf("info", "自动扫描已启动（间隔 %d 分钟）", minutes)
			}
		}
	}
}

func (s *Server) handleGetAutoScan(w http.ResponseWriter, r *http.Request) {
	enabled, minutes := false, 0
	if s.deps.AutoScan != nil {
		enabled, minutes = s.deps.AutoScan()
	}
	writeJSON(w, map[string]any{"enabled": enabled, "minutes": minutes})
}

func (s *Server) handleSaveAutoScan(w http.ResponseWriter, r *http.Request) {
	if s.deps.SaveAutoScan == nil {
		httpError(w, 500, "自动扫描配置不可用")
		return
	}
	var req struct {
		Enabled bool `json:"enabled"`
		Minutes int  `json:"minutes"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpError(w, 400, "请求格式错误")
		return
	}
	if err := s.deps.SaveAutoScan(req.Enabled, req.Minutes); err != nil {
		httpError(w, 400, err.Error())
		return
	}
	writeJSON(w, map[string]any{"ok": true, "enabled": req.Enabled, "minutes": req.Minutes})
}

func (s *Server) mediaServerConfig() (string, string) {
	if s.deps.MediaServer == nil {
		return "", ""
	}
	return s.deps.MediaServer()
}

func (s *Server) mediaServerConfigured() bool {
	url, key := s.mediaServerConfig()
	return url != "" && key != ""
}

func (s *Server) notifyMediaServer(ctx context.Context) error {
	url, key := s.mediaServerConfig()
	if url == "" || key == "" {
		return errors.New("媒体服务器未配置")
	}
	s.logf("info", "通知媒体服务器刷新：%s", url)
	var err error
	if s.deps.TestMediaServer != nil {
		err = s.deps.TestMediaServer(ctx, url, key)
	} else {
		err = mediaserver.Refresh(ctx, url, key)
	}
	if err != nil {
		s.logf("error", "媒体服务器刷新失败：%v", err)
	} else {
		s.logf("success", "媒体服务器已接收刷新请求")
	}
	return err
}

func (s *Server) handleLogs(w http.ResponseWriter, r *http.Request) {
	after, _ := strconv.ParseInt(r.URL.Query().Get("after"), 10, 64)
	if s.deps.Logs == nil {
		writeJSON(w, map[string]any{"entries": []any{}, "next": after})
		return
	}
	entries, next := s.deps.Logs.Since(after)
	writeJSON(w, map[string]any{"entries": entries, "next": next})
}

func (s *Server) handleGetMediaServer(w http.ResponseWriter, r *http.Request) {
	url, key := s.mediaServerConfig()
	writeJSON(w, map[string]any{"url": url, "configured": url != "" && key != ""})
}

func (s *Server) handleSaveMediaServer(w http.ResponseWriter, r *http.Request) {
	if s.deps.SaveMediaServer == nil {
		httpError(w, 500, "媒体服务器配置不可用")
		return
	}
	var req struct {
		URL    string `json:"url"`
		APIKey string `json:"api_key"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpError(w, 400, "请求格式错误")
		return
	}
	req.URL = strings.TrimSpace(req.URL)
	req.APIKey = strings.TrimSpace(req.APIKey)
	if req.URL == "" && req.APIKey == "" {
		if err := s.deps.SaveMediaServer("", ""); err != nil {
			httpError(w, 400, err.Error())
			return
		}
		writeJSON(w, map[string]any{"ok": true, "configured": false})
		return
	}
	if req.APIKey == "" {
		_, old := s.mediaServerConfig()
		req.APIKey = old
	}
	if req.APIKey == "" {
		httpError(w, 400, "请填写 API Key")
		return
	}
	if err := s.deps.SaveMediaServer(req.URL, req.APIKey); err != nil {
		httpError(w, 400, err.Error())
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 20*time.Second)
	defer cancel()
	resp := map[string]any{"ok": true, "configured": true, "tested": true}
	if err := s.notifyMediaServer(ctx); err != nil {
		resp["tested"] = false
		resp["test_error"] = err.Error()
	}
	writeJSON(w, resp)
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
