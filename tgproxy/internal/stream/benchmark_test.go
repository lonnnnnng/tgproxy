package stream

import (
	"context"
	"net/http/httptest"
	"testing"
	"time"
)

func BenchmarkColdRange(b *testing.B) {
	const size = 8 << 20
	src := chunkFunc(func(ctx context.Context, _ int64, buf []byte) (int64, error) {
		// long: 固定每块网络等待，比较冷缓存整段读取的调度效率，避免把热缓存速度当成优化收益。
		timer := time.NewTimer(40 * time.Millisecond)
		defer timer.Stop()
		select {
		case <-timer.C:
			return int64(len(buf)), nil
		case <-ctx.Done():
			return 0, ctx.Err()
		}
	})
	b.SetBytes(size)
	for i := 0; i < b.N; i++ {
		c := NewCache(src, 512<<10, 16<<20, 8)
		h := c.Handler(resolverFunc(func(context.Context, string) (ChunkSource, int64, string, error) {
			return src, size, "video/mp4", nil
		}))
		req := httptest.NewRequest("GET", "/stream/video", nil)
		req.Header.Set("Range", "bytes=0-8388607")
		res := httptest.NewRecorder()
		h.ServeHTTP(res, req)
		c.Close()
		if res.Code != 206 || res.Body.Len() != size {
			b.Fatalf("invalid cold read: status=%d bytes=%d", res.Code, res.Body.Len())
		}
	}
}
