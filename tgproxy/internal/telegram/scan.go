package telegram

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"strings"
	"tgproxy/internal/media"

	"github.com/gotd/td/telegram/downloader"
	"github.com/gotd/td/tg"

	"github.com/iyear/tdl/core/tmedia"
	"github.com/iyear/tdl/core/util/tutil"
)

// ResolveChannel resolves a user-provided channel reference to (channel id, title).
func (c *Client) ResolveChannel(ctx context.Context, ref string) (int64, string, error) {
	_, _, err := c.runtime()
	if err != nil {
		return 0, "", err
	}
	p, err := c.resolvePeer(ctx, ref)
	if err != nil {
		return 0, "", err
	}
	return tutil.GetInputPeerID(p.InputPeer()), p.VisibleName(), nil
}

// MediaItem is a scanned media file.
type MediaItem struct {
	ChannelID int64
	MessageID int
	Name      string
	Size      int64
	MIME      string
	Date      int64
}

// ScanChannel fetches messages of a channel and extracts media items (newest first).
func (c *Client) ScanChannel(ctx context.Context, channelID int64, limit int) ([]MediaItem, error) {
	return c.ScanChannelProgress(ctx, channelID, limit, nil)
}

func (c *Client) ScanChannelProgress(ctx context.Context, channelID int64, limit int, progress func(int)) ([]MediaItem, error) {
	items, _, err := c.ScanChannelSince(ctx, channelID, 0, limit, progress)
	return items, err
}

func (c *Client) ScanChannelSince(ctx context.Context, channelID int64, afterID, limit int, progress func(int)) ([]MediaItem, int, error) {
	pool, _, err := c.runtime()
	if err != nil {
		return nil, afterID, err
	}
	p, err := c.resolvePeer(ctx, fmt.Sprintf("%d", channelID))
	if err != nil {
		return nil, afterID, err
	}
	return scanHistorySince(ctx, pool.Default(ctx), p.InputPeer(), channelID, afterID, limit, progress)
}

func (c *Client) DownloadThumbnail(ctx context.Context, channelID int64, msgID int) ([]byte, error) {
	pool, _, err := c.runtime()
	if err != nil {
		return nil, err
	}
	p, err := c.resolvePeer(ctx, fmt.Sprintf("%d", channelID))
	if err != nil {
		return nil, err
	}
	msg, err := tutil.GetSingleMessage(ctx, pool.Default(ctx), p.InputPeer(), msgID)
	if err != nil {
		return nil, err
	}
	mm, ok := msg.Media.(*tg.MessageMediaDocument)
	if !ok {
		return nil, ErrNoThumbnail
	}
	doc, ok := mm.Document.(*tg.Document)
	if !ok {
		return nil, ErrNoThumbnail
	}
	thumbs, ok := doc.GetThumbs()
	if !ok || len(thumbs) == 0 {
		return nil, ErrNoThumbnail
	}
	var best *tg.PhotoSize
	var cached []byte
	area := -1
	for _, candidate := range thumbs {
		switch size := candidate.(type) {
		case *tg.PhotoCachedSize:
			sizeArea := size.W * size.H
			if sizeArea > area && len(size.Bytes) > 0 && len(size.Bytes) <= 2<<20 {
				area, cached, best = sizeArea, append([]byte(nil), size.Bytes...), nil
			}
		case *tg.PhotoSize:
			sizeArea := size.W * size.H
			if sizeArea > area && size.Size > 0 && size.Size <= 2<<20 {
				area, cached, best = sizeArea, nil, size
			}
		case *tg.PhotoSizeProgressive:
			sizeArea := size.W * size.H
			if sizeArea > area && len(size.Sizes) > 0 && size.Sizes[len(size.Sizes)-1] <= 2<<20 {
				area, cached, best = sizeArea, nil, &tg.PhotoSize{Type: size.Type, W: size.W, H: size.H, Size: size.Sizes[len(size.Sizes)-1]}
			}
		}
	}
	if len(cached) > 0 {
		return cached, nil
	}
	if best == nil {
		return nil, ErrNoThumbnail
	}
	loc := &tg.InputDocumentFileLocation{ID: doc.ID, AccessHash: doc.AccessHash, FileReference: doc.FileReference, ThumbSize: best.Type}
	var output bytes.Buffer
	_, err = downloader.NewDownloader().Download(pool.Client(ctx, doc.DCID), loc).WithSize(int64(best.Size)).Stream(ctx, &output)
	if err != nil {
		return nil, err
	}
	if output.Len() == 0 || output.Len() > 2<<20 {
		return nil, errors.New("thumbnail size is invalid")
	}
	return output.Bytes(), nil
}

type historyAPI interface {
	MessagesGetHistory(context.Context, *tg.MessagesGetHistoryRequest) (tg.MessagesMessagesClass, error)
}

