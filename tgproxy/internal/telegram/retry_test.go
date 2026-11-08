package telegram

import (
	"context"
	"errors"
	"github.com/gotd/td/bin"
	"github.com/gotd/td/tgerr"
	"testing"
	"time"
)

func TestFloodWaitRetriesAndCancels(t *testing.T) {
	calls := 0
	invoke := floodMiddleware(invokeFunc(func(context.Context, bin.Encoder, bin.Decoder) error {
		calls++
		if calls == 1 {
			return tgerr.New(420, "FLOOD_WAIT_0")
		}
		return nil
	}))
	if err := invoke(context.Background(), nil, nil); err != nil || calls != 2 {
		t.Fatalf("calls=%d err=%v", calls, err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Millisecond)
	defer cancel()
	invoke = floodMiddleware(invokeFunc(func(context.Context, bin.Encoder, bin.Decoder) error { return tgerr.New(420, "FLOOD_WAIT_60") }))
	if err := invoke(ctx, nil, nil); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatal(err)
	}
}

func TestClosedPoolRejectsNewClients(t *testing.T) {
	p := &safePool{inner: tailPool{}}
	if err := p.Close(); err != nil {
		t.Fatal(err)
	}
	_, err := p.Client(context.Background(), 2).UploadGetFile(context.Background(), nil)
	if err == nil {
		t.Fatal("closed pool created a client")
	}
	if err = p.Close(); err != nil {
		t.Fatal("close is not idempotent")
	}
}
