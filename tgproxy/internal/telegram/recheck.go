package telegram

import (
	"context"
	"errors"
	"fmt"
	"github.com/gotd/td/tg"
	"github.com/iyear/tdl/core/tmedia"
	"reflect"
	"strings"
	"tgproxy/internal/media"
)

func videoMessage(channelID int64, m *tg.Message) (MediaItem, bool) {
	md, ok := tmedia.GetMedia(m)
	if !ok {
		return MediaItem{}, false
	}
	var mime string
	var documentID int64
	if media, ok := m.Media.(*tg.MessageMediaDocument); ok {
		if doc, ok := media.Document.(*tg.Document); ok {
			mime = doc.MimeType
			documentID = doc.ID
		}
	}
	if !media.IsVideo(md.Name) && !strings.HasPrefix(mime, "video/") {
		return MediaItem{}, false
	}
	name := md.Name
	if !media.IsVideo(name) {
		name += ".mp4"
	}
	return MediaItem{ChannelID: channelID, MessageID: m.ID, Name: name, Size: md.Size, MIME: mime, Date: md.Date, DocumentID: documentID}, true
}

type RecheckResult struct {
	Items   []MediaItem
	Missing []int
	Title   string
}
type messagesAPI interface {
	ChannelsGetMessages(context.Context, *tg.ChannelsGetMessagesRequest) (tg.MessagesMessagesClass, error)
}

func (c *Client) RefreshChannel(ctx context.Context, channelID int64) (JoinedChannel, error) {
	pool, mgr, err := c.runtime()
	if err != nil {
		return JoinedChannel{}, err
	}
	p, err := c.resolvePeer(ctx, fmt.Sprint(channelID))
	if err != nil {
		return JoinedChannel{}, err
	}
	peer, ok := p.InputPeer().(*tg.InputPeerChannel)
	if !ok {
		return JoinedChannel{}, errors.New("请选择频道")
	}
	chats, err := pool.Default(ctx).ChannelsGetChannels(ctx, []tg.InputChannelClass{&tg.InputChannel{ChannelID: peer.ChannelID, AccessHash: peer.AccessHash}})
	if err != nil {
		return JoinedChannel{}, err
	}
	for _, raw := range chats.GetChats() {
		if channel, ok := raw.(*tg.Channel); ok && channel.ID == channelID {
			if err := mgr.Apply(ctx, nil, []tg.ChatClass{channel}); err != nil {
				return JoinedChannel{}, err
			}
			return JoinedChannel{ID: channelID, Title: channel.Title, Ref: fmt.Sprint(channelID)}, nil
		}
	}
	return JoinedChannel{}, errors.New("频道信息不可用")
}

func (c *Client) RecheckMessages(ctx context.Context, channelID int64, ids []int) (RecheckResult, error) {
	pool, _, err := c.runtime()
	if err != nil {
		return RecheckResult{}, err
	}
	p, err := c.resolvePeer(ctx, fmt.Sprint(channelID))
	if err != nil {
		return RecheckResult{}, err
	}
	peer, ok := p.InputPeer().(*tg.InputPeerChannel)
	if !ok {
		return RecheckResult{}, errors.New("请选择频道")
	}
	return recheckMessages(ctx, pool.Default(ctx), &tg.InputChannel{ChannelID: peer.ChannelID, AccessHash: peer.AccessHash}, channelID, ids)
}

func recheckMessages(ctx context.Context, api messagesAPI, peer tg.InputChannelClass, channelID int64, ids []int) (RecheckResult, error) {
	if len(ids) == 0 || len(ids) > 100 {
		return RecheckResult{}, errors.New("每批校验应包含 1 至 100 条消息")
	}
	want := map[int]bool{}
	request := make([]tg.InputMessageClass, 0, len(ids))
	for _, id := range ids {
		if id <= 0 || int64(id) > 2147483647 || want[id] {
			return RecheckResult{}, errors.New("校验消息 ID 无效")
		}
		want[id] = true
		request = append(request, &tg.InputMessageID{ID: id})
	}
	response, err := api.ChannelsGetMessages(ctx, &tg.ChannelsGetMessagesRequest{Channel: peer, ID: request})
	if err != nil {
		return RecheckResult{}, err
	}
	var raw []tg.MessageClass
	var chats []tg.ChatClass
	switch r := response.(type) {
	case *tg.MessagesMessages:
		if r == nil {
			return RecheckResult{}, errors.New("empty response")
		}
		raw, chats = r.Messages, r.Chats
	case *tg.MessagesMessagesSlice:
		if r == nil || r.Inexact {
			return RecheckResult{}, errors.New("inexact response")
		}
		raw, chats = r.Messages, r.Chats
	case *tg.MessagesChannelMessages:
		if r == nil || r.Inexact {
			return RecheckResult{}, errors.New("inexact response")
		}
		raw, chats = r.Messages, r.Chats
	default:
		return RecheckResult{}, errors.New("unexpected recheck response")
	}
	result := RecheckResult{Items: []MediaItem{}, Missing: []int{}}
	seen := map[int]bool{}
	for _, mc := range raw {
		if mc == nil || reflect.ValueOf(mc).IsNil() {
			return RecheckResult{}, errors.New("missing message response")
		}
		id := mc.GetID()
		if !want[id] || seen[id] {
			return RecheckResult{}, errors.New("unexpected message identity")
		}
		seen[id] = true
		switch m := mc.(type) {
		case *tg.Message:
			if m == nil {
				return RecheckResult{}, errors.New("empty message")
			}
			if item, ok := videoMessage(channelID, m); ok {
				result.Items = append(result.Items, item)
			} else {
				result.Missing = append(result.Missing, id)
			}
		case *tg.MessageEmpty, *tg.MessageService:
			result.Missing = append(result.Missing, id)
		default:
			return RecheckResult{}, errors.New("unknown message type")
		}
	}
	// long: 省略、权限错误和不精确响应不能证明删除，整批必须得到明确结果后才允许本机提交。
	if len(seen) != len(want) {
		return RecheckResult{}, errors.New("校验结果不完整，已保留本机索引")
	}
	for _, ch := range chats {
		if channel, ok := ch.(*tg.Channel); ok && channel.ID == channelID {
			result.Title = channel.Title
		}
	}
	return result, nil
}
