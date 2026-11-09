package telegram

import (
	"context"
	"crypto/sha256"
	"errors"
	"fmt"
	"path/filepath"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gotd/td/session"
	"github.com/gotd/td/telegram"
	"github.com/gotd/td/telegram/auth"
	"github.com/gotd/td/telegram/peers"
	"github.com/gotd/td/tg"
	"github.com/gotd/td/tgerr"

	"github.com/iyear/tdl/core/dcpool"
)

// Client wraps gotd client + DC pool for tgproxy operations.
type Client struct {
	dataDir  string
	dialogMu sync.Mutex
	appID    int
	appHash  string

	runtimeMu     sync.RWMutex
	connectMu     sync.Mutex
	connectCancel context.CancelFunc
	pool          dcpool.Pool
	peerMgr       *peers.Manager
	connected     atomic.Bool

	// Stateful login: one persistent client + pending auth state so that
	// "send code" and "sign in" reuse the same connection and phone_code_hash
	// (the code hash from a torn-down connection is rejected by Telegram).
	loginMu      sync.Mutex
	loginContext context.Context
	loginClient  *telegram.Client // live only during a login session
	loginCancel  context.CancelFunc
	loginMem     *session.StorageMemory
	loginPending *loginPending
	session      []byte // result of successful login (read by main via Session())
}

// loginPending holds the send-code state for the current login session.
type loginPending struct {
	phone         string
	phoneCodeHash string
}

func New(appID int, appHash string, dataDir ...string) *Client {
	c := &Client{appID: appID, appHash: appHash}
	if len(dataDir) > 0 {
		c.dataDir = dataDir[0]
	}
	return c
}

var (
	ErrCodeNeeded     = errors.New("code required")
	ErrPasswordNeeded = errors.New("password required")
	ErrNoLogin        = errors.New("no login in progress; send code first")
)

// SendCode starts a login session and sends the verification code.
func (c *Client) SendCode(ctx context.Context, phone string) error {
	c.loginMu.Lock()
	defer c.loginMu.Unlock()

	// If a login session is already pending for the same phone, keep it
	// (code already sent; the user should submit it or request a resend).
	if c.loginClient != nil && c.loginPending != nil && c.loginPending.phone == phone && c.loginContext.Err() == nil {
		// long: 用户明确再次点击发送时复用登录连接重发，保持 phone_code_hash 与新验证码一致。
		sent, err := c.loginClient.Auth().ResendCode(ctx, phone, c.loginPending.phoneCodeHash)
		if err != nil {
			return err
		}
		if code, ok := sent.(*tg.AuthSentCode); ok {
			c.loginPending.phoneCodeHash = code.PhoneCodeHash
		}
		return nil
	}
	c.resetLoginLocked()
	c.session = nil

	mem := &session.StorageMemory{}
	runCtx, cancel := context.WithTimeout(context.Background(), 10*time.Minute)
	c.loginContext = runCtx
	client := telegram.NewClient(c.appID, c.appHash, telegram.Options{
		SessionStorage: mem,
	})
	c.loginClient = client
	c.loginCancel = cancel
	c.loginMem = mem

	// long: 只有 Run 回调开始后才发送验证码，避免初始化期间并发调用底层客户端。
	ready := make(chan struct{})
	finished := make(chan error, 1)
	go func() {
		err := client.Run(runCtx, func(ctx context.Context) error { close(ready); <-ctx.Done(); return ctx.Err() })
		cancel()
		finished <- err
	}()
	waitCtx, waitCancel := context.WithTimeout(ctx, 15*time.Second)
	defer waitCancel()
	select {
	case <-ready:
	case err := <-finished:
		c.resetLoginLocked()
		return fmt.Errorf("登录连接失败: %w", err)
	case <-waitCtx.Done():
		c.resetLoginLocked()
		return waitCtx.Err()
	}
	sent, err := client.Auth().SendCode(ctx, phone, auth.SendCodeOptions{})
	if err != nil {
		c.resetLoginLocked()
		return err
	}

	switch s := sent.(type) {
	case *tg.AuthSentCode:
		c.loginPending = &loginPending{phone: phone, phoneCodeHash: s.PhoneCodeHash}
		return nil
	case *tg.AuthSentCodeSuccess:
		// session was already authorized somehow
		sess, err := mem.LoadSession(ctx)
		if err == nil {
			c.session = sess
		}
		c.resetLoginLocked()
		if err == nil {
			return nil
		}
		return errors.New("unexpected auth success")
	default:
		c.resetLoginLocked()
		return fmt.Errorf("unexpected sent code type %T", sent)
	}
}

// ResendCode re-sends the verification code on the pending login session.
func (c *Client) ResendCode(ctx context.Context, phone string) error {
	c.loginMu.Lock()
	defer c.loginMu.Unlock()

	if c.loginClient == nil || c.loginPending == nil {
		return ErrNoLogin
	}
	if c.loginPending.phone != phone {
		return errors.New("phone mismatch with pending login")
	}
	sent, err := c.loginClient.Auth().ResendCode(ctx, c.loginPending.phone, c.loginPending.phoneCodeHash)
	if err != nil {
		return err
	}
	if s, ok := sent.(*tg.AuthSentCode); ok {
		c.loginPending.phoneCodeHash = s.PhoneCodeHash
	}
	return nil
}

