package telegram

import (
	"context"
	"errors"
	"fmt"
	"strings"
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
	appID   int
	appHash string

	td        *telegram.Client
	pool      dcpool.Pool
	peerMgr   *peers.Manager
	connected atomic.Bool

	// Stateful login: one persistent client + pending auth state so that
	// "send code" and "sign in" reuse the same connection and phone_code_hash
	// (the code hash from a torn-down connection is rejected by Telegram).
	loginMu      sync.Mutex
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

func New(appID int, appHash string) *Client {
	return &Client{appID: appID, appHash: appHash}
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
	if c.loginClient != nil && c.loginPending != nil && c.loginPending.phone == phone {
		return nil
	}
	c.resetLoginLocked()

	mem := &session.StorageMemory{}
	runCtx, cancel := context.WithCancel(context.Background())
	client := telegram.NewClient(c.appID, c.appHash, telegram.Options{
		SessionStorage: mem,
	})
	c.loginClient = client
	c.loginCancel = cancel
	c.loginMem = mem

	// Keep the connection alive until resetLoginLocked cancels it.
	go func() { _ = client.Run(runCtx, func(ctx context.Context) error { <-runCtx.Done(); return runCtx.Err() }) }()

	// Wait for the client to connect, then send the code on the live connection.
	deadline := time.Now().Add(15 * time.Second)
	var sent tg.AuthSentCodeClass
	for {
		s, err := client.Auth().SendCode(ctx, phone, auth.SendCodeOptions{})
		if err == nil {
			sent = s
			break
		}
		// "client is not running" until the underlying conn is up
		if strings.Contains(err.Error(), "client is not running") && time.Now().Before(deadline) {
			time.Sleep(100 * time.Millisecond)
			continue
		}
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
func (c *Client) Session() []byte { return c.session }

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
	if c.session == nil {
		return nil, ErrCodeNeeded
	}
	return c.session, nil
}

// Connect runs the long-lived client with the given session and builds the DC pool.
func (c *Client) Connect(ctx context.Context, sessionData []byte, onReady func(dcpool.Pool)) error {
	mem := session.StorageMemory{}
	if len(sessionData) > 0 {
		if err := mem.StoreSession(ctx, sessionData); err != nil {
			return err
		}
	}
	c.td = telegram.NewClient(c.appID, c.appHash, telegram.Options{
		SessionStorage: &mem,
	})

	return c.td.Run(ctx, func(ctx context.Context) error {
		status, err := c.td.Auth().Status(ctx)
		if err != nil {
			return err
		}
		if !status.Authorized {
			return errors.New("session not authorized; login first")
		}

		pool := dcpool.NewPool(c.td, 4)
		c.pool = pool
		c.peerMgr = peers.Options{Storage: newPeerMemStorage()}.Build(pool.Default(ctx))
		c.connected.Store(true)
		if onReady != nil {
			onReady(pool)
		}

		<-ctx.Done()
		return ctx.Err()
	})
}

// Connected reports whether the long-lived client is ready.
func (c *Client) Connected() bool { return c.connected.Load() }

// Manager returns the peers manager (nil until connected).
func (c *Client) Manager() *peers.Manager { return c.peerMgr }
