package stream

import (
	"container/list"
	"context"
	"errors"
	"io"
	"sync"
	"sync/atomic"
	"time"
)

// long: 每个 Telegram DC 使用 8 条下载连接，预取最多占 7 条，给新的播放/拖动读取留出容量。
const maxPrefetchConcurrent = 7

type ChunkSource interface {
	Chunk(context.Context, int64, []byte) (int64, error)
}

var ErrNotFound = errors.New("stream not found")

type chunkKey struct {
	File   string
	Offset int64
}
type entry struct {
	key         chunkKey
	data        []byte
	n           int64
	ready       chan struct{}
	err         error
	cancel      context.CancelFunc
	waiters     int
	prefetchers int
	done        bool
	abandoned   bool
}

type Cache struct {
	mu                         sync.Mutex
	maxBytes, size, chunk      int64
	items                      map[chunkKey]*list.Element
	order                      *list.List
	src                        ChunkSource
	inflight                   map[chunkKey]*entry
	prefetchN                  int
	demandSlots, prefetchSlots chan struct{}
	ctx                        context.Context
	cancel                     context.CancelFunc
	fetchTimeout               time.Duration
	Hits, Misses, BytesRead    atomic.Int64
	FetchedBytes, FetchErrors  atomic.Int64
	lastFailure                string
}

func NewCache(src ChunkSource, chunkSize, maxBytes int64, prefetch int) *Cache {
	if chunkSize <= 0 {
		chunkSize = 512 << 10
	}
	if maxBytes < chunkSize {
		maxBytes = chunkSize
	}
	if prefetch < 0 {
		prefetch = 0
	}
	if prefetch > 16 {
		prefetch = 16
	}
	// long: 小缓存至少留下当前播放块的位置，避免预取窗口自己反复挤掉待播放的数据。
	prefetch = min(prefetch, int(maxBytes/chunkSize-1))
	ctx, cancel := context.WithCancel(context.Background())
	return &Cache{src: src, chunk: chunkSize, maxBytes: maxBytes, prefetchN: prefetch,
		items: map[chunkKey]*list.Element{}, inflight: map[chunkKey]*entry{}, order: list.New(),
		demandSlots: make(chan struct{}, 8), prefetchSlots: make(chan struct{}, min(prefetch, maxPrefetchConcurrent)), ctx: ctx, cancel: cancel, fetchTimeout: 30 * time.Second}
}
func (c *Cache) Close() { c.cancel() }

func (c *Cache) evictLocked() {
	for c.size > c.maxBytes && c.order.Len() > 0 {
		el := c.order.Back()
		e := el.Value.(*entry)
		delete(c.items, e.key)
		c.order.Remove(el)
		c.size -= int64(len(e.data))
	}
}

func (c *Cache) lookupLocked(key chunkKey) *entry {
	if el := c.items[key]; el != nil {
		c.order.MoveToFront(el)
		c.Hits.Add(1)
		return el.Value.(*entry)
	}
	return c.inflight[key]
}

func (c *Cache) startLocked(src ChunkSource, key chunkKey, expected int64, slots chan struct{}) *entry {
	ctx, cancel := context.WithTimeout(c.ctx, c.fetchTimeout)
	e := &entry{key: key, data: make([]byte, c.chunk), ready: make(chan struct{}), cancel: cancel}
	c.inflight[key] = e
	c.Misses.Add(1)
	go func() {
		defer cancel()
		n, err := src.Chunk(ctx, key.Offset, e.data)
		if n > 0 {
			c.FetchedBytes.Add(n)
		}
		// long: 只有完整逻辑块能进入缓存，网络中断或提前 EOF 不能污染后续重试。
		if n != expected && (err == nil || errors.Is(err, io.EOF)) {
			err = io.ErrUnexpectedEOF
		}
		if n == expected && errors.Is(err, io.EOF) {
			err = nil
		}
		if err != nil && !errors.Is(err, context.Canceled) {
			c.FetchErrors.Add(1)
		}
		c.mu.Lock()
		if err != nil && !errors.Is(err, context.Canceled) {
			// long: 诊断只保存分类，不能把 Telegram 的原始错误或媒体标识暴露到界面。
			switch {
			case errors.Is(err, context.DeadlineExceeded):
				c.lastFailure = "请求超时"
			case errors.Is(err, io.ErrUnexpectedEOF):
				c.lastFailure = "分块数据不完整"
			default:
				c.lastFailure = "下载失败，请检查连接或资源权限"
			}
		}
		e.n, e.err, e.done = n, err, true
		if c.inflight[key] == e {
			delete(c.inflight, key)
		}
		if err == nil && n > 0 && !e.abandoned {
			c.items[key] = c.order.PushFront(e)
			c.size += int64(len(e.data))
			c.evictLocked()
		}
		// long: 先归还下载名额再唤醒读取者，滑动窗口可立即补上后续块。
		<-slots
		close(e.ready)
		c.mu.Unlock()
	}()
	return e
}

