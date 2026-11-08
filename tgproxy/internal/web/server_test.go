package web

import (
	"context"
	"encoding/json"
	"errors"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"tgproxy/internal/store"
	"tgproxy/internal/telegram"
	"time"
)

func TestScanGuardsAndPartialFailure(t *testing.T) {
	dir := t.TempDir()
	lib, err := store.Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	if err = lib.UpsertChannel(store.Channel{ID: 1, Ref: "@a", Title: "a"}); err != nil {
		t.Fatal(err)
	}
	if err = lib.UpsertChannel(store.Channel{ID: 2, Ref: "@b", Title: "b"}); err != nil {
		t.Fatal(err)
	}
	s := NewServer(Deps{Store: lib, MediaDir: filepath.Join(dir, "media"), StrmBase: "http://host"})
	w := httptest.NewRecorder()
	s.Handler().ServeHTTP(w, httptest.NewRequest("POST", "/api/scan", nil))
	if w.Code != 503 {
		t.Fatalf("nil client status=%d", w.Code)
	}
	started, release := make(chan struct{}), make(chan struct{})
	s.deps.Connected = func() bool { return true }
	s.deps.ScanChannel = func(_ context.Context, ch int64, _ int) ([]telegram.MediaItem, error) {
		if ch == 2 {
			return nil, errors.New("test scan failed")
		}
		close(started)
		<-release
		return []telegram.MediaItem{{ChannelID: 1, MessageID: 10, Name: "剧名 第01集.mp4"}}, nil
	}
	w = httptest.NewRecorder()
	s.Handler().ServeHTTP(w, httptest.NewRequest("POST", "/api/scan", nil))
	if w.Code != 200 {
		t.Fatal(w.Code)
	}
	<-started
	w = httptest.NewRecorder()
	s.Handler().ServeHTTP(w, httptest.NewRequest("POST", "/api/rebuild", nil))
	if w.Code != 409 {
		t.Fatalf("rebuild while scanning=%d", w.Code)
	}
	close(release)
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		s.mu.Lock()
		done := !s.scanning
		s.mu.Unlock()
		if done {
			break
		}
		time.Sleep(time.Millisecond)
	}
	w = httptest.NewRecorder()
	s.Handler().ServeHTTP(w, httptest.NewRequest("GET", "/api/status", nil))
	var status map[string]any
	if err = json.Unmarshal(w.Body.Bytes(), &status); err != nil {
		t.Fatal(err)
	}
	if status["scanning"] != false || status["needs_rebuild"] != false || !strings.Contains(status["last_error"].(string), "test scan failed") {
		t.Fatalf("status=%v", status)
	}
	progress := status["scan_progress"].(map[string]any)
	if progress["1"].(map[string]any)["status"] != "completed" || progress["2"].(map[string]any)["status"] != "failed" {
		t.Fatalf("scan progress=%v", progress)
	}
	if status["jellyfin_state"] != "not_configured" || status["jellyfin_configured"] != false || status["strm_files_known"] != true {
		t.Fatalf("sync state=%v", status)
	}
}

func TestRebuildSeparatesMediaTreeFromJellyfinNotification(t *testing.T) {
	dir := t.TempDir()
	lib, err := store.Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	if err = lib.UpsertChannel(store.Channel{ID: 1, Ref: "@a", Title: "a"}); err != nil {
		t.Fatal(err)
	}
	if err = lib.SetItems(1, []store.Item{{ChannelID: 1, MessageID: 10, Name: "片段.mp4", Size: 12}}); err != nil {
		t.Fatal(err)
	}
	var s *Server
	s = NewServer(Deps{
		Store:    lib,
		MediaDir: filepath.Join(dir, "media"),
		StrmBase: "http://host",
		NotifyJellyfin: func(context.Context) error {
			w := httptest.NewRecorder()
			s.Handler().ServeHTTP(w, httptest.NewRequest("GET", "/api/status", nil))
			var status map[string]any
			if err := json.Unmarshal(w.Body.Bytes(), &status); err != nil {
				t.Fatal(err)
			}
			if status["rebuilding"] != false || status["jellyfin_state"] != "notifying" {
				t.Fatalf("notification phase=%v", status)
			}
			return errors.New("offline")
		},
	})
	w := httptest.NewRecorder()
	s.Handler().ServeHTTP(w, httptest.NewRequest("POST", "/api/rebuild", strings.NewReader("{}")))
	if w.Code != 200 {
		t.Fatalf("rebuild status=%d body=%s", w.Code, w.Body.String())
	}
	var result map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &result); err != nil {
		t.Fatal(err)
	}
	if result["strm_files"].(float64) != 1 || result["warning"] == nil {
		t.Fatalf("rebuild result=%v", result)
	}
	w = httptest.NewRecorder()
	s.Handler().ServeHTTP(w, httptest.NewRequest("GET", "/api/status", nil))
	var status map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &status); err != nil {
		t.Fatal(err)
	}
	if status["jellyfin_state"] != "failed" || status["jellyfin_updated_at"] == "" || status["needs_rebuild"] != false {
		t.Fatalf("final sync state=%v", status)
	}
}

func TestCrossSiteMutationRejected(t *testing.T) {
	lib, _ := store.Open(t.TempDir())
	s := NewServer(Deps{Store: lib})
	r := httptest.NewRequest("POST", "http://local/api/scan", nil)
	r.Header.Set("Origin", "https://other.example")
	w := httptest.NewRecorder()
	s.Handler().ServeHTTP(w, r)
	if w.Code != 403 {
		t.Fatal(w.Code)
	}
}
