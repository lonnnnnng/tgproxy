// Package mobilecore 为 Android 提供不依赖命令行、Web 控制台或 Jellyfin 的播放内核。
package mobilecore

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gotd/td/tgerr"
	"github.com/iyear/tdl/core/dcpool"
	"tgproxy/internal/config"
	"tgproxy/internal/stream"
	"tgproxy/internal/telegram"
)

type video struct {
	Key       string `json:"key"`
	ChannelID int64  `json:"channel_id"`
	MessageID int    `json:"message_id"`
	Name      string `json:"name"`
	Size      int64  `json:"size"`
	MIME      string `json:"mime"`
	Date      int64  `json:"date"`
}

// Engine 只通过 JNI 暴露控制接口；HTTP 仅提供经过随机令牌鉴权的媒体读取。
type Engine struct {
	mu          sync.Mutex
	op          sync.Mutex
	ctx         context.Context
	cancel      context.CancelFunc
	wg          sync.WaitGroup
	client      *telegram.Client
	cache       *stream.Cache
	resolved    *stream.CachedSource
	server      *http.Server
	base, token string
	session     []byte
	state       string
	message     string
	closed      bool
	scanning    bool
	found       int
	items       map[string]video
	scanCancel  context.CancelFunc
	fixture     []byte
	requests    atomic.Int64
	ranges      atomic.Int64
}

// NewEngine 建立本机入口；restoredSession 由 Android Keystore 解密后传入，不在 Go 侧落盘。
func NewEngine(appID int64, appHash, restoredSession string) (*Engine, error) {
	defaults := config.Defaults()
	if appID == 0 && strings.TrimSpace(appHash) == "" {
		appID, appHash = int64(defaults.AppID), defaults.AppHash
	}
	if appID <= 0 || appID > 2147483647 || len(strings.TrimSpace(appHash)) != 32 {
		return nil, errors.New("请检查 API ID 和 API Hash")
	}
	for _, char := range strings.TrimSpace(appHash) {
		if !strings.ContainsRune("0123456789abcdefABCDEF", char) {
			return nil, errors.New("请检查 API ID 和 API Hash")
		}
	}
	var sess []byte
	var err error
	if restoredSession != "" {
		sess, err = base64.StdEncoding.DecodeString(restoredSession)
		if err != nil || len(sess) == 0 || len(sess) > 1<<20 {
			return nil, errors.New("保存的会话无效，请重新登录")
		}
	}
	ctx, cancel := context.WithCancel(context.Background())
	e := &Engine{ctx: ctx, cancel: cancel, client: telegram.New(int(appID), appHash), state: "signed_out", items: map[string]video{}, session: sess}
	random := make([]byte, 32)
	if _, err = rand.Read(random); err != nil {
		cancel()
		return nil, err
	}
	e.token = hex.EncodeToString(random)
	// long: 手机首版限定 64 MiB 缓存、3 块预取，避免照搬服务器内存预算挤压解码器。
	e.cache = stream.NewCache(nil, 512<<10, 64<<20, 3)
	e.resolved = stream.NewCachedSource(resolver{e}, time.Minute, 32)
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		e.cache.Close()
		e.resolved.Close()
		cancel()
		return nil, errors.New("无法启动本机播放服务")
	}
	e.base = "http://" + listener.Addr().String()
	e.server = &http.Server{Handler: e.guard(e.cache.Handler(e.resolved)), ReadHeaderTimeout: 5 * time.Second, IdleTimeout: 30 * time.Second}
	e.wg.Add(1)
	go func() { defer e.wg.Done(); _ = e.server.Serve(listener) }()
	if len(sess) > 0 {
		e.beginConnection()
	}
	return e, nil
}

func (e *Engine) guard(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// long: 回环监听仍可被其他本机应用访问，随机令牌必须在媒体解析前校验。
		if subtle.ConstantTimeCompare([]byte(r.Header.Get("Authorization")), []byte("Bearer "+e.token)) != 1 {
			http.Error(w, "unauthorized", 401)
			return
		}
		if !strings.HasPrefix(r.URL.Path, "/stream/") {
			http.NotFound(w, r)
			return
		}
		e.requests.Add(1)
		if r.Header.Get("Range") != "" {
			e.ranges.Add(1)
		}
		next.ServeHTTP(w, r)
	})
}

