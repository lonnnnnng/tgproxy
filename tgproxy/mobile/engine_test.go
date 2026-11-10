package mobilecore

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"testing"
)

func testEngine(t *testing.T) *Engine {
	t.Helper()
	e, err := NewEngine(0, "", "")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(e.Close)
	return e
}

func TestLocalStreamRequiresTokenAndPreservesRanges(t *testing.T) {
	e := testEngine(t)
	data := bytes.Repeat([]byte("0123456789abcdef"), 70000)
	file := filepath.Join(t.TempDir(), "sample.mp4")
	if err := os.WriteFile(file, data, 0o600); err != nil {
		t.Fatal(err)
	}
	url, err := e.MountValidationMedia(file)
	if err != nil {
		t.Fatal(err)
	}
	response, err := http.Get(url)
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if response.StatusCode != 401 {
		t.Fatal("unauthorized local caller could read media")
	}
	for _, offset := range []int{0, 524280, 900000} {
		req, _ := http.NewRequest("GET", url, nil)
		req.Header.Set("Authorization", "Bearer "+e.Token())
		req.Header.Set("Range", fmt.Sprintf("bytes=%d-%d", offset, offset+4095))
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		got, err := io.ReadAll(resp.Body)
		resp.Body.Close()
		if err != nil || resp.StatusCode != 206 || !bytes.Equal(got, data[offset:offset+4096]) {
			t.Fatalf("range failed: %d %v", resp.StatusCode, err)
		}
	}
	var state map[string]any
	if json.Unmarshal([]byte(e.Status()), &state) != nil || state["range_requests"].(float64) != 3 {
		t.Fatal(e.Status())
	}
	if bytes.Contains([]byte(e.Status()), []byte(e.Token())) {
		t.Fatal("status exposed stream credential")
	}
}

func TestRestoreIndexRejectsForgedKeyAndStopsAtClose(t *testing.T) {
	e := testEngine(t)
	if err := e.RestoreIndex(`[{"key":"../file","channel_id":1,"message_id":2}]`); err == nil {
		t.Fatal("accepted forged index key")
	}
	if _, err := e.PlaybackURL("1_2"); err == nil {
		t.Fatal("served unindexed media")
	}
	if err := e.RestoreIndex(`[{"key":"1_2","channel_id":1,"message_id":2,"size":100}]`); err != nil {
		t.Fatal(err)
	}
	if _, err := e.PlaybackURL("1_2"); err != nil {
		t.Fatal(err)
	}
	e.Close()
	if _, err := e.PlaybackURL("1_2"); err == nil {
		t.Fatal("closed engine supplied playback URL")
	}
}

func TestInvalidSessionIsRejectedBeforeConnecting(t *testing.T) {
	if engine, err := NewEngine(0, "", "not base64"); err == nil {
		engine.Close()
		t.Fatal("accepted malformed session")
	}
}

func TestOptionalCredentialsRequireAValidPair(t *testing.T) {
	for _, pair := range []struct {
		id   int64
		hash string
	}{
		{0, "0123456789abcdef0123456789abcdef"}, {1, ""}, {2147483648, "0123456789abcdef0123456789abcdef"}, {1, "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"},
	} {
		if engine, err := NewEngine(pair.id, pair.hash, ""); err == nil {
			engine.Close()
			t.Fatal("accepted invalid credentials")
		}
	}
	engine, err := NewEngine(1234, "0123456789abcdef0123456789abcdef", "")
	if err != nil {
		t.Fatal(err)
	}
	engine.Close()
}
