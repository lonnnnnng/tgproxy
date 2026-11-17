package mediaserver

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestRefreshAndRejectRedirect(t *testing.T) {
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "POST" || r.URL.Path != "/Library/Refresh" || r.Header.Get("X-Emby-Token") != "test-key" || r.URL.RawQuery != "" {
			t.Error("invalid refresh request")
		}
		w.WriteHeader(204)
	}))
	defer s.Close()
	if err := Refresh(context.Background(), s.URL, "test-key"); err != nil {
		t.Fatal(err)
	}
	r := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { http.Redirect(w, r, s.URL, 302) }))
	defer r.Close()
	if err := Refresh(context.Background(), r.URL, "test-key"); err == nil {
		t.Fatal("followed redirect with credentials")
	}
}

func TestLibraryRequestsKeepAPIKeyOutOfURL(t *testing.T) {
	var created, configured, deleted bool
	libs := []Library{}
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if got := r.Header.Get("X-Emby-Token"); got != "test-key" {
			t.Errorf("token header = %q", got)
		}
		if strings.Contains(r.URL.RawQuery, "test-key") || strings.Contains(r.URL.RawQuery, "api_key") {
			t.Errorf("credential found in query: %q", r.URL.RawQuery)
		}
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/Library/VirtualFolders":
			if !created {
				w.Header().Set("Content-Type", "application/json")
				_ = json.NewEncoder(w).Encode(libs)
				return
			}
			_ = json.NewEncoder(w).Encode(libs)
		case r.Method == http.MethodPost && r.URL.Path == "/Library/VirtualFolders":
			created = true
			libs = []Library{{Name: "Test", ItemID: "1", Locations: []string{"/media"}}}
			w.WriteHeader(http.StatusNoContent)
		case r.Method == http.MethodPost && r.URL.Path == "/Library/VirtualFolders/LibraryOptions":
			configured = true
			w.WriteHeader(http.StatusNoContent)
		case r.Method == http.MethodPost && r.URL.Path == "/Library/VirtualFolders/Delete":
			deleted = true
			w.WriteHeader(http.StatusNoContent)
		default:
			http.NotFound(w, r)
		}
	}))
	defer s.Close()

	if err := EnsureLibrary(context.Background(), s.URL, "test-key", "Test", "/media"); err != nil {
		t.Fatal(err)
	}
	if err := DeleteLibraryByPath(context.Background(), s.URL, "test-key", "/media"); err != nil {
		t.Fatal(err)
	}
	if !created || !configured || !deleted {
		t.Fatalf("request coverage created=%t configured=%t deleted=%t", created, configured, deleted)
	}
}
