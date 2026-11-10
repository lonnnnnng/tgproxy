package telegram

import (
	"context"
	"errors"
	"github.com/gotd/td/tg"
	"testing"
)

type messagesFunc func(context.Context, *tg.ChannelsGetMessagesRequest) (tg.MessagesMessagesClass, error)

func (f messagesFunc) ChannelsGetMessages(ctx context.Context, r *tg.ChannelsGetMessagesRequest) (tg.MessagesMessagesClass, error) {
	return f(ctx, r)
}
func TestRecheckRequiresExplicitEvidenceForEveryID(t *testing.T) {
	good := documentMessage(1, "video/mp4", "renamed.mp4")
	good.Media.(*tg.MessageMediaDocument).Document.(*tg.Document).ID = 99
	result, err := recheckMessages(context.Background(), messagesFunc(func(_ context.Context, r *tg.ChannelsGetMessagesRequest) (tg.MessagesMessagesClass, error) {
		if len(r.ID) != 3 {
			t.Fatal("invalid request")
		}
		return &tg.MessagesChannelMessages{Messages: []tg.MessageClass{&tg.MessageEmpty{ID: 2}, good, &tg.Message{ID: 3}}, Chats: []tg.ChatClass{&tg.Channel{ID: 7, Title: "renamed channel"}}}, nil
	}), &tg.InputChannel{ChannelID: 7}, 7, []int{1, 2, 3})
	if err != nil || len(result.Items) != 1 || len(result.Missing) != 2 || result.Items[0].DocumentID != 99 || result.Title != "renamed channel" {
		t.Fatalf("%+v %v", result, err)
	}
	for _, response := range []tg.MessagesMessagesClass{nil, &tg.MessagesMessages{Messages: []tg.MessageClass{(*tg.MessageEmpty)(nil)}}, &tg.MessagesMessages{}, &tg.MessagesMessagesNotModified{}, &tg.MessagesMessages{Messages: []tg.MessageClass{good, good}}, &tg.MessagesChannelMessages{Inexact: true}, &tg.MessagesMessages{Messages: []tg.MessageClass{&tg.MessageEmpty{ID: 9}}}} {
		r, err := recheckMessages(context.Background(), messagesFunc(func(context.Context, *tg.ChannelsGetMessagesRequest) (tg.MessagesMessagesClass, error) {
			return response, nil
		}), &tg.InputChannel{}, 7, []int{1})
		if err == nil || len(r.Items) != 0 || len(r.Missing) != 0 {
			t.Fatal("unsafe partial result", r, err)
		}
	}
	r, err := recheckMessages(context.Background(), messagesFunc(func(context.Context, *tg.ChannelsGetMessagesRequest) (tg.MessagesMessagesClass, error) {
		return nil, errors.New("CHANNEL_PRIVATE")
	}), &tg.InputChannel{}, 7, []int{1})
	if err == nil || len(r.Missing) != 0 {
		t.Fatal("permission error became deletion")
	}
}
func TestRecheckRejectsBadRequestsBeforeRPC(t *testing.T) {
	for _, ids := range [][]int{nil, {0}, {1, 1}, make([]int, 101)} {
		_, err := recheckMessages(context.Background(), messagesFunc(func(context.Context, *tg.ChannelsGetMessagesRequest) (tg.MessagesMessagesClass, error) {
			t.Fatal("unexpected RPC")
			return nil, nil
		}), &tg.InputChannel{}, 7, ids)
		if err == nil {
			t.Fatal("accepted invalid IDs")
		}
	}
}
