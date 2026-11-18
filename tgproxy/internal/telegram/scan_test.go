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

func TestScanSinceStopsAtExistingMessageBoundary(t *testing.T) {
	calls := 0
	api := historyFunc(func(_ context.Context, r *tg.MessagesGetHistoryRequest) (tg.MessagesMessagesClass, error) {
		calls++
		if r.OffsetID != 0 {
			t.Fatalf("unexpected second page offset %d", r.OffsetID)
		}
		return &tg.MessagesChannelMessages{Messages: []tg.MessageClass{
			documentMessage(120, "video/mp4", "new-2.mp4"),
			documentMessage(110, "video/mp4", "new-1.mp4"),
			documentMessage(100, "video/mp4", "old.mp4"),
			documentMessage(90, "video/mp4", "older.mp4"),
		}}, nil
	})
	items, cursor, err := scanHistorySince(context.Background(), api, &tg.InputPeerChannel{}, 1, 100, 0, nil)
	if err != nil || len(items) != 2 || cursor != 120 || calls != 1 || items[0].MessageID != 120 || items[1].MessageID != 110 {
		t.Fatalf("items=%+v cursor=%d err=%v calls=%d", items, cursor, err, calls)
	}
}

func TestScanSinceAdvancesCursorWithoutVideos(t *testing.T) {
	calls := 0
	api := historyFunc(func(_ context.Context, r *tg.MessagesGetHistoryRequest) (tg.MessagesMessagesClass, error) {
		calls++
		if calls == 1 && r.OffsetID == 0 {
			return &tg.MessagesChannelMessages{Messages: []tg.MessageClass{&tg.MessageEmpty{ID: 900}}}, nil
		}
		if calls == 2 && r.OffsetID == 900 {
			return &tg.MessagesChannelMessages{}, nil
		}
		t.Fatalf("unexpected call=%d offset=%d", calls, r.OffsetID)
		return nil, nil
	})
	items, cursor, err := scanHistorySince(context.Background(), api, &tg.InputPeerChannel{}, 1, 0, 0, nil)
	if err != nil || len(items) != 0 || cursor != 900 {
		t.Fatalf("items=%+v cursor=%d err=%v", items, cursor, err)
	}
}

func documentMessage(id int, mime, name string) *tg.Message {
	m := &tg.Message{ID: id}
	m.SetMedia(&tg.MessageMediaDocument{Document: &tg.Document{ID: int64(id), Size: 100, MimeType: mime, Attributes: []tg.DocumentAttributeClass{&tg.DocumentAttributeFilename{FileName: name}}}})
	return m
}
