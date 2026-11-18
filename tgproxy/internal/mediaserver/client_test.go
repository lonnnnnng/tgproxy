package mediaserver

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
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

func TestDecodeLibraryOptionsWithNumericItemID(t *testing.T) {
	var libs []Library
	err := json.Unmarshal([]byte(`[{"Name":"Anime","ItemId":54169,"Locations":["/media/anime"],"LibraryOptions":{"ContentType":"tvshows"}}]`), &libs)
	if err != nil || len(libs) != 1 || libs[0].ItemID != fmt.Sprint(54169) {
		t.Fatalf("libraries=%+v err=%v", libs, err)
	}
}

func TestRequestKeepsContextUntilResponseBodyCloses(t *testing.T) {
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		w.(http.Flusher).Flush()
		time.Sleep(30 * time.Millisecond)
		_, _ = w.Write([]byte("response body"))
	}))
	defer s.Close()
	resp, err := request(context.Background(), http.MethodGet, s.URL, "key", "/slow", nil)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(resp.Body)
	if err != nil || string(body) != "response body" {
		t.Fatalf("body=%q err=%v", body, err)
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

func TestRefreshLibraryTargetsOnlyMatchingPath(t *testing.T) {
	var gotPath string
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Emby-Token") != "test-key" {
			t.Errorf("missing API token")
		}
		if r.Method == http.MethodGet && r.URL.Path == "/Library/VirtualFolders" {
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write([]byte(`[{"Name":"Anime","ItemId":"42","Locations":["/media/anime"]},{"Name":"Other","ItemId":"43","Locations":["/media/other"]}]`))
			return
		}
		gotPath = r.URL.Path
		if r.Method != http.MethodPost || r.URL.Path != "/Items/42/Refresh" || r.URL.Query().Get("Recursive") != "true" || r.URL.Query().Get("ReplaceAllImages") != "false" || r.URL.Query().Get("ReplaceAllMetadata") != "false" {
			t.Errorf("unexpected refresh request %s %s", r.Method, r.URL.String())
		}
		w.WriteHeader(http.StatusNoContent)
	}))
	defer s.Close()
	if err := RefreshLibrary(context.Background(), s.URL, "test-key", "/media/anime"); err != nil {
		t.Fatal(err)
	}
	if gotPath != "/Items/42/Refresh" {
		t.Fatalf("refresh path = %q", gotPath)
	}
}

func TestSeriesWithPostersReturnsOnlyImagePaths(t *testing.T) {
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Emby-Token") != "test-key" {
			t.Errorf("missing auth token")
		}
		switch r.URL.Path {
		case "/Library/VirtualFolders":
			_, _ = w.Write([]byte(`[{"Name":"Anime","ItemId":"42","Locations":["/media/anime"]}]`))
		case "/Items":
			if r.URL.Query().Get("ParentId") != "42" || r.URL.Query().Get("IncludeItemTypes") != "Series" {
				t.Errorf("unexpected query %s", r.URL.RawQuery)
			}
			_, _ = w.Write([]byte(`{"Items":[{"Path":"/media/anime/A","ImageTags":{"Primary":"tag"}},{"Path":"/media/anime/B","ImageTags":{}},{"Path":"/media/anime/C"}]}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer s.Close()
	withPoster, err := SeriesWithPosters(context.Background(), s.URL, "test-key", "/media/anime")
	if err != nil {
		t.Fatal(err)
	}
	if len(withPoster) != 1 || !withPoster["/media/anime/A"] || withPoster["/media/anime/B"] {
		t.Fatalf("withPoster=%v", withPoster)
	}
}
