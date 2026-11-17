package web

import (
	"context"
	"encoding/json"
	"errors"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"tgproxy/internal/logbus"
	"tgproxy/internal/store"
	"tgproxy/internal/telegram"
	"time"
)

func TestRemoveChannelMediaDirConstrainedToMediaRoot(t *testing.T) {
	root := t.TempDir()
	outside := t.TempDir()
	if err := os.WriteFile(filepath.Join(outside, "keep"), []byte("keep"), 0600); err != nil {
		t.Fatal(err)
	}
	for _, dir := range []string{"../" + filepath.Base(outside), filepath.Join(string(filepath.Separator), "tmp", "outside"), "."} {
		if err := removeChannelMediaDir(root, dir); err == nil {
			t.Fatalf("accepted unsafe directory %q", dir)
		}
	}
	link := filepath.Join(root, "linked")
	if err := os.Symlink(outside, link); err != nil {
		t.Fatal(err)
	}
	if err := removeChannelMediaDir(root, "linked"); err == nil {
		t.Fatal("accepted symlink directory")
	}
	if _, err := os.Stat(filepath.Join(outside, "keep")); err != nil {
		t.Fatalf("outside data was affected: %v", err)
	}
}

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
	if status["media_server_state"] != "not_configured" || status["media_server_configured"] != false || status["strm_files_known"] != true {
		t.Fatalf("sync state=%v", status)
	}
}

func TestRebuildSeparatesMediaTreeFromMediaServerNotification(t *testing.T) {
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
		Store:       lib,
		MediaDir:    filepath.Join(dir, "media"),
		StrmBase:    "http://host",
		MediaServer: func() (string, string) { return "http://emby", "key" },
		TestMediaServer: func(context.Context, string, string) error {
			w := httptest.NewRecorder()
			s.Handler().ServeHTTP(w, httptest.NewRequest("GET", "/api/status", nil))
			var status map[string]any
			if err := json.Unmarshal(w.Body.Bytes(), &status); err != nil {
				t.Fatal(err)
			}
			if status["rebuilding"] != false || status["media_server_state"] != "notifying" {
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
	if status["media_server_state"] != "failed" || status["media_server_updated_at"] == "" || status["needs_rebuild"] != false {
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

func TestMediaServerConfigAPI(t *testing.T) {
	lib, _ := store.Open(t.TempDir())
	var savedURL, savedKey string
	s := NewServer(Deps{
		Store: lib,
		MediaServer: func() (string, string) {
			return savedURL, savedKey
		},
		SaveMediaServer: func(url, key string) error {
			savedURL, savedKey = url, key
			return nil
		},
		TestMediaServer: func(context.Context, string, string) error { return nil },
	})
	body := strings.NewReader(`{"url":"http://emby.local:8096","api_key":"secret"}`)
	w := httptest.NewRecorder()
	s.Handler().ServeHTTP(w, httptest.NewRequest("POST", "/api/media-server", body))
	if w.Code != 200 {
		t.Fatalf("save status=%d body=%s", w.Code, w.Body.String())
	}
	var result map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &result); err != nil {
		t.Fatal(err)
	}
	if result["configured"] != true || result["tested"] != true {
		t.Fatalf("save result=%v", result)
	}
	w = httptest.NewRecorder()
	s.Handler().ServeHTTP(w, httptest.NewRequest("GET", "/api/media-server", nil))
	var got map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
		t.Fatal(err)
	}
	if got["url"] != "http://emby.local:8096" || got["configured"] != true {
		t.Fatalf("get result=%v", got)
	}
}

func TestLogsAPI(t *testing.T) {
	lib, _ := store.Open(t.TempDir())
	logs := logbus.New(10)
	logs.Add("info", "hello %s", "world")
	s := NewServer(Deps{Store: lib, Logs: logs})
	w := httptest.NewRecorder()
	s.Handler().ServeHTTP(w, httptest.NewRequest("GET", "/api/logs?after=0", nil))
	var got struct {
		Entries []logbus.Entry `json:"entries"`
		Next    int64          `json:"next"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
		t.Fatal(err)
	}
	if len(got.Entries) != 1 || got.Entries[0].Message != "hello world" || got.Next != 1 {
		t.Fatalf("logs=%+v", got)
	}
	w = httptest.NewRecorder()
	s.Handler().ServeHTTP(w, httptest.NewRequest("GET", "/api/logs?after=1", nil))
	if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
		t.Fatal(err)
	}
	if len(got.Entries) != 0 || got.Next != 1 {
		t.Fatalf("after logs=%+v", got)
	}
}
