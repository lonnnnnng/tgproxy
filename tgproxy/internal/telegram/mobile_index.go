package telegram

import (
	"context"
	"errors"
	"fmt"

	"github.com/gotd/td/telegram/query/dialogs"
	"github.com/gotd/td/tg"
)

type JoinedChannel struct {
	ID    int64  `json:"channel_id"`
	Title string `json:"title"`
	Ref   string `json:"ref"`
}

// JoinedChannels long: 同时枚举主列表和归档，避免已归档频道在手机选择器中消失。
func (c *Client) JoinedChannels(ctx context.Context) ([]JoinedChannel, error) {
	pool, mgr, err := c.runtime()
	if err != nil {
		return nil, err
	}
	rows := []JoinedChannel{}
	seen := map[int64]bool{}
	err = dialogs.NewQueryBuilder(pool.Default(ctx)).ForEachFolder(ctx, 100, func(ctx context.Context, _ int, e dialogs.Elem) error {
		peer, ok := e.Peer.(*tg.InputPeerChannel)
		if !ok || seen[peer.ChannelID] {
			return nil
		}
		channel, ok := e.Entities.Channel(peer.ChannelID)
		if !ok || channel.Left || channel.Min || (!channel.Broadcast && !channel.Megagroup) {
			return nil
		}
		if err := mgr.Apply(ctx, nil, []tg.ChatClass{channel}); err != nil {
			return err
		}
		seen[channel.ID] = true
		rows = append(rows, JoinedChannel{channel.ID, channel.Title, fmt.Sprint(channel.ID)})
		return nil
	})
	return rows, err
}

type HistoryPage struct {
	Items    []MediaItem
	NextID   int
	NewestID int
	Done     bool
	Messages int
}

func (c *Client) ChannelHistoryPage(ctx context.Context, channelID int64, offset, minID int) (HistoryPage, error) {
	pool, _, err := c.runtime()
	if err != nil {
		return HistoryPage{}, err
	}
	p, err := c.resolvePeer(ctx, fmt.Sprint(channelID))
	if err != nil {
		return HistoryPage{}, err
	}
	if _, ok := p.InputPeer().(*tg.InputPeerChannel); !ok {
		return HistoryPage{}, errors.New("请选择频道或超级群组")
	}
	return historyPage(ctx, pool.Default(ctx), p.InputPeer(), channelID, offset, minID)
}

func historyPage(ctx context.Context, api historyAPI, peer tg.InputPeerClass, channelID int64, offset, minID int) (HistoryPage, error) {
	hist, err := api.MessagesGetHistory(ctx, &tg.MessagesGetHistoryRequest{Peer: peer, OffsetID: offset, MinID: minID, Limit: 100})
	if err != nil {
		return HistoryPage{}, err
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
		return HistoryPage{}, errors.New("unexpected history response")
	}
	page := HistoryPage{Items: []MediaItem{}, Done: len(raw) == 0, Messages: len(raw)}
	for _, mc := range raw {
		id := mc.GetID()
		if id <= 0 {
			continue
		}
		if id > page.NewestID {
			page.NewestID = id
		}
		// long: 水位和断点取自所有原始消息；非视频页也必须推进，否则续扫会卡住或漏页。
		if page.NextID == 0 || id < page.NextID {
			page.NextID = id
		}
		if id <= minID {
			page.Done = true
			continue
		}
		m, ok := mc.(*tg.Message)
		if !ok {
			continue
		}
		if item, ok := videoMessage(channelID, m); ok {
			page.Items = append(page.Items, item)
		}
	}
	if !page.Done && (page.NextID == 0 || (offset > 0 && page.NextID >= offset)) {
		return HistoryPage{}, errors.New("频道分页游标未推进")
	}
	return page, nil
}
