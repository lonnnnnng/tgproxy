package media

import (
	"fmt"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"unicode"
)

type Episode struct{ Season, Episode int }
type episodePattern struct {
	re              *regexp.Regexp
	season, episode int
}

var episodePatterns = []episodePattern{
	{regexp.MustCompile(`(?i)S(\d{1,2})\s*E(\d{1,4})(?:E\d{1,4})?`), 1, 2},
	{regexp.MustCompile(`(?i)(\d{1,2})x(\d{1,4})`), 1, 2},
	{regexp.MustCompile(`第\s*(\d{1,4})\s*[集话話回]`), 0, 1},
	{regexp.MustCompile(`(?i)(?:^|[\s._\-\[])EP?(\d{1,4})`), 0, 1},
	{regexp.MustCompile(`[（(\[【]\s*第?\s*(\d{1,4})\s*[集话話]?\s*[）)\]】]`), 0, 1},
	{regexp.MustCompile(`(\d{1,4})`), 0, 1},
}
var chineseSeason = regexp.MustCompile(`第\s*([0-9一二三四五六七八九十]+)\s*季`)
var noiseTags = regexp.MustCompile(`(?i)(?:^|[\s._\-\[\]【】#])(?:1080p|720p|2160p|4k|2k|hdr|dv|x264|x265|h\.?264|h\.?265|hevc|avc)(?:$|[\s._\-\[\]【】#])`)
var languageTags = regexp.MustCompile(`国粤双语|国语|粤语|中字|双语|内封|外挂|简繁|无水印|高清|完整版|加长版|未删减`)
var sourceTags = regexp.MustCompile(`(?i)@[a-z0-9_]+|[\[【](?:来源|[^\]】]*字幕组)[\]】]`)
var trailingYear = regexp.MustCompile(`\(\d{4}\)\s*$`)

func episodeIn(name string) (Episode, int, int, bool) {
	base := strings.TrimSuffix(name, filepath.Ext(name))
	season := 1
	if m := chineseSeason.FindStringSubmatch(base); m != nil {
		if n, err := strconv.Atoi(m[1]); err == nil {
			season = n
		} else {
			digits := map[rune]int{'一': 1, '二': 2, '三': 3, '四': 4, '五': 5, '六': 6, '七': 7, '八': 8, '九': 9}
			n := 0
			for _, r := range m[1] {
				if r == '十' {
					if n == 0 {
						n = 1
					}
					n *= 10
				} else {
					n += digits[r]
				}
			}
			if n > 0 {
				season = n
			}
		}
	}
	for idx, p := range episodePatterns {
		for _, m := range p.re.FindAllStringSubmatchIndex(base, -1) {
			a, b := m[2*p.episode], m[2*p.episode+1]
			if idx == 1 && ((m[0] > 0 && !strings.ContainsRune(" ._-([", rune(base[m[0]-1]))) || (m[1] < len(base) && !strings.ContainsRune(" ._-)]", rune(base[m[1]])))) {
				continue
			}
			if idx == 3 && m[1] < len(base) && ((base[m[1]] >= 'a' && base[m[1]] <= 'z') || (base[m[1]] >= 'A' && base[m[1]] <= 'Z')) {
				continue
			}
			if b < len(base) && base[b] >= '0' && base[b] <= '9' {
				continue
			}
			if idx == 5 {
				beforeOK := a == 0 || strings.ContainsRune(" ._-([【", rune(base[a-1]))
				afterOK := b == len(base) || strings.ContainsRune(" ._-)]】", rune(base[b]))
				if !beforeOK || !afterOK {
					continue
				}
			}
			e, _ := strconv.Atoi(base[a:b])
			if idx >= 4 && isNoiseNumber(e) {
				continue
			}
			s := season
			if p.season > 0 {
				s, _ = strconv.Atoi(base[m[2*p.season]:m[2*p.season+1]])
			}
			return Episode{s, e}, m[0], m[1], true
		}
	}
	return Episode{}, 0, 0, false
}
func isNoiseNumber(e int) bool {
	if e <= 0 || (e >= 1900 && e <= 2100) {
		return true
	}
	switch e {
	case 264, 265, 480, 576, 720, 1080, 2160, 4320:
		return true
	}
	return false
}
func ParseEpisode(name string) (Episode, bool) { ep, _, _, ok := episodeIn(name); return ep, ok }
func CleanTitle(name string) string {
	base := strings.TrimSuffix(name, filepath.Ext(name))
	_, a, b, ok := episodeIn(name)
	if ok {
		if strings.Trim(base[:a], " ._-#([【") == "" {
			base = base[b:]
		} else {
			base = base[:a]
		}
	}
	base = chineseSeason.ReplaceAllString(base, "")
	base = sourceTags.ReplaceAllString(base, " ")
	for i := 0; i < 2; i++ {
		base = noiseTags.ReplaceAllString(base, " ")
	}
	base = languageTags.ReplaceAllString(base, " ")
	base = trailingYear.ReplaceAllString(base, "")
	base = strings.NewReplacer(".", " ", "_", " ", "#", " ", "【", " ", "】", " ", "[", " ", "]", " ").Replace(base)
	return strings.Trim(strings.Join(strings.Fields(base), " "), " ._-()（）")
}

