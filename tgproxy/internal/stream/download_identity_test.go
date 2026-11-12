package stream

import (
	"context"
	"net/http/httptest"
	"testing"
)

type identifiedSource struct {
	chunkFunc
	identity string
}

func (s identifiedSource) CacheKey() string { return s.identity }

func TestDownloadResumeRejectsSameSizeReplacementBeforeReading(t *testing.T) {
	cache := NewCache(nil, 4096, 8192, 0)
	defer cache.Close()
	reads := 0
	source := identifiedSource{chunkFunc(func(_ context.Context, _ int64, b []byte) (int64, error) {
		reads++
		return int64(len(b)), nil
	}), "document:22:4096"}
	handler := cache.Handler(resolverFunc(func(context.Context, string) (ChunkSource, int64, string, error) {
		return source, 4096, "video/mp4", nil
	}))
	request := httptest.NewRequest("GET", "/stream/video", nil)
	request.Header.Set("Range", "bytes=100-200")
	request.Header.Set("If-Match", `"document:11:4096"`)
	recorder := httptest.NewRecorder()
	handler.ServeHTTP(recorder, request)
	if recorder.Code != 412 || reads != 0 {
		t.Fatalf("replacement response=%d reads=%d", recorder.Code, reads)
	}
	request.Header.Set("If-Match", `"document:22:4096"`)
	recorder = httptest.NewRecorder()
	handler.ServeHTTP(recorder, request)
	if recorder.Code != 206 || recorder.Header().Get("ETag") != `"document:22:4096"` || recorder.Body.Len() != 101 {
		t.Fatalf("valid resume response=%d len=%d", recorder.Code, recorder.Body.Len())
	}
}
