package store

import (
	"os"
	"path/filepath"
	"testing"
)

func TestRemoveChannelAndPersistFailure(t *testing.T) {
	l, err := Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	if err = l.UpsertChannel(Channel{ID: 1, Ref: "@a"}); err != nil {
		t.Fatal(err)
	}
	if err = l.SetItems(1, []Item{{ChannelID: 1, MessageID: 2}}); err != nil {
		t.Fatal(err)
	}
	// long: 同一频道不同引用不能重复入库，删除后扫描结果也不能把已删频道复活。
	if err = l.UpsertChannel(Channel{ID: 1, Ref: "1"}); err != nil {
		t.Fatal(err)
	}
	if len(l.ListChannels()) != 1 {
		t.Fatal("duplicate channel")
	}
	if _, err = l.RemoveChannel("1"); err != nil {
		t.Fatal(err)
	}
	if _, ok := l.FindItem(1, 2); ok {
		t.Fatal("removed source is still playable")
	}
	if err = l.SetItems(1, []Item{{ChannelID: 1, MessageID: 2}}); err == nil {
		t.Fatal("removed channel was resurrected")
	}
	before := l.Revision
	l.path = filepath.Join(t.TempDir(), "directory")
	if err = os.Mkdir(l.path, 0700); err != nil {
		t.Fatal(err)
	}
	if err = l.UpsertChannel(Channel{ID: 2, Ref: "@b"}); err == nil {
		t.Fatal("save failure swallowed")
	}
	if len(l.ListChannels()) != 0 || l.Revision != before {
		t.Fatal("failed save changed memory")
	}
}

func TestMergeItemsPreservesExistingAndTracksNewestID(t *testing.T) {
	l, err := Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	if err = l.UpsertChannel(Channel{ID: 7, Ref: "@anime"}); err != nil {
		t.Fatal(err)
	}
	if err = l.SetItems(7, []Item{{ChannelID: 7, MessageID: 12, Name: "old.mp4"}}); err != nil {
		t.Fatal(err)
	}
	if err = l.MergeItemsWithCursor(7, []Item{{ChannelID: 7, MessageID: 15, Name: "new.mp4"}}, 15); err != nil {
		t.Fatal(err)
	}
	if l.ScanCursor(7) != 15 || len(l.AllItems()) != 2 {
		t.Fatalf("cursor=%d items=%+v", l.ScanCursor(7), l.AllItems())
	}
	if err = l.MergeItemsWithCursor(7, []Item{{ChannelID: 7, MessageID: 12, Name: "updated.mp4"}}, 15); err != nil {
		t.Fatal(err)
	}
	item, ok := l.FindItem(7, 12)
	if !ok || item.Name != "updated.mp4" || len(l.AllItems()) != 2 {
		t.Fatalf("merge did not replace duplicate: %+v", l.AllItems())
	}
}

func TestScanCursorPersistsForChannelsWithoutVideos(t *testing.T) {
	dir := t.TempDir()
	l, err := Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	if err = l.UpsertChannel(Channel{ID: 9, Ref: "@empty"}); err != nil {
		t.Fatal(err)
	}
	if err = l.SetItemsWithCursor(9, nil, 300); err != nil {
		t.Fatal(err)
	}
	if l.ScanCursor(9) != 300 {
		t.Fatalf("cursor=%d", l.ScanCursor(9))
	}
	reloaded, err := Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	if reloaded.ScanCursor(9) != 300 {
		t.Fatalf("persisted cursor=%d", reloaded.ScanCursor(9))
	}
}
