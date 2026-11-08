package jellyfin

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestRefreshAndRejectRedirect(t *testing.T) {
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "POST" || r.URL.Path != "/Library/Refresh" || r.Header.Get("Authorization") == "" {
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