func (c *Cache) wait(ctx context.Context, e *entry) (*entry, error) {
	defer func() {
		c.mu.Lock()
		e.waiters--
		c.cancelUnusedLocked(e)
		c.mu.Unlock()
	}()
	select {
	case <-ctx.Done():
		return nil, ctx.Err()
	case <-c.ctx.Done():
		return nil, c.ctx.Err()
	case <-e.ready:
		return e, e.err
	}
}

func (c *Cache) cancelUnusedLocked(e *entry) {
	// long: 同一分块可被多个播放窗口和正式读取共享，只有所有消费者离开后才中止。
	if e.waiters == 0 && e.prefetchers == 0 && !e.done {
		e.abandoned = true
		e.cancel()
		if c.inflight[e.key] == e {
			delete(c.inflight, e.key)
		}
	}
}

func (c *Cache) getWith(ctx context.Context, src ChunkSource, key chunkKey, expected, total int64, ahead *readAhead) (*entry, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	c.mu.Lock()
	if e := c.lookupLocked(key); e != nil {
		e.waiters++
		ahead.advanceLocked(ctx, key.Offset, total)
		c.mu.Unlock()
		return c.wait(ctx, e)
	}
	c.mu.Unlock()
	// long: 先获得下载名额再分配缓冲区，拖动和多客户端不会无限堆积分块内存。
	select {
	case c.demandSlots <- struct{}{}:
	case <-ctx.Done():
		return nil, ctx.Err()
	case <-c.ctx.Done():
		return nil, c.ctx.Err()
	}
	c.mu.Lock()
	if err := ctx.Err(); err != nil {
		<-c.demandSlots
		c.mu.Unlock()
		return nil, err
	}
	e := c.lookupLocked(key)
	if e != nil {
		<-c.demandSlots
	} else {
		e = c.startLocked(src, key, expected, c.demandSlots)
	}
	e.waiters++
	// long: 首块尚在 Telegram 下载时就并发拉取后续分块，抵消较长网络往返造成的播放停顿。
	ahead.advanceLocked(ctx, key.Offset, total)
	c.mu.Unlock()
	return c.wait(ctx, e)
}

func (c *Cache) ReadAt(ctx context.Context, file string, off int64, dst []byte, total int64) (int, error) {
	ahead := c.newReadAhead(c.src, file)
	defer ahead.Close()
	return c.readAtWith(ctx, c.src, file, off, dst, total, ahead)
}
func (c *Cache) readAtWith(ctx context.Context, src ChunkSource, file string, off int64, dst []byte, total int64, ahead *readAhead) (int, error) {
	if off < 0 || total < 0 {
		return 0, errors.New("invalid file offset or size")
	}
	if len(dst) == 0 {
		return 0, nil
	}
	if off >= total {
		return 0, io.EOF
	}
	copied := 0
	for copied < len(dst) && off < total {
		align := off / c.chunk * c.chunk
		e, err := c.getWith(ctx, src, chunkKey{file, align}, min(c.chunk, total-align), total, ahead)
		if err != nil {
			return copied, err
		}
		n := copy(dst[copied:], e.data[off-align:e.n])
		copied += n
		off += int64(n)
		c.BytesRead.Add(int64(n))
		c.mu.Lock()
		ahead.advanceLocked(ctx, align, total)
		c.mu.Unlock()
	}
	if copied < len(dst) {
		return copied, io.EOF
	}
	return copied, nil
}
func (c *Cache) Stat() (int64, int64, int64) {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.Hits.Load(), c.Misses.Load(), c.size
}
func (c *Cache) LastFailure() string { c.mu.Lock(); defer c.mu.Unlock(); return c.lastFailure }
func (c *Cache) WaitReady(key chunkKey) {
	c.mu.Lock()
	e := c.inflight[key]
	c.mu.Unlock()
	if e != nil {
		<-e.ready
	}
}
func (c *Cache) IdleWait(timeout time.Duration) bool {
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		c.mu.Lock()
		n := len(c.inflight)
		c.mu.Unlock()
		if n == 0 {
			return true
		}
		time.Sleep(5 * time.Millisecond)
	}
	return false
}
