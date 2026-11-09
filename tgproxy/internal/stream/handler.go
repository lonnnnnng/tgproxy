package stream

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"strconv"
	"strings"
)

// Source resolves a file key to its chunk source + size + content type.
type Source interface {
	Resolve(ctx context.Context, file string) (ChunkSource, int64, string, error)
}

// Handler serves files with full HTTP Range support on top of Cache.
func (c *Cache) Handler(src Source) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet && r.Method != http.MethodHead {
			w.Header().Set("Allow", "GET, HEAD")
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		file := strings.TrimPrefix(strings.TrimPrefix(r.URL.Path, "/stream/"), "/")
		// long: 下载文件名只作展示，同一消息的不同 URL 后缀必须共享缓存。
		file, _, _ = strings.Cut(file, "/")
		if file == "" {
			http.Error(w, "missing file", 400)
			return
		}
		cs, size, ctype, err := src.Resolve(r.Context(), file)
		if err != nil {
			code := http.StatusBadGateway
			if errors.Is(err, ErrNotFound) {
				code = http.StatusNotFound
			}
			if errors.Is(err, context.DeadlineExceeded) {
				code = http.StatusGatewayTimeout
			}
			log.Printf("resolve stream %s: %v", file, err)
			http.Error(w, "media source unavailable", code)
			return
		}
		if size < 0 {
			http.Error(w, "invalid media size", 502)
			return
		}
		cacheKey := file
		if source, ok := cs.(interface{ CacheKey() string }); ok {
			cacheKey = file + ":" + source.CacheKey()
		}
		sub := &fileCache{c: c, src: cs, file: cacheKey, ahead: c.newReadAhead(cs, cacheKey)}
		defer sub.ahead.Close()
		rangeHeader := r.Header.Get("Range")
		if r.Method == http.MethodHead {
			rangeHeader = ""
		}
		start, end, partial, ok := parseRange(rangeHeader, size)
		if !ok {
			w.Header().Set("Content-Range", fmt.Sprintf("bytes */%d", size))
			http.Error(w, "range not satisfiable", http.StatusRequestedRangeNotSatisfiable)
			return
		}
		// long: 有限 Range 只预取本次请求覆盖的块，探测文件头和尾部时不浪费后续下载带宽。
		sub.ahead.limit = end + 1
		buf := make([]byte, 256<<10)
		off := start
		var first int
		// long: 首块失败时尚未提交成功响应，播放器可得到明确的 502/504 并重试。
		if r.Method != http.MethodHead && off <= end {
			// long: 随机拖动可能落在分块末尾，首包只等当前块，避免额外等待下一个 Telegram 请求。
			first, err = sub.ReadAt(r.Context(), off, buf[:min(int64(len(buf)), end-off+1, c.chunk-off%c.chunk)], size)
			if err != nil {
				code := http.StatusBadGateway
				if errors.Is(err, context.DeadlineExceeded) {
					code = http.StatusGatewayTimeout
				}
				if invalidator, ok := src.(interface{ Invalidate(string) }); ok {
					invalidator.Invalidate(file)
				}
				log.Printf("read stream %s at %d: %v", file, off, err)
				http.Error(w, "media download failed", code)
				return
			}
		}
		w.Header().Set("Accept-Ranges", "bytes")
		w.Header().Set("Content-Type", ctype)
		w.Header().Set("Content-Disposition", fmt.Sprintf("inline; filename=%q", fileNameOf(file)))
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Content-Length", strconv.FormatInt(end-start+1, 10))
		if partial {
			w.Header().Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", start, end, size))
			w.WriteHeader(206)
		} else {
			w.WriteHeader(200)
		}
		if r.Method == http.MethodHead {
			return
		}
		if first > 0 {
			if _, err = w.Write(buf[:first]); err != nil {
				return
			}
			off += int64(first)
			// long: 分块末尾的首包可能不足 HTTP 缓冲大小，立即发送才能真正缩短播放器的等待。
			if err := http.NewResponseController(w).Flush(); err != nil && !errors.Is(err, http.ErrNotSupported) {
				return
			}
		}
		for off <= end {
			nr, readErr := sub.ReadAt(r.Context(), off, buf[:min(int64(len(buf)), end-off+1)], size)
			if nr > 0 {
				if _, err = w.Write(buf[:nr]); err != nil {
					return
				}
				off += int64(nr)
			}
			if readErr != nil || nr == 0 {
				if readErr == nil {
					readErr = io.ErrUnexpectedEOF
				}
				if invalidator, ok := src.(interface{ Invalidate(string) }); ok {
					invalidator.Invalidate(file)
				}
				log.Printf("stream %s interrupted at %d/%d: %v", file, off, size, readErr)
				// long: 已发送响应头后不能改状态，主动中断连接使客户端识别截断响应。
				panic(http.ErrAbortHandler)
			}
		}
	})
}

type fileCache struct {
	c     *Cache
	src   ChunkSource
	file  string
	ahead *readAhead
}

func (f *fileCache) ReadAt(ctx context.Context, off int64, dst []byte, total int64) (int, error) {
	return f.c.readAtWith(ctx, f.src, f.file, off, dst, total, f.ahead)
}

func fileNameOf(file string) string {
	if i := strings.LastIndexByte(file, '/'); i >= 0 {
		return file[i+1:]
	}
	return file
}

// parseRange parses a single-range Range header. Returns start, end (inclusive),
// whether it was a Range request, and validity.
func parseRange(h string, size int64) (int64, int64, bool, bool) {
	if h == "" {
		return 0, size - 1, false, true
	}
	if size <= 0 {
		return 0, 0, false, false
	}
	const p = "bytes="
	if !strings.HasPrefix(h, p) {
		return 0, 0, false, false
	}
	spec := strings.TrimSpace(strings.TrimPrefix(h, p))
	if strings.Contains(spec, ",") {
		// multi-range unsupported: serve from first range only is invalid; reject
		return 0, 0, false, false
	}
	dash := strings.IndexByte(spec, '-')
	if dash < 0 {
		return 0, 0, false, false
	}
	startStr, endStr := spec[:dash], spec[dash+1:]

	var start, end int64
	if startStr == "" {
		// suffix range: last N bytes
		n, err := strconv.ParseInt(endStr, 10, 64)
		if err != nil || n <= 0 {
			return 0, 0, false, false
		}
		if n > size {
			n = size
		}
		start = size - n
		end = size - 1
	} else {
		s, err := strconv.ParseInt(startStr, 10, 64)
		if err != nil || s < 0 || s >= size {
			return 0, 0, false, false
		}
		start = s
		if endStr == "" {
			end = size - 1
		} else {
			e, err := strconv.ParseInt(endStr, 10, 64)
			if err != nil || e < start {
				return 0, 0, false, false
			}
			if e >= size {
				e = size - 1
			}
			end = e
		}
	}
	return start, end, true, true
}

// alias for tests
const httpStatusRequestedRangeNotSatisfiable = http.StatusRequestedRangeNotSatisfiable
