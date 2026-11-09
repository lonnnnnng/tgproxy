package stream

import (
	"bytes"
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func awaitCondition(t *testing.T, check func() bool) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for !check() {
		if time.Now().After(deadline) {
			t.Fatal("condition was not reached")
		}
		time.Sleep(time.Millisecond)
	}
}

func TestPrefetchStartsBeforeFirstBlockAndStopsWithRequest(t *testing.T) {
	started := make(chan int64, 32)
	src := chunkFunc(func(ctx context.Context, off int64, _ []byte) (int64, error) {
		started <- off
		<-ctx.Done()
		return 0, ctx.Err()
	})
	c := NewCache(src, 4096, 1<<20, 8)
	defer c.Close()
	h := c.Handler(resolverFunc(func(context.Context, string) (ChunkSource, int64, string, error) {
		return src, 1 << 20, "video/mp4", nil
	}))
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	r := httptest.NewRequest("GET", "/stream/video", nil).WithContext(ctx)
	done := make(chan struct{})
	go func() { h.ServeHTTP(httptest.NewRecorder(), r); close(done) }()
	seen := map[int64]bool{}
	for len(seen) < 8 {
		select {
		case off := <-started:
			seen[off] = true
		case <-time.After(2 * time.Second):
			t.Fatal("first block blocked the prefetch pipeline")
		}
	}
	// long: 第一块仍未完成时，需求块和有界预取已经重叠；退出请求后不能继续拉旧位置。
	for off := range seen {
		if off < 0 || off >= 8*4096 {
			t.Fatalf("unexpected speculative offset %d", off)
		}
	}
	cancel()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("handler ignored cancellation")
	}
	awaitCondition(t, func() bool { return len(c.demandSlots) == 0 && len(c.prefetchSlots) == 0 })
}

