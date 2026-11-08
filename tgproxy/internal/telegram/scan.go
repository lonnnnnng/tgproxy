package telegram

import (
	"context"
	"errors"
	"fmt"

	"github.com/gotd/td/tg"

	"github.com/iyear/tdl/core/tmedia"
	"github.com/iyear/tdl/core/util/tutil"
)

// ResolveChannel resolves a user-provided channel reference to (channel id, title).
func (c *Client) ResolveChannel(ctx context.Context, ref string) (int64, string, error) {
	if c.peerMgr == nil {
		return 0, "", errors.New("client not connected")
	}
	p, err := tutil.GetInputPeer(ctx, c.peerMgr, ref)
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
	if c.peerMgr == nil {
		return nil, errors.New("client not connected")
	}
	p, err := tutil.GetInputPeer(ctx, c.peerMgr, fmt.Sprintf("%d", channelID))
	if err != nil {
		return nil, err
	}
	api := c.pool.Default(ctx)

	var items []MediaItem
	offsetID := 0
	for {
		hist, err := api.MessagesGetHistory(ctx, &tg.MessagesGetHistoryRequest{
			Peer:     p.InputPeer(),
			Limit:    200,
			OffsetID: offsetID,
		})
		if err != nil {
			return items, err
		}
		msgs := extractMessages(hist)
		if len(msgs) == 0 {
			break
		}
		for _, m := range msgs {
			offsetID = m.ID // descending; next page continues before this
			md, ok := tmedia.GetMedia(m)
			if !ok {
				continue
			}
			items = append(items, MediaItem{
				ChannelID: channelID,
				MessageID: m.ID,
				Name:      md.Name,
				Size:      md.Size,
				Date:      md.Date,
			})
			if limit > 0 && len(items) >= limit {
				return items, nil
			}
		}
		if len(msgs) < 200 {
			break
		}
	}
	return items, nil
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
	if c.peerMgr == nil {
		return nil, errors.New("client not connected")
	}
	p, err := tutil.GetInputPeer(ctx, c.peerMgr, fmt.Sprintf("%d", channelID))
	if err != nil {
		return nil, err
	}
	msg, err := tutil.GetSingleMessage(ctx, c.pool.Default(ctx), p.InputPeer(), msgID)
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
