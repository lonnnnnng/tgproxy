package telegram

import (
	"context"
	"errors"
	"github.com/gotd/td/bin"
	"github.com/gotd/td/tg"
	"github.com/iyear/tdl/core/dcpool"
	"sync"
)

// long: tdl 的关闭操作不锁内部 map，必须与创建 DC 连接串行，避免退出时并发遍历和写入。
type safePool struct {
	mu     sync.Mutex
	inner  dcpool.Pool
	closed bool
}
type closedInvoker struct{}

func (closedInvoker) Invoke(context.Context, bin.Encoder, bin.Decoder) error {
	return errors.New("Telegram 连接池已关闭")
}
func (p *safePool) Client(ctx context.Context, dc int) *tg.Client {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.closed {
		return tg.NewClient(closedInvoker{})
	}
	return p.inner.Client(ctx, dc)
}
func (p *safePool) Default(ctx context.Context) *tg.Client {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.closed {
		return tg.NewClient(closedInvoker{})
	}
	return p.inner.Default(ctx)
}
func (p *safePool) Takeout(ctx context.Context, dc int) *tg.Client {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.closed {
		return tg.NewClient(closedInvoker{})
	}
	return p.inner.Takeout(ctx, dc)
}
func (p *safePool) Close() error {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.closed {
		return nil
	}
	p.closed = true
	return p.inner.Close()
}
