package stream

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"strings"
)

// Source resolves a file key to its chunk source + size + content type.
type Source interface {
	Resolve(file string) (ChunkSource, int64, string, error)
}

// Handler serves files with full HTTP Range support on top of Cache.
func (c *Cache) Handler(src Source) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		file := strings.TrimPrefix(r.URL.Path, "/stream/")
		if file == "" {
			http.Error(w, "missing file", http.StatusBadRequest)
			return
		}

		cs, size, ctype, err := src.Resolve(file)
		if err != nil {
			http.Error(w, "not found: "+file, http.StatusNotFound)
			return
		}

		// swap source for this request
		sub := &fileCache{c: c, src: cs, file: file}

		start, end, partial, ok := parseRange(r.Header.Get("Range"), size)
		if !ok {
			w.Header().Set("Content-Range", fmt.Sprintf("bytes */%d", size))
			http.Error(w, "range not satisfiable", http.StatusRequestedRangeNotSatisfiable)
			return
		}

		w.Header().Set("Accept-Ranges", "bytes")
		w.Header().Set("Content-Type", ctype)
		// inline: let players/browsers play instead of download
		w.Header().Set("Content-Disposition", fmt.Sprintf("inline; filename=%q", fileNameOf(file)))
		w.Header().Set("X-Content-Type-Options", "nosniff")

		if partial {
			w.Header().Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", start, end, size))
		}
		w.Header().Set("Content-Length", strconv.FormatInt(end-start+1, 10))
		w.WriteHeader(map[bool]int{true: http.StatusPartialContent, false: http.StatusOK}[partial])

		if r.Method == http.MethodHead {
			return
		}

		buf := make([]byte, 256<<10)
		off := start
		for off <= end {
			n := len(buf)
			if remain := end - off + 1; int64(n) > remain {
				n = int(remain)
			}
			// don't read past logical EOF
			if limit := size - off; int64(n) > limit {
				n = int(limit)
			}
			if n <= 0 {
				break
			}
			nr, err := sub.ReadAt(r.Context(), off, buf[:n], size)
			if nr > 0 {
				if _, werr := w.Write(buf[:nr]); werr != nil {
					return
				}
				off += int64(nr)
			}
			if err != nil {
				if err == io.EOF {
					return
				}
				return
			}
			if nr == 0 {
				return
			}
		}
	})
}

type fileCache struct {
	c    *Cache
	src  ChunkSource
	file string
}

func (f *fileCache) ReadAt(ctx context.Context, off int64, dst []byte, total int64) (int, error) {
	return f.c.readAtWith(ctx, f.src, f.file, off, dst, total)
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
