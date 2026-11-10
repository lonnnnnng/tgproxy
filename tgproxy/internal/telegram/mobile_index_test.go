package telegram

import (
	"context"
	"errors"
	"github.com/gotd/td/tg"
	"testing"
)

func TestHistoryPageUsesAllMessagesForCursor(t *testing.T) {
	api := historyFunc(func(_ context.Context, r *tg.MessagesGetHistoryRequest) (tg.MessagesMessagesClass, error) {
		if r.OffsetID != 200 || r.MinID != 100 || r.Limit != 100 {
			t.Fatalf("unexpected request: %+v", r)
		}
		return &tg.MessagesChannelMessages{Messages: []tg.MessageClass{
			&tg.MessageService{ID: 190}, documentMessage(180, "video/mp4", "a.mp4"), &tg.MessageEmpty{ID: 150},
		}}, nil
	})
	page, err := historyPage(context.Background(), api, &tg.InputPeerChannel{}, 12, 200, 100)
	if err != nil || page.NextID != 150 || page.NewestID != 190 || page.Done || len(page.Items) != 1 || page.Items[0].ChannelID != 12 {
		t.Fatalf("page=%+v err=%v", page, err)
	}
}
func TestHistoryPageDoesNotEndOnShortNonVideoPage(t *testing.T) {
	api := historyFunc(func(context.Context, *tg.MessagesGetHistoryRequest) (tg.MessagesMessagesClass, error) {
		return &tg.MessagesMessages{Messages: []tg.MessageClass{&tg.MessageEmpty{ID: 8}}}, nil
	})
	page, err := historyPage(context.Background(), api, &tg.InputPeerChannel{}, 1, 0, 0)
	if err != nil || page.Done || page.NextID != 8 || len(page.Items) != 0 {
		t.Fatalf("page=%+v err=%v", page, err)
	}
	if _, err := historyPage(context.Background(), api, &tg.InputPeerChannel{}, 1, 8, 0); err == nil {
		t.Fatal("accepted non-advancing cursor")
	}
}
func TestHistoryPageErrorsDoNotProduceCommitPage(t *testing.T) {
	api := historyFunc(func(context.Context, *tg.MessagesGetHistoryRequest) (tg.MessagesMessagesClass, error) {
		return nil, errors.New("network failure")
	})
	page, err := historyPage(context.Background(), api, &tg.InputPeerChannel{}, 1, 8, 0)
	if err == nil || page.NextID != 0 || page.Done {
		t.Fatalf("page=%+v err=%v", page, err)
	}
}
