package telegram

import (
	"context"
	"encoding/json"
	"errors"
	"github.com/gotd/td/bin"
	"github.com/gotd/td/crypto"
	"github.com/gotd/td/session"
	"github.com/gotd/td/telegram/auth"
	"github.com/gotd/td/tg"
	"github.com/gotd/td/tgerr"
	"io"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// long: 两次读取同一文件的不同分块，核实成功恢复的引用是否会被下一个分块复用。
func TestReferenceRecoveryIsReusedAcrossChunks(t *testing.T) {
	stale, refreshes := 0, 0
	api := tg.NewClient(invokeFunc(func(ctx context.Context, in bin.Encoder, out bin.Decoder) error {
		loc := in.(*tg.UploadGetFileRequest).Location.(*tg.InputDocumentFileLocation)
		if string(loc.FileReference) != "fresh" {
			stale++
			return tgerr.New(400, "FILE_REFERENCE_EXPIRED")
		}
		return (tailInvoker{size: 8192}).Invoke(ctx, in, out)
	}))
	c := &Client{pool: tailPool{api: api}}
	src := c.ChunkSource(&FileHandle{Size: 8192, DC: 1, ChannelID: 1, MessageID: 1, Loc: &tg.InputDocumentFileLocation{ID: 1}}, 4096)
	src.refresh = func(context.Context, int64, int) (*FileHandle, error) {
		refreshes++
		return &FileHandle{Size: 8192, DC: 1, Loc: &tg.InputDocumentFileLocation{ID: 1, FileReference: []byte("fresh")}}, nil
	}
	for _, off := range []int64{0, 4096} {
		n, err := src.Chunk(context.Background(), off, make([]byte, 4096))
		if n != 4096 || (err != nil && !errors.Is(err, io.EOF)) {
			t.Fatal(n, err)
		}
	}
	t.Logf("chunks=2 stale_attempts=%d refresh_calls=%d", stale, refreshes)
	if refreshes != 1 {
		t.Fatalf("recovery was not reused: refresh_calls=%d, want 1", refreshes)
	}
}
func TestMigrationIsReusedAcrossChunks(t *testing.T) {
	stale := 0
	old := tg.NewClient(invokeFunc(func(context.Context, bin.Encoder, bin.Decoder) error {
		stale++
		return tgerr.New(303, "FILE_MIGRATE_2")
	}))
	c := &Client{pool: routingPool{clients: map[int]*tg.Client{1: old, 2: tg.NewClient(tailInvoker{size: 8192})}}}
	src := c.ChunkSource(&FileHandle{Size: 8192, DC: 1, Loc: &tg.InputDocumentFileLocation{ID: 1}}, 4096)
	for _, off := range []int64{0, 4096} {
		n, err := src.Chunk(context.Background(), off, make([]byte, 4096))
		if n != 4096 || (err != nil && !errors.Is(err, io.EOF)) {
			t.Fatal(n, err)
		}
	}
	t.Logf("chunks=2 old_dc_attempts=%d", stale)
	if stale != 1 {
		t.Fatalf("DC migration was not reused: old_dc_attempts=%d, want 1", stale)
	}
}

func TestConcurrentReferenceRecoveryIsCoalesced(t *testing.T) {
	var refreshes atomic.Int32
	entered := make(chan struct{})
	release := make(chan struct{})
	c := &Client{}
	src := c.ChunkSource(&FileHandle{Size: 8192, Loc: &tg.InputDocumentFileLocation{ID: 1}}, 4096)
	_, v := src.location()
	src.refresh = func(ctx context.Context, _ int64, _ int) (*FileHandle, error) {
		if refreshes.Add(1) == 1 {
			close(entered)
		}
		select {
		case <-release:
		case <-ctx.Done():
			return nil, ctx.Err()
		}
		return &FileHandle{Size: 8192, Loc: &tg.InputDocumentFileLocation{ID: 1, FileReference: []byte("fresh")}}, nil
	}
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if err := src.refreshLocation(context.Background(), v); err != nil {
				t.Error(err)
			}
		}()
	}
	<-entered
	close(release)
	wg.Wait()
	if refreshes.Load() != 1 {
		t.Fatalf("refreshes=%d", refreshes.Load())
	}
}
func TestWaitingReferenceRecoveryCanBeCancelled(t *testing.T) {
	c := &Client{}
	src := c.ChunkSource(&FileHandle{Size: 8192, Loc: &tg.InputDocumentFileLocation{ID: 1}}, 4096)
	_, v := src.location()
	entered := make(chan struct{})
	release := make(chan struct{})
	src.refresh = func(context.Context, int64, int) (*FileHandle, error) {
		close(entered)
		<-release
		return &FileHandle{Size: 8192, Loc: &tg.InputDocumentFileLocation{ID: 1}}, nil
	}
	done := make(chan error, 1)
	go func() { done <- src.refreshLocation(context.Background(), v) }()
	<-entered
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if err := src.refreshLocation(ctx, v); !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
	close(release)
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}
func TestDialogsGateHonorsWaitingCancellation(t *testing.T) {
	c := &Client{}
	if err := c.acquireDialogs(context.Background()); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Millisecond)
	defer cancel()
	if err := c.acquireDialogs(ctx); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatal(err)
	}
	<-c.dialogGate
}
func TestSessionValidationAndPasswordSentinel(t *testing.T) {
	if !passwordNeeded(auth.ErrPasswordAuthNeeded) || !passwordNeeded(tgerr.New(400, "SESSION_PASSWORD_NEEDED")) {
		t.Fatal("2FA branch lost")
	}
	var key crypto.Key
	key[0] = 1
	id := key.ID()
	mem := &session.StorageMemory{}
	loader := session.Loader{Storage: mem}
	if err := loader.Save(context.Background(), &session.Data{DC: 2, AuthKey: key[:], AuthKeyID: id[:]}); err != nil {
		t.Fatal(err)
	}
	raw, _ := mem.LoadSession(context.Background())
	if err := ValidateSession(raw); err != nil {
		t.Fatal(err)
	}
	for _, bad := range [][]byte{[]byte("x"), []byte(`{"Version":1}`), []byte(`{"Version":9}`)} {
		if ValidateSession(bad) == nil {
			t.Fatal("accepted damaged session")
		}
	}
	var value map[string]interface{}
	json.Unmarshal(raw, &value)
	value["Data"].(map[string]interface{})["AuthKeyID"] = "AAAA"
	raw, _ = json.Marshal(value)
	if ValidateSession(raw) == nil {
		t.Fatal("accepted corrupt key identity")
	}
}

func TestReferenceRefreshMergesConcurrentMigration(t *testing.T) {
	c := &Client{}
	src := c.ChunkSource(&FileHandle{Size: 8192, DC: 1, Loc: &tg.InputDocumentFileLocation{ID: 1}}, 4096)
	_, version := src.location()
	entered := make(chan struct{})
	release := make(chan struct{})
	src.refresh = func(context.Context, int64, int) (*FileHandle, error) {
		close(entered)
		<-release
		return &FileHandle{Size: 8192, DC: 1, Loc: &tg.InputDocumentFileLocation{ID: 1, FileReference: []byte("fresh")}}, nil
	}
	done := make(chan error, 1)
	go func() { done <- src.refreshLocation(context.Background(), version) }()
	<-entered
	src.migrate(version, 2)
	close(release)
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	current, _ := src.location()
	if current.DC != 2 || string(current.Loc.(*tg.InputDocumentFileLocation).FileReference) != "fresh" {
		t.Fatal("lost concurrent recovery")
	}
}
