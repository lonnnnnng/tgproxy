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
	speculative bool
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
	ctx, cancel := context.WithCancel(context.Background())
	return &Cache{src: src, chunk: chunkSize, maxBytes: maxBytes, prefetchN: prefetch,
		items: map[chunkKey]*list.Element{}, inflight: map[chunkKey]*entry{}, order: list.New(),
		demandSlots: make(chan struct{}, 8), prefetchSlots: make(chan struct{}, 2), ctx: ctx, cancel: cancel, fetchTimeout: 30 * time.Second}
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

func (c *Cache) startLocked(src ChunkSource, key chunkKey, expected int64, speculative bool, slots chan struct{}) *entry {
	ctx, cancel := context.WithTimeout(c.ctx, c.fetchTimeout)
	e := &entry{key: key, data: make([]byte, c.chunk), ready: make(chan struct{}), cancel: cancel, speculative: speculative}
	c.inflight[key] = e
	c.Misses.Add(1)
	go func() {
		defer func() { cancel(); <-slots }()
		n, err := src.Chunk(ctx, key.Offset, e.data)
		// long: 只有完整逻辑块能进入缓存，网络中断或提前 EOF 不能污染后续重试。
		if n != expected && (err == nil || errors.Is(err, io.EOF)) {
			err = io.ErrUnexpectedEOF
		}
		if n == expected && errors.Is(err, io.EOF) {
			err = nil
		}
		c.mu.Lock()
		e.n, e.err, e.done = n, err, true
		if c.inflight[key] == e {
			delete(c.inflight, key)
		}
		if err == nil && n > 0 && !e.abandoned {
			c.items[key] = c.order.PushFront(e)
			c.size += int64(len(e.data))
			c.evictLocked()
		}
		close(e.ready)
		c.mu.Unlock()
	}()
	return e
}

func (c *Cache) wait(ctx context.Context, e *entry) (*entry, error) {
	defer func() {
		c.mu.Lock()
		e.waiters--
		// long: 同块仍有其他播放器等待时保留共享下载，最后一个等待者离开才取消。
		if e.waiters == 0 && !e.speculative && !e.done {
			e.abandoned = true
			e.cancel()
			if c.inflight[e.key] == e {
				delete(c.inflight, e.key)
			}
		}
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

func (c *Cache) getWith(ctx context.Context, src ChunkSource, key chunkKey, expected int64) (*entry, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	c.mu.Lock()
	if e := c.lookupLocked(key); e != nil {
		e.waiters++
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
	e := c.lookupLocked(key)
	if e != nil {
		<-c.demandSlots
	} else {
		e = c.startLocked(src, key, expected, false, c.demandSlots)
	}
	e.waiters++
	c.mu.Unlock()
	return c.wait(ctx, e)
}

func (c *Cache) ReadAt(ctx context.Context, file string, off int64, dst []byte, total int64) (int, error) {
	return c.readAtWith(ctx, c.src, file, off, dst, total)
}
func (c *Cache) readAtWith(ctx context.Context, src ChunkSource, file string, off int64, dst []byte, total int64) (int, error) {
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
		e, err := c.getWith(ctx, src, chunkKey{file, align}, min(c.chunk, total-align))
		if err != nil {
			return copied, err
		}
		n := copy(dst[copied:], e.data[off-align:e.n])
		copied += n
		off += int64(n)
		c.BytesRead.Add(int64(n))
		// long: 优先满足正在播放/拖动的块，预取只使用独立的两个低优先级名额。
		c.schedulePrefetch(src, file, align, total)
	}
	if copied < len(dst) {
		return copied, io.EOF
	}
	return copied, nil
}
func (c *Cache) schedulePrefetch(src ChunkSource, file string, off, total int64) {
	for i := 1; i <= c.prefetchN; i++ {
		next := off + int64(i)*c.chunk
		if next >= total {
			return
		}
		select {
		case <-c.ctx.Done():
			return
		default:
		}
		select {
		case c.prefetchSlots <- struct{}{}:
		default:
			return
		}
		key := chunkKey{file, next}
		c.mu.Lock()
		if c.lookupLocked(key) != nil {
			<-c.prefetchSlots
		} else {
			c.startLocked(src, key, min(c.chunk, total-next), true, c.prefetchSlots)
		}
		c.mu.Unlock()
	}
}
func (c *Cache) Stat() (int64, int64, int64) {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.Hits.Load(), c.Misses.Load(), c.size
}
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
