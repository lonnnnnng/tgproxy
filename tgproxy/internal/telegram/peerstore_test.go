package telegram

import (
	"context"
	"github.com/gotd/td/telegram/peers"
	"os"
	"path/filepath"
	"testing"
)

func TestPeerStorageSurvivesRestart(t *testing.T) {
	path := filepath.Join(t.TempDir(), "peers.json")
	s, err := openPeerStorage(path)
	if err != nil {
		t.Fatal(err)
	}
	key := peers.Key{Prefix: "channel_", ID: 123}
	if err = s.Save(context.Background(), key, peers.Value{AccessHash: 456}); err != nil {
		t.Fatal(err)
	}
	s, err = openPeerStorage(path)
	if err != nil {
		t.Fatal(err)
	}
	v, ok, err := s.Find(context.Background(), key)
	if err != nil || !ok || v.AccessHash != 456 {
		t.Fatalf("%+v %v %v", v, ok, err)
	}
	st, _ := os.Stat(path)
	if st.Mode().Perm() != 0600 {
		t.Fatal("peer cache is not private")
	}
}