func TestSharedPrefetchSurvivesOtherReadersClosing(t *testing.T) {
	started, release, stopped := make(chan struct{}), make(chan struct{}), make(chan struct{})
	var calls atomic.Int32
	var startedOnce, stoppedOnce sync.Once
	src := chunkFunc(func(ctx context.Context, off int64, b []byte) (int64, error) {
		if off == 4096 {
			calls.Add(1)
			startedOnce.Do(func() { close(started) })
			select {
			case <-release:
			case <-ctx.Done():
				stoppedOnce.Do(func() { close(stopped) })
				return 0, ctx.Err()
			}
		}
		return int64(len(b)), nil
	})
	c := NewCache(src, 4096, 32768, 1)
	defer c.Close()
	a, b := c.newReadAhead(src, "video"), c.newReadAhead(src, "video")
	defer a.Close()
	defer b.Close()
	for _, ahead := range []*readAhead{a, b} {
		if _, err := c.readAtWith(context.Background(), src, "video", 0, make([]byte, 16), 8192, ahead); err != nil {
			t.Fatal(err)
		}
	}
	<-started
	a.Close()
	c.mu.Lock()
	e := c.inflight[chunkKey{"video", 4096}]
	owners := e.prefetchers
	c.mu.Unlock()
	if owners != 1 {
		t.Fatalf("remaining prefetch owners = %d", owners)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	done := make(chan error, 1)
	go func() {
		readerAhead := c.newReadAhead(src, "video")
		defer readerAhead.Close()
		_, err := c.readAtWith(ctx, src, "video", 4096, make([]byte, 16), 8192, readerAhead)
		done <- err
	}()
	awaitCondition(t, func() bool {
		c.mu.Lock()
		defer c.mu.Unlock()
		return e.waiters == 1
	})
	b.Close()
	select {
	case <-stopped:
		t.Fatal("closing a prefetch window canceled an active reader")
	default:
	}
	close(release)
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	if calls.Load() != 1 {
		t.Fatalf("shared chunk fetched %d times", calls.Load())
	}
}

func TestPrefetchFailureRetriesOnlyOnDemand(t *testing.T) {
	var calls atomic.Int32
	src := chunkFunc(func(_ context.Context, off int64, b []byte) (int64, error) {
		if off == 4096 && calls.Add(1) == 1 {
			return 0, errors.New("temporary failure")
		}
		return int64(len(b)), nil
	})
	c := NewCache(src, 4096, 32768, 1)
	defer c.Close()
	ahead := c.newReadAhead(src, "video")
	defer ahead.Close()
	read := func(off int64) {
		t.Helper()
		if _, err := c.readAtWith(context.Background(), src, "video", off, make([]byte, 16), 8192, ahead); err != nil {
			t.Fatal(err)
		}
	}
	read(0)
	awaitCondition(t, func() bool { return len(c.prefetchSlots) == 0 })
	for i := 0; i < 4; i++ {
		read(0)
	}
	if calls.Load() != 1 {
		t.Fatal("failed prefetch retried without demand")
	}
	read(4096)
	if calls.Load() != 2 {
		t.Fatal("demand did not retry failed prefetch")
	}
}

func TestCanceledDownloadLateSuccessDoesNotOverwriteRetry(t *testing.T) {
	var calls atomic.Int32
	started, release := make(chan struct{}), make(chan struct{})
	src := chunkFunc(func(ctx context.Context, _ int64, b []byte) (int64, error) {
		call := calls.Add(1)
		if call == 1 {
			close(started)
			<-release
		}
		for i := range b {
			b[i] = byte(call)
		}
		return int64(len(b)), nil
	})
	c := NewCache(src, 4096, 8192, 0)
	defer c.Close()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() { _, err := c.ReadAt(ctx, "video", 0, make([]byte, 16), 4096); done <- err }()
	<-started
	cancel()
	if err := <-done; !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
	buf := make([]byte, 16)
	if _, err := c.ReadAt(context.Background(), "video", 0, buf, 4096); err != nil {
		t.Fatal(err)
	}
	close(release)
	awaitCondition(t, func() bool { return len(c.demandSlots) == 0 })
	if _, err := c.ReadAt(context.Background(), "video", 0, buf, 4096); err != nil || !bytes.Equal(buf, bytes.Repeat([]byte{2}, 16)) {
		t.Fatalf("late abandoned result overwrote retry: %v %v", buf, err)
	}
}

func TestSmallCacheLimitsPrefetchWindow(t *testing.T) {
	for _, capacity := range []int64{1, 2} {
		var calls atomic.Int32
		src := chunkFunc(func(_ context.Context, _ int64, b []byte) (int64, error) {
			calls.Add(1)
			return int64(len(b)), nil
		})
		c := NewCache(src, 4096, capacity*4096, 8)
		ahead := c.newReadAhead(src, "video")
		_, err := c.readAtWith(context.Background(), src, "video", 0, make([]byte, 16), 1<<20, ahead)
		if err != nil || !c.IdleWait(time.Second) {
			t.Fatal("small cache read did not finish", err)
		}
		if int64(calls.Load()) > capacity {
			t.Fatalf("cache of %d blocks downloaded %d", capacity, calls.Load())
		}
		ahead.Close()
		c.Close()
	}
}

func TestBoundedRangeDoesNotPrefetchBeyondItsEnd(t *testing.T) {
	var calls atomic.Int32
	src := chunkFunc(func(_ context.Context, _ int64, b []byte) (int64, error) {
		calls.Add(1)
		return int64(len(b)), nil
	})
	c := NewCache(src, 4096, 1<<20, 8)
	defer c.Close()
	h := c.Handler(resolverFunc(func(context.Context, string) (ChunkSource, int64, string, error) {
		return src, 1 << 20, "video/mp4", nil
	}))
	r := httptest.NewRequest("GET", "/stream/video", nil)
	r.Header.Set("Range", "bytes=1024-2047")
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	awaitCondition(t, func() bool { return len(c.demandSlots) == 0 && len(c.prefetchSlots) == 0 })
	if w.Code != 206 || w.Body.Len() != 1024 || calls.Load() != 1 {
		t.Fatalf("bounded range status=%d body=%d downloads=%d", w.Code, w.Body.Len(), calls.Load())
	}
}

func TestUnalignedSeekSendsFirstBytesBeforeNextBlock(t *testing.T) {
	gate := make(chan struct{})
	var once sync.Once
	release := func() { once.Do(func() { close(gate) }) }
	defer release()
	src := chunkFunc(func(ctx context.Context, off int64, b []byte) (int64, error) {
		if off > 0 {
			select {
			case <-gate:
			case <-ctx.Done():
				return 0, ctx.Err()
			}
		}
		return int64(len(b)), nil
	})
	c := NewCache(src, 4096, 16384, 1)
	defer c.Close()
	srv := httptest.NewServer(c.Handler(resolverFunc(func(context.Context, string) (ChunkSource, int64, string, error) {
		return src, 8192, "video/mp4", nil
	})))
	defer srv.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	req, _ := http.NewRequestWithContext(ctx, "GET", srv.URL+"/stream/video", nil)
	req.Header.Set("Range", "bytes=4064-8191")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal("first response waited for the next block", err)
	}
	defer resp.Body.Close()
	first := make([]byte, 32)
	if _, err := io.ReadFull(resp.Body, first); err != nil {
		t.Fatal(err)
	}
	release()
	rest, err := io.ReadAll(resp.Body)
	if err != nil || resp.StatusCode != 206 || len(rest) != 4096 {
		t.Fatalf("range response: status=%d rest=%d err=%v", resp.StatusCode, len(rest), err)
	}
}
