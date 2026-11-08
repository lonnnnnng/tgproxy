package media

import (
	"fmt"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
)

// Episode is an episode number extracted from a file name.
type Episode struct {
	Season  int
	Episode int
}

var seasonEpisodePatterns = []*regexp.Regexp{
	// S01E02 / s01e02, S01E02E03 (take first)
	regexp.MustCompile(`(?i)[\s._-]*S(\d{1,2})\s*E(\d{1,3})`),
	// 1x02
	regexp.MustCompile(`(?i)[\s._-]+(\d{1,2})x(\d{1,3})\b`),
	// 第02集 / 第2集
	regexp.MustCompile(`第\s*(\d{1,4})\s*[集话話回]`),
	// EP02 / E02 / ep02 (standalone)
	regexp.MustCompile(`(?i)[\s._-]+(?:EP?)(\d{1,4})\b`),
	// 【01】 / 【第01集】
	regexp.MustCompile(`【\s*第?\s*(\d{1,4})\s*[集话話]?\s*】`),
	// (01) or [01] at word boundary
	regexp.MustCompile(`[\(\[](\d{1,4})[\)\]]`),
	// 01.mkv style: leading number in filename
	regexp.MustCompile(`(?:^|[\s._-])(\d{2,4})(?:[\s._-]|$)`),
}

// ParseEpisode extracts season/episode info from a media file name.
// Returns season=1 when unspecified.
func ParseEpisode(name string) (Episode, bool) {
	base := strings.TrimSuffix(name, filepath.Ext(name))

	// explicit season+episode
	re := regexp.MustCompile(`(?i)[\s._-]*S(\d{1,2})\s*E(\d{1,3})`)
	if m := re.FindStringSubmatch(base); m != nil {
		s, _ := strconv.Atoi(m[1])
		e, _ := strconv.Atoi(m[2])
		return Episode{Season: normSeason(s), Episode: e}, true
	}

	// 1x02
	re = regexp.MustCompile(`(?:^|[\s._-])(\d{1,2})x(\d{1,3})(?:$|[\s._-])`)
	if m := re.FindStringSubmatch(base); m != nil {
		s, _ := strconv.Atoi(m[1])
		e, _ := strconv.Atoi(m[2])
		return Episode{Season: normSeason(s), Episode: e}, true
	}

	for _, re := range []*regexp.Regexp{
		regexp.MustCompile(`第\s*(\d{1,4})\s*[集话話回]`),
		regexp.MustCompile(`(?i)(?:^|[\s._\-\[])(?:EP?)(\d{1,4})(?:$|[\s._\-\]])`),
		regexp.MustCompile(`【\s*第?\s*(\d{1,4})\s*[集话話]?\s*】`),
		regexp.MustCompile(`(?:^|[\s._\-\[\(])(\d{1,4})(?:$|[\s._\-\]\)])`),
	} {
		if m := re.FindStringSubmatch(base); m != nil {
			e, err := strconv.Atoi(m[1])
			// skip years and common quality/codec numbers
			if err == nil && e > 0 && e < 3000 && !isNoiseNumber(e, base, m[1]) {
				return Episode{Season: 1, Episode: e}, true
			}
		}
	}
	return Episode{}, false
}

// isNoiseNumber rejects year-like (1900-2099) and resolution-like (480/720/1080/2160) numbers.
func isNoiseNumber(e int, base, raw string) bool {
	if e >= 1900 && e <= 2100 {
		return true
	}
	switch e {
	case 480, 576, 720, 1080, 2160, 4320:
		return true
	}
	return false
}

func normSeason(s int) int {
	if s == 0 {
		return 1
	}
	return s
}

// CleanTitle derives the show title from a file name by stripping
// episode markers, quality tags, and trailing junk.
func CleanTitle(name string) string {
	base := strings.TrimSuffix(name, filepath.Ext(name))

	// strip common noise tags
	noise := []*regexp.Regexp{
		regexp.MustCompile(`(?i)[\s._\-\[]*(1080p|720p|2160p|4k|2k|hdr|dv|x264|x265|h\.?264|h\.?265|hevc|avc)[\s._\-\]]*`),
		regexp.MustCompile(`(?i)[\s._\-\[]*(国粤双语|国语|粤语|中字|双语|内封|外挂|简繁|字幕组|无水印|高清|完整版|加长版|未删减)[\s._\-\]]*`),
		regexp.MustCompile(`\((?:\d{4})\)\s*$`), // trailing (2024)
	}
	for _, re := range noise {
		base = re.ReplaceAllString(base, " ")
	}

	// cut at season/episode marker
	cut := regexp.MustCompile(`(?i)[\s._-]*S\d{1,2}\s*E\d{1,3}`)
	if loc := cut.FindStringIndex(base); loc != nil {
		base = base[:loc[0]]
	}
	cut = regexp.MustCompile(`(?:^|[\s._-])\d{1,2}x\d{1,3}(?:$|[\s._-])`)
	if loc := cut.FindStringIndex(base); loc != nil {
		base = base[:loc[0]]
	}
	for _, re := range []*regexp.Regexp{
		regexp.MustCompile(`【.*?】`), // strip bracketed tags first
		regexp.MustCompile(`第\s*\d{1,4}\s*[集话話回].*$`),
		regexp.MustCompile(`(?i)(?:^|[\s._\-\[])EP?\d{1,4}(?:$|[\s._\-\]])`),
	} {
		if loc := re.FindStringIndex(base); loc != nil && loc[0] > 0 {
			base = base[:loc[0]]
		} else if loc != nil {
			base = base[loc[1]:]
		}
	}

	// collapse dots/spaces/punctuation runs to single space
	base = strings.TrimSpace(base)
	base = strings.Trim(base, "._- ")
	base = strings.Join(strings.Fields(base), " ")
	base = strings.NewReplacer(".", " ", "_", " ").Replace(base)
	base = strings.Join(strings.Fields(base), " ")
	return strings.TrimSpace(base)
}

