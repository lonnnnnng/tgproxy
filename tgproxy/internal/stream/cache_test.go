package stream

import (
	"bytes"
	"context"
	"crypto/rand"
	"errors"
	"io"
	"net/http/httptest"
	"sync"
	"testing"
	"time"
)

// fakeSource serves a deterministic 10MB "file" of pseudo-random bytes,
// counting network fetches with configurable latency.
type fakeSource struct {
	mu      sync.Mutex
	data    map[int64][]byte // chunk aligned data (only first chunk is random; rest derived)
	fetches map[int64]int
	latency time.Duration
	size    int64
	chunk   int64
}

func newFakeSource(size, chunk int64, latency time.Duration) *fakeSource {
	return &fakeSource{
		data:    make(map[int64][]byte),
		fetches: make(map[int64]int),
		latency: latency,
		size:    size,
		chunk:   chunk,
	}
}

func (f *fakeSource) fill(off int64) []byte {
	f.mu.Lock()
	defer f.mu.Unlock()
	if b, ok := f.data[off]; ok {
		return b
	}
	b := make([]byte, f.chunk)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	f.data[off] = b
	return b
}

func (f *fakeSource) Chunk(_ context.Context, off int64, b []byte) (int64, error) {
	f.mu.Lock()
	f.fetches[off]++
	f.mu.Unlock()
	time.Sleep(f.latency)
	src := f.fill(off)
	n := int64(len(src))
	if remain := f.size - off; n > remain {
		n = remain
	}
	copy(b, src[:n])
	if off+n >= f.size {
		return n, io.EOF
	}
	return n, nil
}

func (f *fakeSource) fetchCount() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	n := 0
	for _, c := range f.fetches {
		n += c
	}
	return n
}

type fakeResolver struct {
	src   *fakeSource
	size  int64
	ctype string
}

func (r *fakeResolver) Resolve(_ context.Context, file string) (ChunkSource, int64, string, error) {
	if file != "test.mp4" {
		return nil, 0, "", ErrNotFound
	}
	return r.src, r.size, r.ctype, nil
}

func newTestCache(src ChunkSource, chunk, maxBytes int64, prefetch int) *Cache {
	return NewCache(src, chunk, maxBytes, prefetch)
}

func TestHandlerFullBody(t *testing.T) {
	size := int64(5 << 20)
	chunk := int64(1 << 20)
	src := newFakeSource(size, chunk, 0)
	cache := newTestCache(src, chunk, 8<<20, 0)
	h := cache.Handler(&fakeResolver{src: src, size: size, ctype: "video/mp4"})

	req := httptest.NewRequest("GET", "/stream/test.mp4", nil)
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)

	if rec.Code != 200 {
		t.Fatalf("code = %d, want 200", rec.Code)
	}
	if got := rec.Body.Len(); got != int(size) {
		t.Fatalf("body len = %d, want %d", got, size)
	}
	if rec.Header().Get("Accept-Ranges") != "bytes" {
		t.Fatal("missing Accept-Ranges")
	}
	if rec.Header().Get("Content-Length") != "5242880" {
		t.Fatalf("Content-Length = %q", rec.Header().Get("Content-Length"))
	}
}

func TestHandlerRange(t *testing.T) {
	size := int64(5 << 20)
	chunk := int64(1 << 20)
	src := newFakeSource(size, chunk, 0)
	cache := newTestCache(src, chunk, 8<<20, 0)
	h := cache.Handler(&fakeResolver{src: src, size: size, ctype: "video/mp4"})

	req := httptest.NewRequest("GET", "/stream/test.mp4", nil)
	req.Header.Set("Range", "bytes=1000-2999")
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)

	if rec.Code != 206 {
		t.Fatalf("code = %d, want 206", rec.Code)
	}
	if got := rec.Body.Len(); got != 2000 {
		t.Fatalf("body len = %d, want 2000", got)
	}
	if cr := rec.Header().Get("Content-Range"); cr != "bytes 1000-2999/5242880" {
		t.Fatalf("Content-Range = %q", cr)
	}
}

func TestHandlerSuffixRange(t *testing.T) {
	size := int64(5 << 20)
	chunk := int64(1 << 20)
	src := newFakeSource(size, chunk, 0)
	cache := newTestCache(src, chunk, 8<<20, 0)
	h := cache.Handler(&fakeResolver{src: src, size: size, ctype: "video/mp4"})

	req := httptest.NewRequest("GET", "/stream/test.mp4", nil)
	req.Header.Set("Range", "bytes=-500")
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)

	if rec.Code != 206 {
		t.Fatalf("code = %d", rec.Code)
	}
	if got := rec.Body.Len(); got != 500 {
		t.Fatalf("body = %d, want 500", got)
	}
	if cr := rec.Header().Get("Content-Range"); cr != "bytes 5242380-5242879/5242880" {
		t.Fatalf("Content-Range = %q", cr)
	}
}

func TestHandlerHead(t *testing.T) {
	size := int64(5 << 20)
	chunk := int64(1 << 20)
	src := newFakeSource(size, chunk, 0)
	cache := newTestCache(src, chunk, 8<<20, 0)
	h := cache.Handler(&fakeResolver{src: src, size: size, ctype: "video/mp4"})

	req := httptest.NewRequest("HEAD", "/stream/test.mp4", nil)
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)

	if rec.Code != 200 {
		t.Fatalf("code = %d", rec.Code)
	}
	if rec.Body.Len() != 0 {
		t.Fatal("HEAD should have no body")
	}
	if rec.Header().Get("Content-Length") != "5242880" {
		t.Fatalf("CL = %q", rec.Header().Get("Content-Length"))
	}
}