// long: 内核的 exported 方法只使用 gomobile 支持的简单类型，复杂结果以 JSON 交给 Kotlin。
func (e *Engine) Status() string {
	e.mu.Lock()
	defer e.mu.Unlock()
	hits, misses, used := e.cache.Stat()
	return marshal(map[string]any{"state": e.state, "message": e.message, "connected": e.client.Connected(), "scanning": e.scanning, "found": e.found, "items": len(e.items), "cache_bytes": used, "hits": hits, "misses": misses, "requests": e.requests.Load(), "range_requests": e.ranges.Load()})
}

func (e *Engine) Session() string {
	e.mu.Lock()
	defer e.mu.Unlock()
	return base64.StdEncoding.EncodeToString(e.session)
}
func (e *Engine) Token() string { return e.token }

func (e *Engine) PlaybackURL(key string) (string, error) {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.closed {
		return "", errors.New("播放服务已关闭")
	}
	_, ok := e.items[key]
	if !ok && !(key == "validation" && len(e.fixture) > 0) {
		return "", errors.New("视频不在本地索引中")
	}
	return e.base + "/stream/" + key, nil
}

func (e *Engine) SendCode(phone string) (string, error) {
	if !e.op.TryLock() {
		return "", errors.New("请等待当前操作结束")
	}
	defer e.op.Unlock()
	phone = strings.TrimSpace(phone)
	if !strings.HasPrefix(phone, "+") || len(phone) < 7 || len(phone) > 18 {
		return "", errors.New("请输入带国家区号的手机号")
	}
	e.mu.Lock()
	ready := len(e.session) > 0
	e.mu.Unlock()
	if ready {
		return "", errors.New("请先退出当前账号")
	}
	ctx, cancel := context.WithTimeout(e.ctx, 45*time.Second)
	defer cancel()
	if err := e.client.SendCode(ctx, phone); err != nil {
		return "", safeError(err)
	}
	if len(e.client.Session()) > 0 {
		return e.completeLogin()
	}
	e.setState("code", "")
	return "code", nil
}

func (e *Engine) SignIn(code string) (string, error) {
	if !e.op.TryLock() {
		return "", errors.New("请等待当前操作结束")
	}
	defer e.op.Unlock()
	ctx, cancel := context.WithTimeout(e.ctx, 45*time.Second)
	defer cancel()
	err := e.client.SignIn(ctx, strings.TrimSpace(code))
	if errors.Is(err, telegram.ErrPasswordNeeded) {
		e.setState("password", "")
		return "password", nil
	}
	if err != nil {
		return "", safeError(err)
	}
	return e.completeLogin()
}

func (e *Engine) SubmitPassword(password string) (string, error) {
	if !e.op.TryLock() {
		return "", errors.New("请等待当前操作结束")
	}
	defer e.op.Unlock()
	ctx, cancel := context.WithTimeout(e.ctx, 45*time.Second)
	defer cancel()
	if err := e.client.SubmitPassword(ctx, password); err != nil {
		return "", safeError(err)
	}
	return e.completeLogin()
}

func (e *Engine) completeLogin() (string, error) {
	sess := e.client.Session()
	if len(sess) == 0 {
		return "", errors.New("登录未生成会话，请重新发送验证码")
	}
	e.mu.Lock()
	e.session = sess
	e.mu.Unlock()
	e.beginConnection()
	return "connecting", nil
}

func (e *Engine) setState(state, message string) {
	e.mu.Lock()
	defer e.mu.Unlock()
	if !e.closed {
		e.state, e.message = state, message
	}
}

