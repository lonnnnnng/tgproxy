package telegram

import (
	"context"
	"fmt"
	"strconv"

	"github.com/gotd/td/telegram/query/dialogs"
	"github.com/gotd/td/tg"
)

// JoinedChannel is one channel/supergroup visible to the logged-in account.
type JoinedChannel struct {
	ID        int64  `json:"id"`
	Title     string `json:"title"`
	Ref       string `json:"ref"`
	Username  string `json:"username,omitempty"`
	Broadcast bool   `json:"broadcast"`
	Megagroup bool   `json:"megagroup"`
}

// JoinedChannels lists channels and supergroups from the account dialog list.
func (c *Client) JoinedChannels(ctx context.Context) ([]JoinedChannel, error) {
	pool, _, err := c.runtime()
	if err != nil {
		return nil, err
	}
	iter := dialogs.NewQueryBuilder(pool.Default(ctx)).GetDialogs().BatchSize(100).Iter()
	out := make([]JoinedChannel, 0, 64)
	for iter.Next(ctx) {
		elem := iter.Value()
		peer, ok := elem.Peer.(*tg.InputPeerChannel)
		if !ok {
			continue
		}
		ch, ok := elem.Entities.Channel(peer.ChannelID)
		if !ok || ch.Left {
			continue
		}
		title := ch.Title
		if title == "" {
			title = fmt.Sprintf("频道 %d", ch.ID)
		}
		ref := strconv.FormatInt(ch.ID, 10)
		if ch.Username != "" {
			ref = "@" + ch.Username
		}
		out = append(out, JoinedChannel{
			ID:        ch.ID,
			Title:     title,
			Ref:       ref,
			Username:  ch.Username,
			Broadcast: ch.Broadcast,
			Megagroup: ch.Megagroup,
		})
		if len(out) >= 500 {
			break
		}
	}
	if err := iter.Err(); err != nil {
		return nil, err
	}
	return out, nil
}