type Group struct {
	Title    string
	Season   int
	Episodes []EpisodeFile
}
type EpisodeFile struct {
	Episode         int
	StreamKey, Name string
	Size            int64
}
type Item struct {
	ChannelID int64
	MessageID int
	Name      string
	Size      int64
}

func Key(ch int64, msg int) string   { return fmt.Sprintf("%d_%d", ch, msg) }
func normalizeTitle(t string) string { return strings.ToLower(strings.Join(strings.Fields(t), "")) }
func readable(s string) bool {
	for _, r := range s {
		if unicode.IsLetter(r) || unicode.IsNumber(r) {
			return true
		}
	}
	return false
}

func Classify(items []Item) []Group {
	input := append([]Item(nil), items...)
	sort.Slice(input, func(i, j int) bool {
		return Key(input[i].ChannelID, input[i].MessageID) < Key(input[j].ChannelID, input[j].MessageID)
	})
	// long: 无集号的同名文件可能是不同电影或不同预告，不能当成一集覆盖。
	standalone := map[string]int{}
	for _, it := range input {
		if IsVideo(it.Name) {
			if _, ok := ParseEpisode(it.Name); !ok {
				standalone[normalizeTitle(CleanTitle(it.Name))]++
			}
		}
	}
	type groupKey struct {
		title  string
		season int
	}
	groups := map[groupKey]*Group{}
	seen := map[string]bool{}
	for _, it := range input {
		key := Key(it.ChannelID, it.MessageID)
		if !IsVideo(it.Name) || seen[key] {
			continue
		}
		seen[key] = true
		ep, ok := ParseEpisode(it.Name)
		if !ok {
			ep = Episode{1, 0}
		}
		title := CleanTitle(it.Name)
		if !readable(title) {
			title = "未命名视频 " + key
		} else if !ok && standalone[normalizeTitle(title)] > 1 {
			title += " [" + key + "]"
		}
		k := groupKey{normalizeTitle(title), ep.Season}
		g := groups[k]
		if g == nil {
			g = &Group{Title: title, Season: ep.Season}
			groups[k] = g
		}
		g.Episodes = append(g.Episodes, EpisodeFile{ep.Episode, key, it.Name, it.Size})
	}
	out := make([]Group, 0, len(groups))
	for _, g := range groups {
		sort.Slice(g.Episodes, func(i, j int) bool {
			a, b := g.Episodes[i], g.Episodes[j]
			if a.Episode == b.Episode {
				return a.StreamKey < b.StreamKey
			}
			return a.Episode < b.Episode
		})
		out = append(out, *g)
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].Title == out[j].Title {
			return out[i].Season < out[j].Season
		}
		return out[i].Title < out[j].Title
	})
	return out
}
func IsVideo(name string) bool {
	switch strings.ToLower(filepath.Ext(name)) {
	case ".mp4", ".mkv", ".avi", ".mov", ".flv", ".wmv", ".webm", ".m4v", ".ts", ".mpg", ".mpeg", ".rmvb", ".3gp":
		return true
	}
	return false
}
func SanitizeDirName(s string) string {
	var b strings.Builder
	for _, r := range s {
		if r < 32 || strings.ContainsRune(`/\:*?"<>|`, r) {
			b.WriteRune(' ')
		} else {
			b.WriteRune(r)
		}
	}
	out := strings.Trim(b.String(), ". ")
	if !readable(out) {
		out = "unknown"
	}
	// long: 为季集号和来源后缀保留空间，避免中文长文件名超过文件系统单段限制。
	if len(out) > 160 {
		out = shortName(out)
	}
	return out
}