func (e *Engine) beginConnection() {
	e.mu.Lock()
	if e.closed {
		e.mu.Unlock()
		return
	}
	session := append([]byte(nil), e.session...)
	e.state = "connecting"
	e.wg.Add(1)
	e.mu.Unlock()
	go func() {
		defer e.wg.Done()
		delay := time.Second
		for e.ctx.Err() == nil {
			err := e.client.Connect(e.ctx, session, func(dcpool.Pool) { e.setState("ready", ""); delay = time.Second })
			if e.ctx.Err() != nil {
				return
			}
			if err != nil && (tgerr.Is(err, "AUTH_KEY_UNREGISTERED") || tgerr.Is(err, "SESSION_REVOKED") || strings.Contains(err.Error(), "session not authorized")) {
				e.setState("expired", "登录已失效，请退出后重新登录")
				return
			}
			e.resolved.Reset()
			e.setState("offline", "Telegram 暂时断开，正在重连")
			timer := time.NewTimer(delay)
			select {
			case <-e.ctx.Done():
				timer.Stop()
				return
			case <-timer.C:
			}
			delay = min(delay*2, 30*time.Second)
		}
	}()
}

// Scan 技术验证阶段只索引一个选定频道的最近视频，限额防止首次运行长时间全量扫描。
func (e *Engine) Scan(ref string, limit int64) (string, error) {
	if !e.op.TryLock() {
		return "", errors.New("请等待当前操作结束")
	}
	defer e.op.Unlock()
	if !e.client.Connected() {
		return "", errors.New("请先连接 Telegram")
	}
	limit = max(1, min(limit, 200))
	ctx, cancel := context.WithTimeout(e.ctx, 3*time.Minute)
	defer cancel()
	e.mu.Lock()
	e.scanning = true
	e.found = 0
	e.scanCancel = cancel
	e.mu.Unlock()
	defer func() { e.mu.Lock(); e.scanning = false; e.scanCancel = nil; e.mu.Unlock() }()
	id, title, err := e.client.ResolveChannel(ctx, strings.TrimSpace(ref))
	if err != nil {
		return "", safeError(err)
	}
	items, err := e.client.ScanChannelProgress(ctx, id, int(limit), func(n int) { e.mu.Lock(); e.found = n; e.mu.Unlock() })
	if err != nil {
		return "", safeError(err)
	}
	rows := make([]video, 0, len(items))
	for _, it := range items {
		rows = append(rows, video{Key: fmt.Sprintf("%d_%d", it.ChannelID, it.MessageID), ChannelID: it.ChannelID, MessageID: it.MessageID, Name: it.Name, Size: it.Size, MIME: it.MIME, Date: it.Date})
	}
	e.mu.Lock()
	e.items = map[string]video{}
	for _, v := range rows {
		e.items[v.Key] = v
	}
	e.found = len(rows)
	e.mu.Unlock()
	e.resolved.Reset()
	return marshal(map[string]any{"channel_id": id, "title": title, "ref": strings.TrimSpace(ref), "items": rows}), nil
}

func (e *Engine) CancelScan() {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.scanCancel != nil {
		e.scanCancel()
	}
}

// UpdateScanProgress long: Android 在每个 SQLite 页事务后报告进度，取消时 UI 显示的是已持久化条目数。
func (e *Engine) UpdateScanProgress(found int64, scanning bool) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.found = int(max(int64(0), min(found, int64(2147483647))))
	e.scanning = scanning
}

// RestoreIndex 只恢复受限的媒体标识；Telegram 访问权限仍由当前登录账号验证。
func (e *Engine) RestoreIndex(snapshot string) error {
	var rows []video
	if len(snapshot) > 2<<20 || json.Unmarshal([]byte(snapshot), &rows) != nil || len(rows) > 1000 {
		return errors.New("媒体索引无效")
	}
	index := make(map[string]video, len(rows))
	for _, v := range rows {
		if v.ChannelID <= 0 || v.MessageID <= 0 || v.Key != fmt.Sprintf("%d_%d", v.ChannelID, v.MessageID) || v.Size < 0 {
			return errors.New("媒体索引无效")
		}
		index[v.Key] = v
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	e.items = index
	return nil
}

// MountValidationMedia 仅供验证页和设备测试使用，测试素材不会加入 Telegram 索引。
func (e *Engine) MountValidationMedia(path string) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", errors.New("无法读取测试片")
	}
	defer f.Close()
	data, err := io.ReadAll(io.LimitReader(f, 32<<20+1))
	if err != nil || len(data) == 0 || len(data) > 32<<20 {
		return "", errors.New("测试片大小无效")
	}
	e.mu.Lock()
	e.fixture = data
	e.mu.Unlock()
	e.resolved.Invalidate("validation")
	return e.PlaybackURL("validation")
}

