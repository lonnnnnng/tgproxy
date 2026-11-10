package mobilecore

import (
	"bytes"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"testing"
)

// long: 同大小的合成素材替换后应读到新字节，避免测试诊断使用旧缓存误报。
func TestValidationMediaReplacementUsesNewCacheIdentity(t *testing.T) {
	e := testEngine(t)
	file := filepath.Join(t.TempDir(), "sample.mp4")
	read := func(value byte) []byte {
		if err := os.WriteFile(file, bytes.Repeat([]byte{value}, 4096), 0600); err != nil {
			t.Fatal(err)
		}
		url, err := e.MountValidationMedia(file)
		if err != nil {
			t.Fatal(err)
		}
		req, _ := http.NewRequest("GET", url, nil)
		req.Header.Set("Authorization", "Bearer "+e.Token())
		req.Header.Set("Range", "bytes=0-31")
		res, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		got, err := io.ReadAll(res.Body)
		if err != nil || res.StatusCode != 206 {
			t.Fatal(res.StatusCode, err)
		}
		return got
	}
	first := read('A')
	second := read('B')
	t.Logf("initial_byte=%q replacement_byte=%q", first[0], second[0])
	if !bytes.Equal(second, bytes.Repeat([]byte{'B'}, 32)) {
		t.Fatal("replacement still returns previous fixture bytes")
	}
}

func TestCancelledScanStaysCancelledUntilNewTask(t *testing.T) {
	e := testEngine(t)
	e.CancelScan()
	if _, err := e.ScanPage(1, 0, 0); err == nil || err.Error() != "操作已取消" {
		t.Fatal(err)
	}
	e.PrepareScan()
	if _, err := e.ScanPage(1, 0, 0); err == nil || err.Error() == "操作已取消" {
		t.Fatal(err)
	}
}
func TestRegisteredMetadataHasBoundedLifetime(t *testing.T) {
	e := testEngine(t)
	for i := 1; i <= 500; i++ {
		if err := e.RegisterVideo(fmt.Sprintf(`{"key":"1_%d","channel_id":1,"message_id":%d,"size":100}`, i, i)); err != nil {
			t.Fatal(err)
		}
	}
	if len(e.items) > 128 {
		t.Fatalf("unbounded metadata: %d", len(e.items))
	}
	if _, err := e.PlaybackURL("1_500"); err != nil {
		t.Fatal(err)
	}
}
