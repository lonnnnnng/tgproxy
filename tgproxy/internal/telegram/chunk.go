package telegram

import (
	"context"
	"errors"
	"fmt"
	"io"

	"github.com/gotd/td/tg"
	"github.com/gotd/td/tgerr"

	"github.com/iyear/tdl/core/dcpool"
	"github.com/iyear/tdl/core/tmedia"
)

// Pool returns the DC pool (nil until connected).
func (c *Client) Pool() dcpool.Pool { c.runtimeMu.RLock(); defer c.runtimeMu.RUnlock(); return c.pool }

// ChunkSource returns a stream.ChunkSource-compatible reader for a resolved file.
// chunkSize must be a multiple of 4096 (telegram alignment).
func (c *Client) ChunkSource(h *FileHandle, chunkSize int64) *chunkSource {
	return &chunkSource{c: c, h: h, chunk: chunkSize, refresh: c.ChunkFile}
}

// chunkSource fetches aligned chunks via upload.getFile on the file's DC.
// It satisfies tgproxy/internal/stream.ChunkSource (Chunk(ctx, offset, b)).
type chunkSource struct {
	c       *Client
	h       *FileHandle
	chunk   int64
	refresh func(context.Context, int64, int) (*FileHandle, error)
}

func fileIdentity(h *FileHandle) string {
	if loc, ok := h.Loc.(*tg.InputDocumentFileLocation); ok {
		return fmt.Sprintf("document:%d:%d", loc.ID, h.Size)
	}
	return fmt.Sprintf("%d:%d:%d", h.ChannelID, h.MessageID, h.Size)
}
func (cs *chunkSource) CacheKey() string { return fileIdentity(cs.h) }

// Chunk fills b with data at offset. b length must equal the chunk size.
func (cs *chunkSource) Chunk(ctx context.Context, offset int64, b []byte) (int64, error) {
	if int64(len(b)) != cs.chunk {
		return 0, errors.New("chunk buffer size mismatch")
	}
	if offset >= cs.h.Size {
		return 0, io.EOF
	}

	pool := cs.c.Pool()
	if pool == nil {
		return 0, errors.New("telegram not connected")
	}
	dc := cs.h.DC
	api := pool.Client(ctx, dc)

	limit := cs.chunk
	if remain := cs.h.Size - offset; limit > remain {
		limit = remain
	}

	loc, err := cs.inputLocation()
	if err != nil {
		return 0, err
	}

	var n int64
	migrations, references := 0, 0
	for n < limit {
		// long: 即使业务分块改变，请求也不能跨越 Telegram 的 1 MiB 文件窗口。
		requestLimit := min(chunkLimit(limit-n), (1<<20)-(offset+n)%(1<<20))
		req := &tg.UploadGetFileRequest{
			Location: loc,
			Offset:   offset + n,
			Limit:    int(requestLimit),
		}
		req.SetPrecise(true)

		res, err := api.UploadGetFile(ctx, req)
		if err != nil {
			if migration, ok := tgerr.AsType(err, "FILE_MIGRATE"); ok && migration.Argument > 0 && migration.Argument != dc && migrations < 3 {
				migrations++
				dc = migration.Argument
				api = pool.Client(ctx, dc)
				continue
			}
			if (tgerr.Is(err, "FILE_REFERENCE_EXPIRED") || tgerr.Is(err, "FILE_REFERENCE_INVALID")) && references == 0 && cs.refresh != nil {
				references++
				h, freshErr := cs.refresh(ctx, cs.h.ChannelID, cs.h.MessageID)
				if freshErr != nil {
					return n, freshErr
				}
				// long: 消息可能被编辑换片，不能把新文件分块拼进旧文件缓存。
				if fileIdentity(h) != fileIdentity(cs.h) {
					return n, errors.New("Telegram 文件已替换，请重新打开视频")
				}
				freshLoc, ok := h.Loc.(tg.InputFileLocationClass)
				if !ok {
					return n, errors.New("invalid refreshed file location")
				}
				loc = freshLoc
				dc = h.DC
				api = pool.Client(ctx, dc)
				continue
			}
			return n, err
		}
		switch f := res.(type) {
		case *tg.UploadFile:
			if len(f.Bytes) == 0 {
				return n, io.EOF
			}
			// long: 请求长度会向上对齐，但缓存只能保留当前文件块内的真实数据。
			cn := int64(copy(b[n:limit], f.Bytes))
			n += cn
			if n < limit && (offset+n)%1024 != 0 {
				return n, io.ErrUnexpectedEOF
			}
		case *tg.UploadFileCDNRedirect:
			return n, errors.New("cdn redirect unsupported")
		default:
			return n, errors.New("unexpected upload.getFile response")
		}
	}
	if offset+n >= cs.h.Size {
		return n, io.EOF
	}
	return n, nil
}

func (cs *chunkSource) inputLocation() (tg.InputFileLocationClass, error) {
	if loc, ok := cs.h.Loc.(tg.InputFileLocationClass); ok {
		return loc, nil
	}
	return nil, errors.New("invalid file location")
}

// chunkLimit clamps to telegram's max limit (1 MiB, must be divisible by 4096).
func chunkLimit(want int64) int64 {
	const max = 1 << 20
	if want > max {
		return max
	}
	// long: 尾块不足 4096 字节时仍需请求一整段；向下取整会发出零长度请求，
	// 导致 MP4 尾部索引丢失，Jellyfin 无法探测媒体信息或播放。
	return (want + 4095) / 4096 * 4096
}

// ensure tmedia import used (keeps helpers referenced)
var _ = tmedia.GetMedia