func TestHandlerRangeBeyondEOF(t *testing.T) {
	size := int64(5 << 20)
	chunk := int64(1 << 20)
	src := newFakeSource(size, chunk, 0)
	cache := newTestCache(src, chunk, 8<<20, 0)
	h := cache.Handler(&fakeResolver{src: src, size: size, ctype: "video/mp4"})

	req := httptest.NewRequest("GET", "/stream/test.mp4", nil)
	req.Header.Set("Range", "bytes=5242880-")
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if rec.Code != httpStatusRequestedRangeNotSatisfiable {
		t.Fatalf("code = %d, want 416", rec.Code)
	}
}

// Handler responses must match direct chunk source reads byte-for-byte.
func TestByteIdentity(t *testing.T) {
	size := int64(3<<20 + 12345)
	chunk := int64(512 << 10)
	src := newFakeSource(size, chunk, 0)
	cache := newTestCache(src, chunk, 8<<20, 0)
	h := cache.Handler(&fakeResolver{src: src, size: size, ctype: "video/mp4"})

	// fetch via handler at arbitrary offsets
	for _, rg := range []string{"bytes=0-", "bytes=777777-888888", "bytes=-1000", "bytes=0-999"} {
		req := httptest.NewRequest("GET", "/stream/test.mp4", nil)
		req.Header.Set("Range", rg)
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, req)

		start, end := parseRangeResp(rg, size)
		want := make([]byte, end-start+1)
		off := start
		for copied := int64(0); copied < int64(len(want)); {
			n := int64(len(want) - int(copied))
			if n > chunk {
				n = chunk
			}
			// read via direct source at aligned offset
			align := (off / chunk) * chunk
			buf := make([]byte, chunk)
			nr, _ := src.Chunk(context.Background(), align, buf)
			s := off - align
			e := s + n
			if e > nr {
				e = nr
			}
			cop := copy(want[copied:], buf[s:e])
			copied += int64(cop)
			off += int64(cop)
		}
		if !bytes.Equal(rec.Body.Bytes(), want) {
			t.Fatalf("bytes mismatch for range %s", rg)
		}
	}
}

func parseRangeResp(rg string, size int64) (int64, int64) {
	start, end, _, ok := parseRange(rg, size)
	if !ok {
		panic("bad range")
	}
	_ = start
	return start, end
}

func TestCacheDedupConcurrent(t *testing.T) {
	// 10 concurrent readers of same chunk with 50ms latency: source must be hit ~1x per chunk
	size := int64(4 << 20)
	chunk := int64(1 << 20)
	src := newFakeSource(size, chunk, 50*time.Millisecond)
	cache := newTestCache(src, chunk, 8<<20, 0)

	var wg sync.WaitGroup
	for i := 0; i < 10; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			buf := make([]byte, chunk)
			if _, err := cache.ReadAt(context.Background(), "f.mp4", 0, buf, size); err != nil && !errors.Is(err, io.EOF) {
				t.Error(err)
			}
		}()
	}
	wg.Wait()
	if n := src.fetchCount(); n > 2 {
		t.Fatalf("source fetched %d times for same chunk, want <=2 (dedup failed)", n)
	}
}

func TestPrefetchReducesLatency(t *testing.T) {
	// sequential read with prefetch: after warmup, subsequent chunks should be cached already
	size := int64(8 << 20)
	chunk := int64(1 << 20)
	lat := 80 * time.Millisecond

	// without prefetch
	src1 := newFakeSource(size, chunk, lat)
	c1 := newTestCache(src1, chunk, 32<<20, 0)
	start := time.Now()
	buf := make([]byte, chunk)
	for off := int64(0); off < 4<<20; off += chunk {
		c1.ReadAt(context.Background(), "f.mp4", off, buf, size)
	}
	noPre := time.Since(start)

	// with prefetch
	src2 := newFakeSource(size, chunk, lat)
	c2 := newTestCache(src2, chunk, 32<<20, 4)
	start = time.Now()
	for off := int64(0); off < 4<<20; off += chunk {
		c2.ReadAt(context.Background(), "f.mp4", off, buf, size)
	}
	withPre := time.Since(start)

	t.Logf("no prefetch: %v, with prefetch: %v", noPre, withPre)
	if withPre >= noPre {
		t.Fatalf("prefetch did not help: %v >= %v", withPre, noPre)
	}
}

func TestSeekDoesNotFetchWholeFile(t *testing.T) {
	// seek to 90% of a 100MB file with small cache: must fetch only nearby chunks
	size := int64(100 << 20)
	chunk := int64(1 << 20)
	src := newFakeSource(size, chunk, 0)
	cache := newTestCache(src, chunk, 8<<20, 0)

	buf := make([]byte, 64<<10)
	off := int64(90) << 20
	if _, err := cache.ReadAt(context.Background(), "f.mp4", off, buf, size); err != nil && !errors.Is(err, io.EOF) {
		t.Fatal(err)
	}
	// must only fetch chunks covering [90MB, 90MB+64K) plus prefetch window
	if n := len(src.fetches); n > 12 {
		t.Fatalf("fetched %d chunks for a single seek, want <= 12", n)
	}
}