// SignIn submits the code received by the user.
func (c *Client) SignIn(ctx context.Context, code string) error {
	c.loginMu.Lock()
	defer c.loginMu.Unlock()

	if c.loginClient == nil || c.loginPending == nil {
		return ErrNoLogin
	}
	client, p := c.loginClient, c.loginPending

	_, err := client.Auth().SignIn(ctx, p.phone, code, p.phoneCodeHash)
	if err == nil {
		return c.finishLoginLocked(ctx)
	}
	if tgerr.Is(err, "SESSION_PASSWORD_NEEDED") {
		return ErrPasswordNeeded
	}
	if tgerr.Is(err, "PHONE_CODE_INVALID") {
		return errors.New("验证码错误")
	}
	if tgerr.Is(err, "PHONE_CODE_EXPIRED") {
		c.resetLoginLocked()
		return errors.New("验证码已过期，请重新发送")
	}
	return err
}

// SubmitPassword completes 2FA login.
func (c *Client) SubmitPassword(ctx context.Context, password string) error {
	c.loginMu.Lock()
	defer c.loginMu.Unlock()

	if c.loginClient == nil || c.loginPending == nil {
		return ErrNoLogin
	}
	if _, err := c.loginClient.Auth().Password(ctx, password); err != nil {
		return err
	}
	return c.finishLoginLocked(ctx)
}

// finishLoginLocked stores the session and tears the login client down.
func (c *Client) finishLoginLocked(ctx context.Context) error {
	sess, err := c.loginMem.LoadSession(ctx)
	if err != nil {
		return err
	}
	c.session = sess
	c.resetLoginLocked()
	return nil
}

func (c *Client) resetLoginLocked() {
	if c.loginCancel != nil {
		c.loginCancel()
		c.loginCancel = nil
	}
	c.loginClient = nil
	c.loginMem = nil
	c.loginPending = nil
}

// Session returns the session bytes from a completed login, if any.
func (c *Client) Session() []byte {
	c.loginMu.Lock()
	defer c.loginMu.Unlock()
	return append([]byte(nil), c.session...)
}

// Login performs one-shot login: empty code+password means "send code".
// The web layer uses SendCode/SignIn/SubmitPassword directly; this wrapper
// keeps the old three-step semantics on the stateful flow.
func (c *Client) Login(ctx context.Context, phone, code, password string) ([]byte, error) {
	switch {
	case code == "" && password == "":
		if err := c.SendCode(ctx, phone); err != nil {
			return nil, err
		}
		return nil, ErrCodeNeeded
	case code != "":
		if err := c.SignIn(ctx, code); err != nil {
			return nil, err
		}
	case password != "":
		if err := c.SubmitPassword(ctx, password); err != nil {
			return nil, err
		}
	}
	sess := c.Session()
	if len(sess) == 0 {
		return nil, ErrCodeNeeded
	}
	return sess, nil
}

// Connect runs the long-lived client with the given session and builds the DC pool.
func (c *Client) Connect(ctx context.Context, sessionData []byte, onReady func(dcpool.Pool)) error {
	c.connectMu.Lock()
	defer c.connectMu.Unlock()
	runCtx, cancel := context.WithCancel(ctx)
	defer cancel()
	c.runtimeMu.Lock()
	c.connectCancel = cancel
	c.runtimeMu.Unlock()
	defer func() {
		c.runtimeMu.Lock()
		c.connected.Store(false)
		c.pool = nil
		c.peerMgr = nil
		c.connectCancel = nil
		c.runtimeMu.Unlock()
	}()
	mem := session.StorageMemory{}
	if err := mem.StoreSession(runCtx, sessionData); err != nil {
		return err
	}
	td := telegram.NewClient(c.appID, c.appHash, telegram.Options{SessionStorage: &mem})
	return td.Run(runCtx, func(ctx context.Context) error {
		status, err := td.Auth().Status(ctx)
		if err != nil {
			return err
		}
		if !status.Authorized {
			return errors.New("session not authorized; login first")
		}
		// long: 配合最多 7 个并发预取保留需求下载容量，高延迟时不再只靠两块轮流等待。
		pool := &safePool{inner: dcpool.NewPool(td, 8, telegram.MiddlewareFunc(floodMiddleware))}
		defer pool.Close()
		peerPath := ""
		if c.dataDir != "" {
			fingerprint := sha256.Sum256(sessionData)
			peerPath = filepath.Join(c.dataDir, fmt.Sprintf("peers-%x.json", fingerprint[:8]))
		}
		storage, err := openPeerStorage(peerPath)
		if err != nil {
			return fmt.Errorf("读取频道访问缓存失败: %w", err)
		}
		mgr := peers.Options{Storage: storage}.Build(pool.Default(ctx))
		// long: 一次发布整套连接资源，扫描和拉流不会看到只初始化了一半的状态。
		c.runtimeMu.Lock()
		c.pool = pool
		c.peerMgr = mgr
		c.connected.Store(true)
		c.runtimeMu.Unlock()
		if onReady != nil {
			onReady(pool)
		}
		<-ctx.Done()
		return ctx.Err()
	})
}

func (c *Client) Close() {
	c.runtimeMu.Lock()
	if c.connectCancel != nil {
		c.connectCancel()
	}
	c.connected.Store(false)
	c.runtimeMu.Unlock()
	c.loginMu.Lock()
	c.resetLoginLocked()
	c.loginMu.Unlock()
}
func (c *Client) runtime() (dcpool.Pool, *peers.Manager, error) {
	c.runtimeMu.RLock()
	defer c.runtimeMu.RUnlock()
	if c.pool == nil || c.peerMgr == nil || !c.connected.Load() {
		return nil, nil, errors.New("telegram not connected")
	}
	return c.pool, c.peerMgr, nil
}

// Connected reports whether the long-lived client is ready.
func (c *Client) Connected() bool { return c.connected.Load() }

// Manager returns the peers manager (nil until connected).
func (c *Client) Manager() *peers.Manager {
	c.runtimeMu.RLock()
	defer c.runtimeMu.RUnlock()
	return c.peerMgr
}
