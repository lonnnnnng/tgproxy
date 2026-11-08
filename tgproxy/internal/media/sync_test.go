package media

import (
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

func TestClassifyPreservesSourcesAndGroupsEpisodeFormats(t *testing.T) {
	items := []Item{{1, 1, "剧名 01.mp4", 1}, {2, 2, "剧名(02).mkv", 1}, {3, 3, "[来源]剧名 第03集 @source.mp4", 1}, {4, 4, "预告.mp4", 1}, {4, 5, "预告.mp4", 1}, {5, 6, "_.mp4", 1}, {6, 7, "剧名 S00E01.mp4", 1}, {7, 8, "剧名 第2季 第01集.mp4", 1}}
	groups := Classify(items)
	files, n, err := planTree(groups, "http://host")
	if err != nil || n != len(items) {
		t.Fatalf("n=%d err=%v", n, err)
	}
	strms := 0
	for p := range files {
		if strings.HasSuffix(p, ".strm") {
			strms++
		}
		if strings.HasPrefix(p, "_/") {
			t.Fatal(p)
		}
	}
	if strms != len(items) {
		t.Fatal("sources were overwritten")
	}
	for i, j := 0, len(items)-1; i < j; i, j = i+1, j-1 {
		items[i], items[j] = items[j], items[i]
	}
	again, _, _ := planTree(Classify(items), "http://host")
	if !reflect.DeepEqual(files, again) {
		t.Fatal("input order changes media tree")
	}
	for _, path := range []string{"剧名/Season 01/剧名 S01E01.strm", "剧名/Season 01/剧名 S01E02.strm", "剧名/Season 01/剧名 S01E03.strm", "剧名/Season 00/剧名 S00E01.strm", "剧名/Season 02/剧名 S02E01.strm"} {
		if _, ok := files[path]; !ok {
			t.Fatalf("missing %s: %+v", path, files)
		}
	}
}

func TestTreeSyncBacksUpStaleFilesAndKeepsRoot(t *testing.T) {
	root := filepath.Join(t.TempDir(), "media")
	items := []Item{{1, 1, "剧名 S01E01.mp4", 1}, {2, 2, "剧名 S01E01.mp4", 2}}
	if n, err := BuildTree(root, Classify(items), "http://host", 1); err != nil || n != 2 {
		t.Fatalf("%d %v", n, err)
	}
	before, _ := os.Stat(root)
	if err := os.WriteFile(filepath.Join(root, "user-note.txt"), []byte("keep"), 0600); err != nil {
		t.Fatal(err)
	}
	if n, err := BuildTree(root, nil, "http://host", 2); err != nil || n != 0 {
		t.Fatalf("%d %v", n, err)
	}
	after, _ := os.Stat(root)
	if !os.SameFile(before, after) {
		t.Fatal("root inode changed")
	}
	if _, err := os.Stat(filepath.Join(root, "user-note.txt")); err != nil {
		t.Fatal("unrelated file changed")
	}
	strms, _ := filepath.Glob(root + ".backups/*/剧名/Season 01/*.strm")
	if len(strms) != 2 {
		t.Fatalf("backup files=%v", strms)
	}
	if rev, ok := TreeRevision(root); !ok || rev != 2 {
		t.Fatal("revision not saved")
	}
}

func TestTreeRejectsSymlinksAndForeignConflicts(t *testing.T) {
	root := t.TempDir()
	outside := t.TempDir()
	if err := os.Symlink(outside, filepath.Join(root, "剧名")); err != nil {
		t.Fatal(err)
	}
	g := Classify([]Item{{1, 1, "剧名 S01E01.mp4", 1}})
	if _, err := BuildTree(root, g, "http://host"); err == nil {
		t.Fatal("followed symlink")
	}
	entries, _ := os.ReadDir(outside)
	if len(entries) != 0 {
		t.Fatal("wrote outside root")
	}
	root = t.TempDir()
	target := filepath.Join(root, "剧名", "Season 01", "剧名 S01E01.strm")
	if err := os.MkdirAll(filepath.Dir(target), 0755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(target, []byte("http://unrelated/video"), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := BuildTree(root, g, "http://host"); err == nil {
		t.Fatal("overwrote foreign stream")
	}
	b, _ := os.ReadFile(target)
	if string(b) != "http://unrelated/video" {
		t.Fatal("foreign content changed")
	}
}

func TestResolutionIsNotAnEpisodeAndSeasonCaseIsStable(t *testing.T) {
	for _, name := range []string{"电影 1920x1080.mkv", "电影 1080x1920.mp4", "电影 E02Trailer.mp4"} {
		if ep, ok := ParseEpisode(name); ok {
			t.Fatalf("%s parsed as %+v", name, ep)
		}
	}
	groups := Classify([]Item{{1, 1, "Show S01E01.mp4", 1}, {2, 2, "show S02E01.mp4", 1}})
	root := t.TempDir()
	for i := 0; i < 2; i++ {
		if n, err := BuildTree(root, groups, "http://host"); n != 2 || err != nil {
			t.Fatalf("build %d: n=%d err=%v", i, n, err)
		}
	}
	shows, err := os.ReadDir(root)
	if err != nil {
		t.Fatal(err)
	}
	dirs := 0
	for _, d := range shows {
		if d.IsDir() {
			dirs++
		}
	}
	if dirs != 1 {
		t.Fatalf("same show split by case: %d", dirs)
	}
}
