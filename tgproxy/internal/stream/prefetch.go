package stream

import "context"

// long: 预取属于一次 HTTP 播放请求，窗口移动和请求结束只释放自己的引用，不干扰其他播放器。
// 所有字段由 Cache.mu 保护；窗口最多保留配置数量的分块引用。
type readAhead struct {
	c       *Cache
	src     ChunkSource
	file    string
	limit   int64
	entries map[int64]*entry
	closed  bool
}

func (c *Cache) newReadAhead(src ChunkSource, file string) *readAhead {
	return &readAhead{c: c, src: src, file: file, entries: make(map[int64]*entry)}
}

func (a *readAhead) Close() {
	a.c.mu.Lock()
	defer a.c.mu.Unlock()
	if a.closed {
		return
	}
	a.closed = true
	for off, e := range a.entries {
		e.prefetchers--
		a.c.cancelUnusedLocked(e)
		delete(a.entries, off)
	}
}

func (a *readAhead) advanceLocked(ctx context.Context, off, total int64) {
	c := a.c
	if a.closed || ctx.Err() != nil || c.ctx.Err() != nil {
		return
	}
	// long: 用剩余块数限制窗口，既不读过文件尾，也避免大文件偏移加法溢出。
	windowTotal := total
	if a.limit > 0 {
		windowTotal = min(total, a.limit)
	}
	count := min(int64(c.prefetchN), (windowTotal-1-off)/c.chunk)
	end := off + count*c.chunk
	for pos, e := range a.entries {
		if pos <= off || pos > end {
			e.prefetchers--
			c.cancelUnusedLocked(e)
			delete(a.entries, pos)
		}
	}
	for i := int64(1); i <= count; i++ {
		next := off + i*c.chunk
		if _, attempted := a.entries[next]; attempted {
			// long: 同一窗口失败的预取不循环重试，正式读取仍能重新发起一次共享下载。
			continue
		}
		key := chunkKey{a.file, next}
		if c.items[key] != nil {
			continue
		}
		e := c.inflight[key]
		if e == nil {
			select {
			case c.prefetchSlots <- struct{}{}:
			default:
				return
			}
			e = c.startLocked(a.src, key, min(c.chunk, total-next), c.prefetchSlots)
		}
		e.prefetchers++
		a.entries[next] = e
	}
}
