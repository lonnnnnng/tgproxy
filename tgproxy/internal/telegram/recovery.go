package telegram

import (
	"bytes"
	"context"
	"errors"
	"github.com/gotd/td/crypto"
	"github.com/gotd/td/session"
	"github.com/gotd/td/telegram/auth"
	"github.com/gotd/td/tgerr"
)

// long: 先离线校验密钥结构，损坏会话不能进入永不成功的网络重试。
func ValidateSession(raw []byte) error {
	mem := &session.StorageMemory{}
	if err := mem.StoreSession(context.Background(), raw); err != nil {
		return err
	}
	data, err := (&session.Loader{Storage: mem}).Load(context.Background())
	if err != nil {
		return errors.New("保存的会话损坏，请重新登录")
	}
	var key crypto.Key
	id := key.ID()
	if len(data.AuthKey) != len(key) || len(data.AuthKeyID) != len(id) {
		return errors.New("保存的会话密钥不完整，请重新登录")
	}
	copy(key[:], data.AuthKey)
	id = key.ID()
	if key.Zero() || !bytes.Equal(id[:], data.AuthKeyID) {
		return errors.New("保存的会话密钥无效，请重新登录")
	}
	return nil
}

func passwordNeeded(err error) bool {
	return errors.Is(err, auth.ErrPasswordAuthNeeded) || tgerr.Is(err, "SESSION_PASSWORD_NEEDED")
}
func (c *Client) loginActiveLocked() bool {
	if c.loginClient == nil || c.loginPending == nil || c.loginContext == nil || c.loginContext.Err() != nil {
		c.resetLoginLocked()
		return false
	}
	return true
}
func (c *Client) LoginPending() bool {
	if !c.loginMu.TryLock() {
		return true
	}
	defer c.loginMu.Unlock()
	return c.loginActiveLocked()
}
func (c *Client) CancelLogin()     { c.loginMu.Lock(); defer c.loginMu.Unlock(); c.resetLoginLocked() }
func (c *Client) AccountID() int64 { return c.accountID.Load() }
func (c *Client) Diagnostics() (int64, int64, int64) {
	return c.reconnects.Load(), c.migrations.Load(), c.referenceRefreshes.Load()
}
func (c *Client) acquireDialogs(ctx context.Context) error {
	c.dialogOnce.Do(func() { c.dialogGate = make(chan struct{}, 1) })
	select {
	case c.dialogGate <- struct{}{}:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}
