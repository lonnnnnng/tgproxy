package telegram

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"net/http/httptest"
	"testing"

	"github.com/gotd/td/bin"
	"github.com/gotd/td/tg"
	"github.com/gotd/td/tgerr"
	"strings"

	"tgproxy/internal/stream"
)

type tailInvoker struct{ size int64 }

func (f tailInvoker) Invoke(_ context.Context, input bin.Encoder, output bin.Decoder) error {
	r := input.(*tg.UploadGetFileRequest)
	// long: 模拟 Telegram 的请求对齐约束，避免普通内存文件掩盖尾块被截断的问题。
	if r.Limit <= 0 || r.Limit > 1<<20 || r.Limit%4096 != 0 || r.Offset%4096 != 0 || r.Offset/(1<<20) != (r.Offset+int64(r.Limit)-1)/(1<<20) {
		return fmt.Errorf("invalid download range: offset=%d limit=%d", r.Offset, r.Limit)
	}
	n := min(int64(r.Limit), f.size-r.Offset)
	b := make([]byte, n)
	for i := range b {
		b[i] = byte((r.Offset + int64(i)) % 251)
	}
	output.(*tg.UploadFileBox).File = &tg.UploadFile{Bytes: b}
	return nil
}

type tailPool struct{ api *tg.Client }

func (p tailPool) Client(context.Context, int) *tg.Client  { return p.api }
func (p tailPool) Takeout(context.Context, int) *tg.Client { return p.api }
func (p tailPool) Default(context.Context) *tg.Client      { return p.api }
func (p tailPool) Close() error                            { return nil }

type tailResolver struct {
	src  stream.ChunkSource
	size int64
}

func (r tailResolver) Resolve(context.Context, string) (stream.ChunkSource, int64, string, error) {
	return r.src, r.size, "video/mp4", nil
}

func TestTelegramTailRange(t *testing.T) {
	// long: MP4 索引经常在文件末尾；同时覆盖对齐边界和线上两个探测失败的真实文件大小。
	for _, size := range []int64{1, 4095, 4096, 4097, 512 << 10, 512<<10 + 1, 267546752, 392415986} {
		t.Run(fmt.Sprint(size), func(t *testing.T) {
			const chunk = 512 << 10
			client := &Client{pool: tailPool{api: tg.NewClient(tailInvoker{size: size})}}
			src := client.ChunkSource(&FileHandle{Size: size, Loc: &tg.InputDocumentFileLocation{}}, chunk)
			cache := stream.NewCache(src, chunk, 2*chunk, 0)
			handler := cache.Handler(tailResolver{src: src, size: size})
			req := httptest.NewRequest("GET", "/stream/video", nil)
			req.Header.Set("Range", "bytes=-1024")
			rec := httptest.NewRecorder()
			handler.ServeHTTP(rec, req)
			n := min(size, 1024)
			want := make([]byte, n)
			for i := range want {
				want[i] = byte((size - n + int64(i)) % 251)
			}
			if rec.Code != 206 || !bytes.Equal(rec.Body.Bytes(), want) {
				t.Fatalf("tail status=%d bytes=%d, want 206 and %d intact bytes", rec.Code, rec.Body.Len(), n)
			}
			if got := rec.Header().Get("Content-Range"); got != fmt.Sprintf("bytes %d-%d/%d", size-n, size-1, size) {
				t.Fatalf("unexpected Content-Range: %s", got)
			}
		})
	}
}

type invokeFunc func(context.Context, bin.Encoder, bin.Decoder) error

func (f invokeFunc) Invoke(ctx context.Context, in bin.Encoder, out bin.Decoder) error {
	return f(ctx, in, out)
}

type routingPool struct{ clients map[int]*tg.Client }

func (p routingPool) Client(_ context.Context, dc int) *tg.Client    { return p.clients[dc] }
func (p routingPool) Takeout(ctx context.Context, dc int) *tg.Client { return p.Client(ctx, dc) }
func (p routingPool) Default(ctx context.Context) *tg.Client         { return p.Client(ctx, 1) }
func (p routingPool) Close() error                                   { return nil }

func TestFileMigrationUsesTargetDC(t *testing.T) {
	calls := 0
	old := tg.NewClient(invokeFunc(func(context.Context, bin.Encoder, bin.Decoder) error {
		calls++
		return tgerr.New(303, "FILE_MIGRATE_2")
	}))
	pool := routingPool{clients: map[int]*tg.Client{1: old, 2: tg.NewClient(tailInvoker{size: 4096})}}
	c := &Client{pool: pool}
	src := c.ChunkSource(&FileHandle{Size: 4096, DC: 1, Loc: &tg.InputDocumentFileLocation{ID: 1}}, 4096)
	if n, err := src.Chunk(context.Background(), 0, make([]byte, 4096)); (err != nil && !errors.Is(err, io.EOF)) || n != 4096 || calls != 1 {
		t.Fatalf("n=%d err=%v calls=%d", n, err, calls)
	}
	pool.clients[2] = old
	if _, err := src.Chunk(context.Background(), 0, make([]byte, 4096)); err == nil {
		t.Fatal("migration loop did not stop")
	}
}
func TestExpiredReferenceRefreshAndIdentity(t *testing.T) {
	api := tg.NewClient(invokeFunc(func(ctx context.Context, in bin.Encoder, out bin.Decoder) error {
		loc := in.(*tg.UploadGetFileRequest).Location.(*tg.InputDocumentFileLocation)
		if string(loc.FileReference) != "fresh" {
			return tgerr.New(400, "FILE_REFERENCE_EXPIRED")
		}
		return (tailInvoker{size: 4096}).Invoke(ctx, in, out)
	}))
	c := &Client{pool: tailPool{api: api}}
	src := c.ChunkSource(&FileHandle{Size: 4096, DC: 1, ChannelID: 1, MessageID: 1, Loc: &tg.InputDocumentFileLocation{ID: 1}}, 4096)
	src.refresh = func(context.Context, int64, int) (*FileHandle, error) {
		return &FileHandle{Size: 4096, DC: 1, Loc: &tg.InputDocumentFileLocation{ID: 1, FileReference: []byte("fresh")}}, nil
	}
	if n, err := src.Chunk(context.Background(), 0, make([]byte, 4096)); (err != nil && !errors.Is(err, io.EOF)) || n != 4096 {
		t.Fatalf("n=%d err=%v", n, err)
	}
	src = c.ChunkSource(&FileHandle{Size: 4096, DC: 1, ChannelID: 1, MessageID: 1, Loc: &tg.InputDocumentFileLocation{ID: 1}}, 4096)
	src.refresh = func(context.Context, int64, int) (*FileHandle, error) {
		return &FileHandle{Size: 4096, Loc: &tg.InputDocumentFileLocation{ID: 2}}, nil
	}
	if _, err := src.Chunk(context.Background(), 0, make([]byte, 4096)); err == nil || !strings.Contains(err.Error(), "文件已替换") {
		t.Fatalf("identity error=%v", err)
	}
}
