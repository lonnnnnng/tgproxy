package media

import (
	"encoding/json"
	"os"
	"reflect"
	"strings"
	"testing"
	"tgproxy/internal/store"
)

func TestLibraryFixture(t *testing.T) {
	path := os.Getenv("TGPROXY_LIBRARY_FIXTURE")
	if path == "" {
		t.Skip("可选：使用私有频道索引副本验证真实命名，不将索引加入仓库")
	}
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var fixture struct {
		Items []store.Item `json:"items"`
	}
	if err = json.Unmarshal(b, &fixture); err != nil {
		t.Fatal(err)
	}
	var items []Item
	keys := map[string]bool{}
	for _, it := range fixture.Items {
		items = append(items, Item{it.ChannelID, it.MessageID, it.Name, it.Size})
		if IsVideo(it.Name) {
			keys[Key(it.ChannelID, it.MessageID)] = true
		}
	}
	groups := Classify(items)
	files, n, err := planTree(groups, "http://test-source")
	if err != nil || n != len(keys) {
		t.Fatalf("input videos=%d exported=%d err=%v", len(keys), n, err)
	}
	paths := 0
	for path := range files {
		if strings.HasSuffix(path, ".strm") {
			paths++
		}
	}
	if paths != n {
		t.Fatal("output collisions")
	}
	for i, j := 0, len(items)-1; i < j; i, j = i+1, j-1 {
		items[i], items[j] = items[j], items[i]
	}
	reversed, _, err := planTree(Classify(items), "http://test-source")
	if err != nil || !reflect.DeepEqual(files, reversed) {
		t.Fatal("non-deterministic media tree")
	}
	root := t.TempDir()
	for i := 0; i < 2; i++ {
		if count, err := BuildTree(root, groups, "http://test-source"); err != nil || count != n {
			t.Fatalf("real fixture build=%d count=%d err=%v", i, count, err)
		}
	}
	t.Logf("%d indexed files -> %d videos, %d groups, %d unique STRM paths", len(items), len(keys), len(groups), paths)
}