// Close 先撤销网络任务，再等待登录/扫描收尾，避免 Android 退出时留下后台连接。
func (e *Engine) Close() {
	e.mu.Lock()
	if e.closed {
		e.mu.Unlock()
		return
	}
	e.closed = true
	e.state = "closed"
	e.session = nil
	e.fixture = nil
	e.mu.Unlock()
	e.cancel()
	e.cache.Close()
	e.resolved.Close()
	_ = e.server.Close()
	e.op.Lock()
	e.client.Close()
	e.op.Unlock()
	e.wg.Wait()
}

// long: resolver 留在未导出的类型里，避免 context、ChunkSource 被 gomobile 当成 Java API 生成。
type resolver struct{ engine *Engine }

func (r resolver) Resolve(ctx context.Context, key string) (stream.ChunkSource, int64, string, error) {
	return r.engine.resolve(ctx, key)
}

func (e *Engine) resolve(ctx context.Context, key string) (stream.ChunkSource, int64, string, error) {
	e.mu.Lock()
	v, ok := e.items[key]
	fixture := e.fixture
	closed := e.closed
	e.mu.Unlock()
	if closed {
		return nil, 0, "", errors.New("engine closed")
	}
	if key == "validation" && len(fixture) > 0 {
		return memorySource{data: fixture}, int64(len(fixture)), "video/mp4", nil
	}
	if !ok {
		return nil, 0, "", stream.ErrNotFound
	}
	h, err := e.client.ChunkFile(ctx, v.ChannelID, v.MessageID)
	if err != nil {
		return nil, 0, "", err
	}
	mime := v.MIME
	if mime == "" {
		mime = "video/mp4"
	}
	return e.client.ChunkSource(h, 512<<10), h.Size, mime, nil
}

type memorySource struct{ data []byte }

func (s memorySource) Chunk(ctx context.Context, off int64, b []byte) (int64, error) {
	if err := ctx.Err(); err != nil {
		return 0, err
	}
	if off >= int64(len(s.data)) {
		return 0, io.EOF
	}
	n := copy(b, s.data[off:])
	return int64(n), nil
}

func marshal(v any) string { b, _ := json.Marshal(v); return string(b) }

func safeError(err error) error {
	if errors.Is(err, context.Canceled) {
		return errors.New("操作已取消")
	}
	if errors.Is(err, context.DeadlineExceeded) {
		return errors.New("请求超时，请检查 Telegram 网络连接后重试")
	}
	if wait, ok := tgerr.AsFloodWait(err); ok {
		return fmt.Errorf("Telegram 请求较频繁，请 %d 秒后重试", int(wait.Seconds())+1)
	}
	for _, p := range []struct{ code, message string }{{"PHONE_NUMBER_INVALID", "手机号格式不正确"}, {"API_ID_INVALID", "API ID 或 API Hash 无效"}, {"PHONE_CODE_INVALID", "验证码错误"}, {"PHONE_CODE_EXPIRED", "验证码已过期，请重新发送"}, {"PASSWORD_HASH_INVALID", "两步验证密码不正确"}, {"CHANNEL_PRIVATE", "当前账号无法访问该频道"}, {"USERNAME_INVALID", "频道用户名无效"}, {"USERNAME_NOT_OCCUPIED", "未找到该频道"}} {
		if tgerr.Is(err, p.code) {
			return errors.New(p.message)
		}
	}
	if errors.Is(err, telegram.ErrNoLogin) {
		return errors.New("登录已超时，请重新发送验证码")
	}
	if err.Error() == "验证码错误" || err.Error() == "验证码已过期，请重新发送" {
		return errors.New(err.Error())
	}
	return errors.New("Telegram 操作失败，请确认网络和频道权限后重试")
}