// Group is a classified show: one folder in the media tree.
type Group struct {
	Title    string
	Season   int
	Episodes []EpisodeFile
}

// EpisodeFile is one episode entry pointing back to its source item.
type EpisodeFile struct {
	Episode   int
	StreamKey string // file key for tgproxy stream URL
	Name      string
	Size      int64
}

// Key builds the stream key for a channel/message item.
func Key(channelID int64, msgID int) string {
	return fmt.Sprintf("%d_%d", channelID, msgID)
}

// Classify groups scanned items into shows/seasons.
// Same-name files with different episodes are merged into one group.
func Classify(items []Item) []Group {
	type key struct{ title string; season int }
	groups := map[key]*Group{}

	for _, it := range items {
		if !IsVideo(it.Name) {
			continue
		}
		ep, ok := ParseEpisode(it.Name)
		if !ok {
			// no episode marker: treat the whole name as its own single-episode title
			ep = Episode{Season: 1, Episode: 0}
		}
		title := CleanTitle(it.Name)
		if title == "" {
			title = it.Name
		}
		if ep.Episode == 0 {
			// standalone item (movie): strip noise tags but keep distinct name
			title = CleanTitle(it.Name)
			if title == "" {
				title = strings.TrimSuffix(it.Name, filepath.Ext(it.Name))
			}
		}

		k := key{title: normalizeTitle(title), season: ep.Season}
		g, exists := groups[k]
		if !exists {
			g = &Group{Title: title, Season: ep.Season}
			groups[k] = g
		}
		g.Episodes = append(g.Episodes, EpisodeFile{
			Episode:   ep.Episode,
			StreamKey: Key(it.ChannelID, it.MessageID),
			Name:      it.Name,
			Size:      it.Size,
		})
	}

	out := make([]Group, 0, len(groups))
	for _, g := range groups {
		sort.Slice(g.Episodes, func(i, j int) bool { return g.Episodes[i].Episode < g.Episodes[j].Episode })
		out = append(out, *g)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Title < out[j].Title })
	return out
}

func normalizeTitle(t string) string {
	return strings.ToLower(strings.Join(strings.Fields(t), ""))
}

// Item is the media-tree input; keeps telegram package decoupled.
type Item struct {
	ChannelID int64
	MessageID int
	Name      string
	Size      int64
}

// IsVideo checks file extension.
func IsVideo(name string) bool {
	switch strings.ToLower(strings.TrimPrefix(filepath.Ext(name), ".")) {
	case "mp4", "mkv", "avi", "mov", "flv", "wmv", "webm", "m4v", "ts", "mpg", "mpeg", "rmvb", "3gp":
		return true
	}
	return false
}

// SanitizeDirName makes a safe directory/file name.
func SanitizeDirName(s string) string {
	var b strings.Builder
	for _, r := range s {
		if r < 32 || r == '/' || r == '\\' || r == ':' || r == '*' || r == '?' || r == '"' || r == '<' || r == '>' || r == '|' {
			b.WriteRune(' ')
		} else {
			b.WriteRune(r)
		}
	}
	out := strings.TrimSpace(b.String())
	if out == "" {
		out = "unknown"
	}
	// avoid trailing dots (Windows compat)
	out = strings.TrimRight(out, ". ")
	return out
}

// BuildTree writes the media tree under root:
//
//	root/Show Title/Season 01/Show Title S01E02.strm
//	root/Show Title/tvshow.nfo
//	root/Show Title/Season 01/... (episode strm files)
//
// strmBase e.g. "http://192.168.1.2:8080" — full URL becomes strmBase + /stream/<key>/<name>
func BuildTree(root string, groups []Group, strmBase string) (int, error) {
	count := 0
	for _, g := range groups {
		dir := filepath.Join(root, SanitizeDirName(g.Title), fmt.Sprintf("Season %02d", g.Season))
		if err := mkdir(dir); err != nil {
			return count, err
		}
		// tvshow.nfo so Jellyfin treats folder as a show
		nfo := fmt.Sprintf("<tvshow><title>%s</title></tvshow>", xmlEscape(g.Title))
		if err := writeFile(filepath.Join(root, SanitizeDirName(g.Title), "tvshow.nfo"), nfo); err != nil {
			return count, err
		}
		for _, ef := range g.Episodes {
			name := g.Title
			fname := fmt.Sprintf("%s S%02dE%02d.strm", SanitizeDirName(name), g.Season, ef.Episode)
			url := fmt.Sprintf("%s/stream/%s", strmBase, ef.StreamKey)
			if err := writeFile(filepath.Join(dir, fname), url); err != nil {
				return count, err
			}
			count++
		}
	}
	return count, nil
}

func mkdir(p string) error { return osMkdirAll(p, 0o755) }
