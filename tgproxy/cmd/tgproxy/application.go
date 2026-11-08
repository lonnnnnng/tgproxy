package main

import (
	"context"
	"errors"
	"log"
	"sync"
	"tgproxy/internal/config"
	"tgproxy/internal/stream"
	"tgproxy/internal/telegram"
)

type application struct {
	mu        sync.Mutex
	cfg       *config.Config
	dataDir   string
	ctx       context.Context
	client    *telegram.Client
	runCancel context.CancelFunc
	resolved  *stream.CachedSource
}

func (a *application) currentTG() *telegram.Client { a.mu.Lock(); defer a.mu.Unlock(); return a.client }
func (a *application) connectLocked(sess []byte) {
	if a.runCancel != nil {
		a.runCancel()
	}
	if a.client == nil {
		return
	}
	a.client.Close()
	if a.resolved != nil {
		a.resolved.Reset()
	}
	ctx, cancel := context.WithCancel(a.ctx)
	a.runCancel = cancel
	// long: 连接绑定本次客户端，后续凭证切换不会让旧 goroutine 连到新客户端。
	client := a.client
	session := append([]byte(nil), sess...)
	go func() {
		if err := client.Connect(ctx, session, nil); err != nil && !errors.Is(err, context.Canceled) {
			log.Printf("telegram connection ended: %v", err)
		}
	}()
}
func (a *application) completeLogin(c *telegram.Client) error {
	sess := c.Session()
	if len(sess) == 0 {
		return errors.New("登录完成但未取得会话")
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.client != c {
		return errors.New("登录期间凭证已变化，请重试")
	}
	next := *a.cfg
	next.Session = sess
	if err := config.Save(a.dataDir, &next); err != nil {
		return err
	}
	a.cfg = &next
	a.connectLocked(sess)
	return nil
}
func (a *application) sendCode(ctx context.Context, phone string) error {
	c := a.currentTG()
	if c == nil {
		return errors.New("请先配置 Telegram 凭证")
	}
	if err := c.SendCode(ctx, phone); err != nil {
		return err
	}
	if len(c.Session()) > 0 {
		return a.completeLogin(c)
	}
	return nil
}
func (a *application) signIn(ctx context.Context, code string) error {
	c := a.currentTG()
	if c == nil {
		return errors.New("请先配置 Telegram 凭证")
	}
	if err := c.SignIn(ctx, code); err != nil {
		return err
	}
	return a.completeLogin(c)
}
func (a *application) submitPassword(ctx context.Context, password string) error {
	c := a.currentTG()
	if c == nil {
		return errors.New("请先配置 Telegram 凭证")
	}
	if err := c.SubmitPassword(ctx, password); err != nil {
		return err
	}
	return a.completeLogin(c)
}
func (a *application) saveCredentials(ctx context.Context, id int, hash string) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	if err := ctx.Err(); err != nil {
		return err
	}
	next := *a.cfg
	next.AppID, next.AppHash = id, hash
	if err := config.Save(a.dataDir, &next); err != nil {
		return err
	}
	if a.runCancel != nil {
		a.runCancel()
	}
	if a.client != nil {
		a.client.Close()
	}
	a.cfg = &next
	a.client = telegram.New(id, hash, a.dataDir)
	if len(next.Session) > 0 {
		a.connectLocked(next.Session)
	}
	return nil
}
func (a *application) close() {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.runCancel != nil {
		a.runCancel()
	}
	if a.client != nil {
		a.client.Close()
	}
}
