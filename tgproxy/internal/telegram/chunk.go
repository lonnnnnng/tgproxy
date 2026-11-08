package telegram

import (
	"context"
	"errors"
	"io"

	"github.com/gotd/td/tg"
	"github.com/gotd/td/tgerr"

	"github.com/iyear/tdl/core/dcpool"
	"github.com/iyear/tdl/core/tmedia"
)

// Pool returns the DC pool (nil until connected).
func (c *Client) Pool() dcpool.Pool { return c.pool }

// ChunkSource returns a stream.ChunkSource-compatible reader for a resolved file.
// chunkSize must be a multiple of 4096 (telegram alignment).
func (c *Client) ChunkSource(h *FileHandle, chunkSize int64) *chunkSource {
	return &chunkSource{c: c, h: h, chunk: chunkSize}
}

// chunkSource fetches aligned chunks via upload.getFile on the file's DC.
// It satisfies tgproxy/internal/stream.ChunkSource (Chunk(ctx, offset, b)).
type chunkSource struct {
	c     *Client
	h     *FileHandle
	chunk int64
}

// Chunk fills b with data at offset. b length must equal the chunk size.
func (cs *chunkSource) Chunk(ctx context.Context, offset int64, b []byte) (int64, error) {
	if int64(len(b)) != cs.chunk {
		return 0, errors.New("chunk buffer size mismatch")
	}
	if offset >= cs.h.Size {
		return 0, io.EOF
	}

	pool := cs.c.pool
	if pool == nil {
		return 0, errors.New("telegram not connected")
	}
	api := pool.Client(ctx, cs.h.DC)

	limit := cs.chunk
	if remain := cs.h.Size - offset; limit > remain {
		limit = remain
	}

	loc, err := cs.inputLocation()
	if err != nil {
		return 0, err
	}

	var n int64
	for n < limit {
		req := &tg.UploadGetFileRequest{
			Location: loc,
			Offset:   offset + n,
			Limit:    int(chunkLimit(limit - n)),
		}
		req.SetPrecise(true)

		res, err := api.UploadGetFile(ctx, req)
		if err != nil {
			if tgerr.IsCode(err, 303) {
				// FILE_MIGRATE_X: DC changed; refresh pool client and retry
				api = pool.Client(ctx, cs.h.DC)
				continue
			}
			return n, err
		}
		switch f := res.(type) {
		case *tg.UploadFile:
			if len(f.Bytes) == 0 {
				return n, io.EOF
			}
			cn := int64(copy(b[n:n+int64(len(f.Bytes))], f.Bytes))
			n += cn
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
	// round down to 4096 multiple
	return want - want%4096
}

// ensure tmedia import used (keeps helpers referenced)
var _ = tmedia.GetMedia
