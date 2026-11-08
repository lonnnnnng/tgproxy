package stream

import (
	"container/list"
	"context"
	"errors"
	"io"
	"math"
	"sync"
	"sync/atomic"
	"time"
)

// ChunkSource fetches one aligned chunk of data. offset is chunk-aligned.
type ChunkSource interface {
	// Chunk fills b (b.len == chunk size) with data starting at offset.
	// Returns bytes read (may be less at EOF).
	Chunk(ctx context.Context, offset int64, b []byte) (int64, error)
}

var ErrNotFound = errors.New("stream not found")

// chunkKey identifies one chunk of one file.
type chunkKey struct {
	File   string
	Offset int64
}

// entry is a cached chunk.
type entry struct {
	key   chunkKey
	data  []byte
	n     int64 // valid bytes
	ready chan struct{}
	err   error
}

// Cache is an LRU chunk cache with single-flight fetch and sequential prefetch.
type Cache struct {
	mu       sync.Mutex
	maxBytes int64
	size     int64
	items    map[chunkKey]*list.Element
	order    *list.List // front = hot
	src      ChunkSource
	chunk    int64

	inflight map[chunkKey]*entry

	// prefetch tracking: last requested chunk offset per file
	lastAccess map[string]int64
	prefetchN  int

	// stats
	Hits      atomic.Int64
	Misses    atomic.Int64
	BytesRead atomic.Int64
}

func NewCache(src ChunkSource, chunkSize int64, maxBytes int64, prefetch int) *Cache {
	if prefetch < 0 {
		prefetch = 0
	}
	return &Cache{
		maxBytes:   maxBytes,
		items:      make(map[chunkKey]*list.Element),
		order:      list.New(),
		src:        src,
		chunk:      chunkSize,
		inflight:   make(map[chunkKey]*entry),
		lastAccess: make(map[string]int64),
		prefetchN:  prefetch,
	}
}

func (c *Cache) evictLocked() {
	for c.size > c.maxBytes && c.order.Len() > 0 {
		e := c.order.Back()
		if e == nil {
			return
		}
		ce := e.Value.(*entry)
		c.order.Remove(e)
		delete(c.items, ce.key)
		c.size -= int64(len(ce.data))
	}
}

// get returns the chunk entry, fetching via single-flight if absent.
func (c *Cache) get(ctx context.Context, key chunkKey, fetch bool) (*entry, error) {
	return c.getWith(ctx, c.src, key, fetch)
}

// getWith fetches using the given source.
func (c *Cache) getWith(ctx context.Context, src ChunkSource, key chunkKey, fetch bool) (*entry, error) {
	c.mu.Lock()
	if el, ok := c.items[key]; ok {
		c.order.MoveToFront(el)
		c.mu.Unlock()
		c.Hits.Add(1)
		e := el.Value.(*entry)
		<-e.ready
		return e, e.err
	}

	if !fetch {
		c.mu.Unlock()
		return nil, ErrNotFound
	}

	if e, ok := c.inflight[key]; ok {
		c.mu.Unlock()
		c.Misses.Add(1)
		<-e.ready
		return e, e.err
	}

	e := &entry{key: key, data: make([]byte, c.chunk), ready: make(chan struct{})}
	c.inflight[key] = e
	c.mu.Unlock()
	c.Misses.Add(1)

	go func() {
		n, err := src.Chunk(context.Background(), key.Offset, e.data)
		e.n = n
		e.err = err
		close(e.ready)

		c.mu.Lock()
		delete(c.inflight, key)
		// store result even on error? store only on success or EOF-with-data
		if n > 0 {
			c.items[key] = c.order.PushFront(e)
			c.size += int64(len(e.data))
			c.evictLocked()
		}
		c.mu.Unlock()
	}()

	<-e.ready
	return e, e.err
}

// ReadAt copies min(len(dst), remaining) bytes of file starting at off.
// total is the logical file size. Sequential prefetch is scheduled on forward seeks.
func (c *Cache) ReadAt(ctx context.Context, file string, off int64, dst []byte, total int64) (int, error) {
	return c.readAtWith(ctx, c.src, file, off, dst, total)
}

// readAtWith uses a per-request chunk source (registered under the same file key).
func (c *Cache) readAtWith(ctx context.Context, src ChunkSource, file string, off int64, dst []byte, total int64) (int, error) {
	if off >= total {
		return 0, io.EOF
	}
	c.schedulePrefetch(src, file, off, total)

	var copied int
	for copied < len(dst) {
		if off >= total {
			break
		}
		align := (off / c.chunk) * c.chunk
		e, err := c.getWith(ctx, src, chunkKey{File: file, Offset: align}, true)
		if err != nil && !errors.Is(err, io.EOF) {
			return copied, err
		}
		<-e.ready
		if e.err != nil && e.n == 0 {
			if copied > 0 {
				return copied, nil
			}
			return 0, e.err
		}

		// bytes within chunk valid for the logical file
		start := off - align
		if start >= e.n {
			// beyond EOF of actual source
			if copied > 0 {
				return copied, io.EOF
			}
			return 0, io.EOF
		}
		end := e.n
		if limit := total - align; end > limit {
			end = limit
		}
		n := copy(dst[copied:], e.data[start:end])
		c.BytesRead.Add(int64(n))
		copied += n
		off += int64(n)
	}
	return copied, nil
}

// schedulePrefetch kicks off background fetches of the next N chunks after the
// accessed one, only when access is roughly sequential (or first access).
func (c *Cache) schedulePrefetch(src ChunkSource, file string, off int64, total int64) {
	if c.prefetchN <= 0 {
		return
	}
	alignment := (off / c.chunk) * c.chunk

	c.mu.Lock()
	last, seen := c.lastAccess[file]
	c.lastAccess[file] = alignment
	// sequential if this access is within one chunk of last alignment, or first access
	sequential := !seen || math.Abs(float64(alignment-last)) <= float64(c.chunk*2)
	c.mu.Unlock()

	if !sequential {
		return
	}

	for i := 1; i <= c.prefetchN; i++ {
		po := alignment + int64(i)*c.chunk
		if po >= total {
			break
		}
		key := chunkKey{File: file, Offset: po}
		c.mu.Lock()
		_, cached := c.items[key]
		_, flying := c.inflight[key]
		if !cached && !flying {
			// mark inflight manually to avoid dup goroutines
			e := &entry{key: key, data: make([]byte, c.chunk), ready: make(chan struct{})}
			c.inflight[key] = e
			c.mu.Unlock()
			go func() {
				n, err := src.Chunk(context.Background(), key.Offset, e.data)
				e.n = n
				e.err = err
				close(e.ready)
				c.mu.Lock()
				delete(c.inflight, key)
				if n > 0 {
					c.items[key] = c.order.PushFront(e)
					c.size += int64(len(e.data))
					c.evictLocked()
				}
				c.mu.Unlock()
			}()
			continue
		}
		c.mu.Unlock()
	}
}

// Warm requests a chunk without blocking caller (used to pre-trigger fetch).
func (c *Cache) Stat() (hits, misses int64, bytesCached int64) {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.Hits.Load(), c.Misses.Load(), c.size
}

// WaitReady blocks until inflight for a key completes (test helper).
func (c *Cache) WaitReady(key chunkKey) {
	c.mu.Lock()
	e, ok := c.inflight[key]
	c.mu.Unlock()
	if ok {
		<-e.ready
	}
}

// IdleWait waits until no inflight fetches remain (test helper).
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
