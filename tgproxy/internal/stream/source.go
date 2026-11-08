package stream

import (
	"context"
	"sync"
	"time"
)

type resolved struct {
	src             ChunkSource
	size            int64
	mime            string
	err             error
	expires         time.Time
	ready           chan struct{}
	cancel          context.CancelFunc
	waiters         int
	done, abandoned bool
}

// long: CachedSource 合并同一视频的句柄解析，各等待者独立取消，不会连带中断其他播放器。
type CachedSource struct {
	mu      sync.Mutex
	source  Source
	ttl     time.Duration
	limit   int
	entries map[string]*resolved
	slots   chan struct{}
	ctx     context.Context
	cancel  context.CancelFunc
}

func NewCachedSource(src Source, ttl time.Duration, limit int) *CachedSource {
	if limit < 8 {
		limit = 8
	}
	ctx, cancel := context.WithCancel(context.Background())
	return &CachedSource{source: src, ttl: ttl, limit: limit, entries: map[string]*resolved{}, slots: make(chan struct{}, 8), ctx: ctx, cancel: cancel}
}
func (c *CachedSource) Close() { c.cancel(); c.Reset() }
func (c *CachedSource) Reset() {
	c.mu.Lock()
	defer c.mu.Unlock()
	for _, e := range c.entries {
		if !e.done {
			e.abandoned = true
			e.cancel()
		}
	}
	c.entries = map[string]*resolved{}
}
func (c *CachedSource) lookup(file string) *resolved {
	e := c.entries[file]
	if e != nil && e.done && time.Now().After(e.expires) {
		delete(c.entries, file)
		return nil
	}
	return e
}
func (c *CachedSource) wait(ctx context.Context, file string, e *resolved) (ChunkSource, int64, string, error) {
	defer func() {
		c.mu.Lock()
		e.waiters--
		if e.waiters == 0 && !e.done {
			e.abandoned = true
			e.cancel()
			if c.entries[file] == e {
				delete(c.entries, file)
			}
		}
		c.mu.Unlock()
	}()
	select {
	case <-ctx.Done():
		return nil, 0, "", ctx.Err()
	case <-c.ctx.Done():
		return nil, 0, "", c.ctx.Err()
	case <-e.ready:
		return e.src, e.size, e.mime, e.err
	}
}
func (c *CachedSource) Resolve(ctx context.Context, file string) (ChunkSource, int64, string, error) {
	if err := ctx.Err(); err != nil {
		return nil, 0, "", err
	}
	c.mu.Lock()
	e := c.lookup(file)
	if e != nil {
		e.waiters++
		c.mu.Unlock()
		return c.wait(ctx, file, e)
	}
	c.mu.Unlock()
	select {
	case c.slots <- struct{}{}:
	case <-ctx.Done():
		return nil, 0, "", ctx.Err()
	case <-c.ctx.Done():
		return nil, 0, "", c.ctx.Err()
	}
	c.mu.Lock()
	if e = c.lookup(file); e != nil {
		e.waiters++
		c.mu.Unlock()
		<-c.slots
		return c.wait(ctx, file, e)
	}
	if len(c.entries) >= c.limit {
		var oldest string
		var when time.Time
		for key, v := range c.entries {
			if v.done && (oldest == "" || v.expires.Before(when)) {
				oldest, when = key, v.expires
			}
		}
		if oldest != "" {
			delete(c.entries, oldest)
		}
	}
	shared, cancel := context.WithTimeout(c.ctx, 30*time.Second)
	e = &resolved{ready: make(chan struct{}), cancel: cancel, waiters: 1}
	c.entries[file] = e
	c.mu.Unlock()
	go func() {
		defer func() { cancel(); <-c.slots }()
		src, size, mime, err := c.source.Resolve(shared, file)
		c.mu.Lock()
		e.src, e.size, e.mime, e.err = src, size, mime, err
		e.expires = time.Now().Add(c.ttl)
		e.done = true
		if (err != nil || e.abandoned) && c.entries[file] == e {
			delete(c.entries, file)
		}
		close(e.ready)
		c.mu.Unlock()
	}()
	return c.wait(ctx, file, e)
}

func (c *CachedSource) Invalidate(file string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if e := c.entries[file]; e != nil {
		if !e.done {
			e.abandoned = true
			e.cancel()
		}
		delete(c.entries, file)
	}
}
