package media

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestParseEpisode(t *testing.T) {
	cases := []struct {
		name    string
		season  int
		episode int
	}{
		{"庆余年.S01E02.1080p.mkv", 1, 2},
		{"show s03e45.mp4", 3, 45},
		{"Friends.2x05.The.One.mp4", 2, 5},
		{"琅琊榜 第12集.mp4", 1, 12},
		{"誓言 第3话.mp4", 1, 3},
		{"动画 EP08.mp4", 1, 8},
		{"剧名【第05集】.mkv", 1, 5},
		{"剧名(07).mkv", 1, 7},
		{"剧名 [12].mp4", 1, 12},
		{"09.剧名.mp4", 1, 9},
		{"剧名.E07.mkv", 1, 7},
	}
	for _, c := range cases {
		ep, ok := ParseEpisode(c.name)
		if !ok {
			t.Errorf("ParseEpisode(%q) = not found, want S%02dE%02d", c.name, c.season, c.episode)
			continue
		}
		if ep.Season != c.season || ep.Episode != c.episode {
			t.Errorf("ParseEpisode(%q) = S%02dE%02d, want S%02dE%02d", c.name, ep.Season, ep.Episode, c.season, c.episode)
		}
	}
	// non-episode
	if _, ok := ParseEpisode("movie.2024.1080p.mkv"); ok {
		t.Error("movie should not parse as episode")
	}
}

func TestCleanTitle(t *testing.T) {
	cases := map[string]string{
		"庆余年.S01E02.1080p.mkv":           "庆余年",
		"Show.Name.S02E03.720p.x265.mp4": "Show Name",
		"琅琊榜 第12集 国语.mkv":                "琅琊榜",
		"Bleach EP08.mp4":                "Bleach",
		"剧名【第05集】.mkv":                   "剧名",
		"Inception (2010).mkv":           "Inception",
	}
	for in, want := range cases {
		if got := CleanTitle(in); got != want {
			t.Errorf("CleanTitle(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestClassify(t *testing.T) {
	items := []Item{
		{ChannelID: 100, MessageID: 1, Name: "庆余年.S01E01.mkv", Size: 1},
		{ChannelID: 100, MessageID: 2, Name: "庆余年.S01E02.mkv", Size: 1},
		{ChannelID: 200, MessageID: 5, Name: "庆余年.S01E03.mp4", Size: 1}, // cross-channel merge
		{ChannelID: 100, MessageID: 3, Name: "其他剧 第01集.mkv", Size: 1},
		{ChannelID: 100, MessageID: 4, Name: "电影.mp4", Size: 1},
		{ChannelID: 100, MessageID: 6, Name: "note.txt", Size: 1}, // ignored
	}
	groups := Classify(items)
	if len(groups) != 3 {
		t.Fatalf("got %d groups, want 3: %+v", len(groups), groups)
	}
	// find 庆余年 group
	var qyn *Group
	for i := range groups {
		if groups[i].Title == "庆余年" {
			qyn = &groups[i]
		}
	}
	if qyn == nil {
		t.Fatalf("missing 庆余年 group: %+v", groups)
	}
	if len(qyn.Episodes) != 3 {
		t.Errorf("庆余年 has %d episodes, want 3 (cross-channel merge)", len(qyn.Episodes))
	}
	if qyn.Episodes[0].Episode != 1 || qyn.Episodes[2].Episode != 3 {
		t.Errorf("episodes not sorted: %+v", qyn.Episodes)
	}
	if qyn.Episodes[2].StreamKey != "200_5" {
		t.Errorf("wrong stream key: %s", qyn.Episodes[2].StreamKey)
	}
}

func TestBuildTree(t *testing.T) {
	root := t.TempDir()
	groups := []Group{
		{Title: "Test Show", Season: 1, Episodes: []EpisodeFile{
			{Episode: 1, StreamKey: "100_1", Name: "a.mkv", Size: 1},
			{Episode: 2, StreamKey: "100_2", Name: "b.mkv", Size: 1},
		}},
	}
	n, err := BuildTree(root, groups, "http://192.168.1.2:8080")
	if err != nil {
		t.Fatal(err)
	}
	if n != 2 {
		t.Fatalf("wrote %d strm files, want 2", n)
	}

	showDir := filepath.Join(root, "Test Show")
	if _, err := os.Stat(filepath.Join(showDir, "tvshow.nfo")); err != nil {
		t.Errorf("missing tvshow.nfo: %v", err)
	}
	s1 := filepath.Join(showDir, "Season 01")
	data, err := os.ReadFile(filepath.Join(s1, "Test Show S01E01.strm"))
	if err != nil {
		t.Fatal(err)
	}
	if got := strings.TrimSpace(string(data)); got != "http://192.168.1.2:8080/stream/100_1" {
		t.Errorf("strm content = %q", got)
	}
}

func TestSanitizeDirName(t *testing.T) {
	if got := SanitizeDirName(`a/b\c:d*e?f"g<h>i|j`); strings.ContainsAny(got, `/\:*?"<>|`) {
		t.Errorf("unsanitized: %q", got)
	}
	if got := SanitizeDirName("bad."); got != "bad" {
		t.Errorf("trailing dot: %q", got)
	}
}
