package telegram

import (
	"context"
	"fmt"
	"github.com/gotd/td/telegram/peers"
	"github.com/gotd/td/telegram/query/dialogs"
	"github.com/gotd/td/tg"
	"github.com/iyear/tdl/core/util/tutil"
	"strconv"
	"strings"
)

func (c *Client) resolvePeer(ctx context.Context, ref string) (peers.Peer, error) {
	pool, mgr, err := c.runtime()
	if err != nil {
		return nil, err
	}
	ref = strings.TrimSpace(ref)
	id, numericErr := strconv.ParseInt(ref, 10, 64)
	if numericErr == nil {
		// long: Telegram 客户端常展示 -100 开头 ID，MTProto 频道方法使用正的原始 ID。
		if id <= -1000000000000 {
			id = -id - 1000000000000
		} else if id < 0 {
			id = -id
		}
		ref = strconv.FormatInt(id, 10)
	}
	p, err := tutil.GetInputPeer(ctx, mgr, ref)
	if err == nil || numericErr != nil {
		return p, err
	}
	// long: 重启后的数字 ID 可能缺少 access_hash，只在直接解析失败时查已有会话，不加入新频道。
	if err := c.acquireDialogs(ctx); err != nil {
		return nil, err
	}
	defer func() { <-c.dialogGate }()
	if ctx.Err() != nil {
		return nil, ctx.Err()
	}
	if p, err = tutil.GetInputPeer(ctx, mgr, ref); err == nil {
		return p, nil
	}
	iter := dialogs.NewQueryBuilder(pool.Default(ctx)).GetDialogs().BatchSize(100).Iter()
	for iter.Next(ctx) {
		if peer, ok := iter.Value().Peer.(*tg.InputPeerChannel); ok && peer.ChannelID == id {
			if err = mgr.Apply(ctx, nil, []tg.ChatClass{&tg.Channel{ID: id, AccessHash: peer.AccessHash}}); err != nil {
				return nil, err
			}
			return tutil.GetInputPeer(ctx, mgr, ref)
		}
	}
	if err = iter.Err(); err != nil {
		return nil, err
	}
	return nil, fmt.Errorf("无法解析频道 %s，请使用公开频道链接或确认账号已加入该频道", ref)
}
