package telegram

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"tgproxy/internal/media"

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
	pool, _, err := c.runtime()
	if err != nil {
		return nil, err
	}
	p, err := c.resolvePeer(ctx, fmt.Sprintf("%d", channelID))
	if err != nil {
		return nil, err
	}
	return scanHistory(ctx, pool.Default(ctx), p.InputPeer(), channelID, limit, progress)
}

type historyAPI interface {
	MessagesGetHistory(context.Context, *tg.MessagesGetHistoryRequest) (tg.MessagesMessagesClass, error)
}

func scanHistory(ctx context.Context, api historyAPI, peer tg.InputPeerClass, channelID int64, limit int, progress ...func(int)) ([]MediaItem, error) {
	pages := 0
	var items []MediaItem
	offsetID := 0
	for {
		hist, err := api.MessagesGetHistory(ctx, &tg.MessagesGetHistoryRequest{Peer: peer, Limit: 100, OffsetID: offsetID})
		if err != nil {
			return items, err
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
			return items, errors.New("unexpected history response")
		}
		if len(raw) == 0 {
			if len(progress) > 0 && progress[0] != nil {
				progress[0](len(items))
			}
			return items, nil
		}
		next := 0
		for _, mc := range raw {
			id := mc.GetID()
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
				return items, nil
			}
		}
		// long: 照片、服务消息、短页仍可能有更早视频；以原始最小 ID 翻页直到空页。
		if next == 0 || (offsetID != 0 && next >= offsetID) {
			return items, errors.New("频道分页游标未推进，保留已有索引")
		}
		offsetID = next
		pages++
		if pages%10 == 0 && len(progress) > 0 && progress[0] != nil {
			progress[0](len(items))
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