var ErrNoThumbnail = errors.New("video has no downloadable thumbnail")

func scanHistory(ctx context.Context, api historyAPI, peer tg.InputPeerClass, channelID int64, limit int, progress ...func(int)) ([]MediaItem, error) {
	var callback func(int)
	if len(progress) > 0 {
		callback = progress[0]
	}
	items, _, err := scanHistorySince(ctx, api, peer, channelID, 0, limit, callback)
	return items, err
}

func scanHistorySince(ctx context.Context, api historyAPI, peer tg.InputPeerClass, channelID int64, afterID, limit int, progress func(int)) ([]MediaItem, int, error) {
	pages := 0
	var items []MediaItem
	offsetID := 0
	cursor := afterID
	for {
		hist, err := api.MessagesGetHistory(ctx, &tg.MessagesGetHistoryRequest{Peer: peer, Limit: 100, OffsetID: offsetID})
		if err != nil {
			return items, cursor, err
		}
		var raw []tg.MessageClass
		switch h := hist.(type) {
		case *tg.MessagesMessages:
			raw = h.Messages
		case *tg.MessagesMessagesSlice:
			raw = h.Messages
		case *tg.MessagesChannelMessages:
			raw = h.Messages
		default:
			return items, cursor, errors.New("unexpected history response")
		}
		if len(raw) == 0 {
			if progress != nil {
				progress(len(items))
			}
			return items, cursor, nil
		}
		next := 0
		boundaryReached := false
		for _, mc := range raw {
			id := mc.GetID()
			if id > cursor {
				cursor = id
			}
			if afterID > 0 && id > 0 && id <= afterID {
				boundaryReached = true
				continue
			}
			if id > 0 && (next == 0 || id < next) {
				next = id
			}
			m, ok := mc.(*tg.Message)
			if !ok {
				continue
			}
			md, ok := tmedia.GetMedia(m)
			if !ok {
				continue
			}
			mime := ""
			if mm, ok := m.Media.(*tg.MessageMediaDocument); ok {
				if doc, ok := mm.Document.(*tg.Document); ok {
					mime = doc.MimeType
				}
			}
			if !media.IsVideo(md.Name) && !strings.HasPrefix(mime, "video/") {
				continue
			}
			name := md.Name
			if !media.IsVideo(name) {
				name += ".mp4"
			}
			items = append(items, MediaItem{ChannelID: channelID, MessageID: m.ID, Name: name, Size: md.Size, MIME: mime, Date: md.Date})
			if limit > 0 && len(items) >= limit {
				return items, cursor, nil
			}
		}
		if boundaryReached {
			if progress != nil {
				progress(len(items))
			}
			return items, cursor, nil
		}
		// long: 照片、服务消息、短页仍可能有更早视频；以原始最小 ID 翻页直到空页。
		if next == 0 || (offsetID != 0 && next >= offsetID) {
			return items, cursor, errors.New("频道分页游标未推进，保留已有索引")
		}
		offsetID = next
		pages++
		if pages%10 == 0 && progress != nil {
			progress(len(items))
		}
	}
}

func extractMessages(hist tg.MessagesMessagesClass) []*tg.Message {
	var out []*tg.Message
	switch m := hist.(type) {
	case *tg.MessagesMessages:
		out = toMessages(m.Messages)
	case *tg.MessagesMessagesSlice:
		out = toMessages(m.Messages)
	case *tg.MessagesChannelMessages:
		out = toMessages(m.Messages)
	}
	return out
}

func toMessages(msgs []tg.MessageClass) []*tg.Message {
	var out []*tg.Message
	for _, mc := range msgs {
		if m, ok := mc.(*tg.Message); ok {
			out = append(out, m)
		}
	}
	return out
}

// ChunkFile resolves a channel/message pair to a downloadable file handle.
func (c *Client) ChunkFile(ctx context.Context, channelID int64, msgID int) (*FileHandle, error) {
	pool, _, err := c.runtime()
	if err != nil {
		return nil, err
	}
	p, err := c.resolvePeer(ctx, fmt.Sprintf("%d", channelID))
	if err != nil {
		return nil, err
	}
	msg, err := tutil.GetSingleMessage(ctx, pool.Default(ctx), p.InputPeer(), msgID)
	if err != nil {
		return nil, err
	}
	md, ok := tmedia.GetMedia(msg)
	if !ok {
		return nil, errors.New("message has no media")
	}
	return &FileHandle{
		Loc:       md.InputFileLoc,
		Size:      md.Size,
		Name:      md.Name,
		DC:        md.DC,
		ChannelID: channelID,
		MessageID: msgID,
	}, nil
}

// FileHandle is a resolved downloadable file.
type FileHandle struct {
	Loc       interface{} // tg.InputFileLocationClass
	Size      int64
	Name      string
	DC        int
	ChannelID int64
	MessageID int
}
