package telegram

import (
	"context"
	"github.com/gotd/td/tg"
	"testing"
)

type historyFunc func(context.Context, *tg.MessagesGetHistoryRequest) (tg.MessagesMessagesClass, error)

func (f historyFunc) MessagesGetHistory(ctx context.Context, r *tg.MessagesGetHistoryRequest) (tg.MessagesMessagesClass, error) {
	return f(ctx, r)
}

func TestScanContinuesAcrossShortAndNonVideoPages(t *testing.T) {
	calls := 0
	api := historyFunc(func(_ context.Context, r *tg.MessagesGetHistoryRequest) (tg.MessagesMessagesClass, error) {
		calls++
		if r.Limit != 100 {
			t.Fatalf("batch=%d", r.Limit)
		}
		switch calls {
		case 1:
			if r.OffsetID != 0 {
				t.Fatal(r.OffsetID)
			}
			return &tg.MessagesChannelMessages{Messages: []tg.MessageClass{&tg.MessageService{ID: 110}, &tg.MessageEmpty{ID: 100}}}, nil
		case 2:
			if r.OffsetID != 100 {
				t.Fatal(r.OffsetID)
			}
			return &tg.MessagesChannelMessages{Messages: []tg.MessageClass{documentMessage(90, "video/mp4", "剧名 第01集.mp4"), documentMessage(80, "image/jpeg", "image.jpg")}}, nil
		default:
			if r.OffsetID != 80 {
				t.Fatal(r.OffsetID)
			}
			return &tg.MessagesChannelMessages{}, nil
		}
	})
	items, err := scanHistory(context.Background(), api, &tg.InputPeerChannel{}, 1, 0)
	if err != nil || len(items) != 1 || items[0].MIME != "video/mp4" || calls != 3 {
		t.Fatalf("items=%+v err=%v calls=%d", items, err, calls)
	}
}

func TestScanRejectsNonAdvancingHistory(t *testing.T) {
	api := historyFunc(func(context.Context, *tg.MessagesGetHistoryRequest) (tg.MessagesMessagesClass, error) {
		return &tg.MessagesMessages{Messages: []tg.MessageClass{&tg.MessageEmpty{ID: 10}}}, nil
	})
	if _, err := scanHistory(context.Background(), api, &tg.InputPeerChannel{}, 1, 0); err == nil {
		t.Fatal("non-advancing history loop accepted")
	}
}

func documentMessage(id int, mime, name string) *tg.Message {
	m := &tg.Message{ID: id}
	m.SetMedia(&tg.MessageMediaDocument{Document: &tg.Document{ID: int64(id), Size: 100, MimeType: mime, Attributes: []tg.DocumentAttributeClass{&tg.DocumentAttributeFilename{FileName: name}}}})
	return m
}
