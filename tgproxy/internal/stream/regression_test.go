package stream

import (
	"context"
	"errors"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"
)

type chunkFunc func(context.Context, int64, []byte) (int64, error)

func (f chunkFunc) Chunk(ctx context.Context, off int64, b []byte) (int64, error) {
	return f(ctx, off, b)
}

type resolverFunc func(context.Context, string) (ChunkSource, int64, string, error)

func (f resolverFunc) Resolve(ctx context.Context, key string) (ChunkSource, int64, string, error) {
	return f(ctx, key)
}

func TestTransientPartialFailureCanRetry(t *testing.T) {
	var calls atomic.Int32
	src := chunkFunc(func(_ context.Context, _ int64, b []byte) (int64, error) {
		if calls.Add(1) == 1 {
			return 7, errors.New("temporary network failure")
		}
		return int64(len(b)), nil
	})
	c := NewCache(src, 4096, 8192, 0)
	defer c.Close()
	if _, err := c.ReadAt(context.Background(), "video", 0, make([]byte, 16), 4096); err == nil {
		t.Fatal("expected failure")
	}
	if n, err := c.ReadAt(context.Background(), "video", 0, make([]byte, 16), 4096); err != nil || n != 16 {
		t.Fatalf("retry %d %v", n, err)
	}
	if calls.Load() != 2 {
		t.Fatal("partial failure was cached")
	}
}

func TestCanceledReaderReleasesDownload(t *testing.T) {
	started, stopped := make(chan struct{}), make(chan struct{})
	src := chunkFunc(func(ctx context.Context, _ int64, _ []byte) (int64, error) {
		close(started)
		<-ctx.Done()
		close(stopped)
		return 0, ctx.Err()
	})
	c := NewCache(src, 4096, 8192, 0)
	defer c.Close()
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { _, err := c.ReadAt(ctx, "video", 0, make([]byte, 16), 4096); done <- err }()
	<-started
	cancel()
	select {
	case err := <-done:
		if !errors.Is(err, context.Canceled) {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("reader ignored cancellation")
	}
	select {
	case <-stopped:
	case <-time.After(time.Second):
		t.Fatal("unneeded download was not canceled")
	}
}

func TestDownloadTimeout(t *testing.T) {
	c := NewCache(chunkFunc(func(ctx context.Context, _ int64, _ []byte) (int64, error) { <-ctx.Done(); return 0, ctx.Err() }), 4096, 8192, 0)
	defer c.Close()
	c.fetchTimeout = 20 * time.Millisecond
	if _, err := c.ReadAt(context.Background(), "video", 0, make([]byte, 16), 4096); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatal(err)
	}
}

func TestHandlerFailureAndEmptyRanges(t *testing.T) {
	c := NewCache(nil, 4096, 8192, 0)
	defer c.Close()
	for _, tt := range []struct {
		method, rg string
		size       int64
		want       int
	}{{"GET", "", 4096, 502}, {"POST", "", 4096, 405}, {"GET", "bytes=-1", 0, 416}, {"GET", "", 0, 200}} {
		h := c.Handler(resolverFunc(func(context.Context, string) (ChunkSource, int64, string, error) {
			return chunkFunc(func(context.Context, int64, []byte) (int64, error) { return 0, errors.New("upstream failed") }), tt.size, "video/mp4", nil
		}))
		r := httptest.NewRequest(tt.method, "/stream/a", nil)
		r.Header.Set("Range", tt.rg)
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		if w.Code != tt.want {
			t.Fatalf("%+v status=%d", tt, w.Code)
		}
	}
}

func TestSourceCacheDeduplicatesAndRetries(t *testing.T) {
	var calls atomic.Int32
	s := NewCachedSource(resolverFunc(func(context.Context, string) (ChunkSource, int64, string, error) {
		if calls.Add(1) == 1 {
			return nil, 0, "", errors.New("temporary")
		}
		return nil, 4096, "video/mp4", nil
	}), time.Minute, 2)
	for i := 0; i < 3; i++ {
		_, _, _, _ = s.Resolve(context.Background(), "a")
	}
	if calls.Load() != 2 {
		t.Fatalf("resolve calls=%d", calls.Load())
	}
	s.Reset()
	_, _, _, _ = s.Resolve(context.Background(), "a")
	if calls.Load() != 3 {
		t.Fatal("reset retained old session source")
	}
}

func TestCanceledDownloadDoesNotCaptureImmediateRetry(t *testing.T) {
	var calls atomic.Int32
	started, release := make(chan struct{}), make(chan struct{})
	c := NewCache(chunkFunc(func(ctx context.Context, _ int64, b []byte) (int64, error) {
		if calls.Add(1) == 1 {
			close(started)
			<-ctx.Done()
			<-release
			return 0, ctx.Err()
		}
		return int64(len(b)), nil
	}), 4096, 8192, 0)
	defer c.Close()
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { _, _ = c.ReadAt(ctx, "video", 0, make([]byte, 16), 4096); close(done) }()
	<-started
	cancel()
	<-done
	n, err := c.ReadAt(context.Background(), "video", 0, make([]byte, 16), 4096)
	close(release)
	if err != nil || n != 16 || calls.Load() != 2 {
		t.Fatalf("immediate retry n=%d err=%v calls=%d", n, err, calls.Load())
	}
}

func TestSharedResolveSurvivesFirstWaiterCancellation(t *testing.T) {
	started, release := make(chan struct{}), make(chan struct{})
	var calls atomic.Int32
	s := NewCachedSource(resolverFunc(func(ctx context.Context, _ string) (ChunkSource, int64, string, error) {
		calls.Add(1)
		close(started)
		select {
		case <-release:
			return nil, 4096, "video/mp4", nil
		case <-ctx.Done():
			return nil, 0, "", ctx.Err()
		}
	}), time.Minute, 8)
	defer s.Close()
	ctx, cancel := context.WithCancel(context.Background())
	first, second := make(chan error, 1), make(chan error, 1)
	go func() { _, _, _, err := s.Resolve(ctx, "a"); first <- err }()
	<-started
	go func() { _, _, _, err := s.Resolve(context.Background(), "a"); second <- err }()
	deadline := time.Now().Add(time.Second)
	for {
		s.mu.Lock()
		n := s.entries["a"].waiters
		s.mu.Unlock()
		if n == 2 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("second waiter not attached")
		}
		time.Sleep(time.Millisecond)
	}
	cancel()
	if err := <-first; !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
	close(release)
	if err := <-second; err != nil {
		t.Fatalf("other player canceled: %v", err)
	}
	if calls.Load() != 1 {
		t.Fatal("shared resolve duplicated")
	}
}
