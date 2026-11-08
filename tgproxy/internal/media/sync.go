package media

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"sync"
	"syscall"
	"tgproxy/internal/persist"
	"time"
	"unicode/utf8"
)

var treeMu sync.Mutex
var legacyKey = regexp.MustCompile(`^-?\d+_\d+$`)

const manifestName = ".tgproxy-manifest.json"

type treeManifest struct {
	Revision uint64            `json:"revision"`
	Version  int               `json:"version"`
	Files    map[string]string `json:"files"`
}

func digest(b []byte) string { h := sha256.Sum256(b); return hex.EncodeToString(h[:]) }
func shortName(s string) string {
	end := min(len(s), 140)
	for end > 0 && !utf8.RuneStart(s[end]) {
		end--
	}
	return s[:end] + "-" + digest([]byte(s))[:10]
}
func safePath(root, rel string) (string, error) {
	if filepath.IsAbs(rel) || rel == "." || rel == "" || rel == ".." || strings.HasPrefix(rel, ".."+string(filepath.Separator)) {
		return "", fmt.Errorf("无效媒体相对路径 %q", rel)
	}
	p := root
	for _, part := range strings.Split(filepath.Clean(rel), string(filepath.Separator)) {
		if part == ".." {
			return "", fmt.Errorf("媒体路径不能越过根目录")
		}
		p = filepath.Join(p, part)
		st, err := os.Lstat(p)
		if err == nil && st.Mode()&os.ModeSymlink != 0 {
			return "", fmt.Errorf("媒体路径包含符号链接: %s", rel)
		}
		if err != nil && !os.IsNotExist(err) {
			return "", err
		}
	}
	return p, nil
}
func planTree(groups []Group, base string) (map[string]string, int, error) {
	files := map[string]string{}
	titles := map[string]string{}
	for _, g := range groups {
		k := normalizeTitle(g.Title)
		if old, ok := titles[k]; !ok || g.Title < old {
			titles[k] = g.Title
		}
	}
	folders := map[string]map[string]bool{}
	for key, title := range titles {
		dir := strings.ToLower(SanitizeDirName(title))
		if folders[dir] == nil {
			folders[dir] = map[string]bool{}
		}
		folders[dir][key] = true
	}
	count := 0
	for _, g := range groups {
		title := titles[normalizeTitle(g.Title)]
		dir := SanitizeDirName(title)
		if len(folders[strings.ToLower(dir)]) > 1 {
			dir += "-" + digest([]byte(normalizeTitle(g.Title)))[:8]
		}
		files[filepath.Join(dir, "tvshow.nfo")] = fmt.Sprintf("<tvshow><title>%s</title></tvshow>", xmlEscape(title))
		counts := map[int]int{}
		for _, ef := range g.Episodes {
			counts[ef.Episode]++
		}
		for _, ef := range g.Episodes {
			suffix := ""
			if counts[ef.Episode] > 1 {
				suffix = " - " + ef.StreamKey
			}
			name := fmt.Sprintf("%s S%02dE%02d%s.strm", SanitizeDirName(title), g.Season, ef.Episode, suffix)
			rel := filepath.Join(dir, fmt.Sprintf("Season %02d", g.Season), name)
			if _, ok := files[rel]; ok {
				return nil, 0, fmt.Errorf("媒体输出路径冲突: %s", rel)
			}
			files[rel] = strings.TrimRight(base, "/") + "/stream/" + ef.StreamKey
			count++
		}
	}
	return files, count, nil
}

// long: BuildTree 保留根目录 inode，仅同步已登记的项目文件；旧内容移至旁边的备份目录。
func BuildTree(root string, groups []Group, strmBase string, revision ...uint64) (count int, err error) {
	treeMu.Lock()
	defer treeMu.Unlock()
	root, err = filepath.Abs(root)
	if err != nil {
		return 0, err
	}
	if err = os.MkdirAll(root, 0755); err != nil {
		return 0, err
	}
	st, err := os.Lstat(root)
	if err != nil {
		return 0, err
	}
	if st.Mode()&os.ModeSymlink != 0 {
		return 0, fmt.Errorf("媒体根目录不能是符号链接")
	}
	desired, count, err := planTree(groups, strmBase)
	if err != nil {
		return 0, err
	}
	old := treeManifest{Version: 1, Files: map[string]string{}}
	manifestPath, err := safePath(root, manifestName)
	if err != nil {
		return 0, err
	}
	b, readErr := os.ReadFile(manifestPath)
	if readErr == nil {
		if err = json.Unmarshal(b, &old); err != nil {
			return 0, err
		}
		if old.Version != 1 || old.Files == nil {
			return 0, fmt.Errorf("媒体清单格式无效")
		}
	} else if !os.IsNotExist(readErr) {
		return 0, readErr
	} else {
		// long: 兼容旧版无清单的树，仅接管符合本项目流地址格式且位于剧/季目录的文件。
		ownedDirs := map[string]bool{}
		err = filepath.WalkDir(root, func(path string, d fs.DirEntry, e error) error {
			if e != nil {
				return e
			}
			if d.Type()&os.ModeSymlink != 0 {
				return nil
			}
			if d.IsDir() {
				if path != root && strings.HasPrefix(d.Name(), ".") {
					return filepath.SkipDir
				}
				return nil
			}
			rel, _ := filepath.Rel(root, path)
			if filepath.Ext(path) != ".strm" || !strings.HasPrefix(filepath.Base(filepath.Dir(path)), "Season ") {
				return nil
			}
			data, e := os.ReadFile(path)
			if e != nil {
				return e
			}
			key, ok := strings.CutPrefix(strings.TrimSpace(string(data)), strings.TrimRight(strmBase, "/")+"/stream/")
			if ok && legacyKey.MatchString(key) {
				old.Files[rel] = digest(data)
				ownedDirs[filepath.Dir(filepath.Dir(path))] = true
			}
			return nil
		})
		if err != nil {
			return 0, err
		}
		for dir := range ownedDirs {
			relNFO, _ := filepath.Rel(root, filepath.Join(dir, "tvshow.nfo"))
			path, pathErr := safePath(root, relNFO)
			if pathErr != nil {
				return 0, pathErr
			}
			data, e := os.ReadFile(path)
			if e == nil && strings.HasPrefix(string(data), "<tvshow><title>") && strings.HasSuffix(string(data), "</title></tvshow>") {
				rel, _ := filepath.Rel(root, path)
				old.Files[rel] = digest(data)
			}
		}
	}
	// long: 在任何写入前核验所有权，用户文件和被手动编辑过的文件都不会被覆盖。
	all := map[string]bool{}
	for rel := range old.Files {
		all[rel] = true
	}
	for rel := range desired {
		all[rel] = true
	}
	keys := make([]string, 0, len(all))
	for rel := range all {
		keys = append(keys, rel)
	}
	sort.Strings(keys)
	for _, rel := range keys {
		path, e := safePath(root, rel)
		if e != nil {
			return 0, e
		}
		data, e := os.ReadFile(path)
		if os.IsNotExist(e) {
			continue
		}
		if e != nil {
			return 0, e
		}
		hash := digest(data)
		want, wanted := desired[rel]
		owned := old.Files[rel]
		if owned == "" && (!wanted || string(data) != want) {
			return 0, fmt.Errorf("目标已有非项目文件，保留原文件: %s", rel)
		}
		if owned != "" && hash != owned {
			// long: 上次同步可能在提交清单前中断，已写成目标内容的文件可继续接管。
			if wanted && string(data) == want {
				continue
			}
			if wanted {
				return 0, fmt.Errorf("媒体文件已被手动修改，保留原文件: %s", rel)
			}
			delete(old.Files, rel)
			delete(all, rel)
		}
	}
	backup := ""
	moved := []string{}
	written := []string{}
	defer func() {
		if err == nil {
			return
		}
		var rollback []error
		for i := len(written) - 1; i >= 0; i-- {
			rel := written[i]
			p, pathErr := safePath(root, rel)
			if pathErr != nil {
				rollback = append(rollback, pathErr)
				continue
			}
			if backup != "" {
				dst := filepath.Join(backup, "failed-new", rel)
				if e := os.MkdirAll(filepath.Dir(dst), 0755); e != nil {
					rollback = append(rollback, e)
					continue
				}
				if e := moveFile(p, dst); e != nil {
					rollback = append(rollback, e)
				}
			}
		}
		for i := len(moved) - 1; i >= 0; i-- {
			rel := moved[i]
			p, pathErr := safePath(root, rel)
			if pathErr != nil {
				rollback = append(rollback, pathErr)
				continue
			}
			if e := os.MkdirAll(filepath.Dir(p), 0755); e != nil {
				rollback = append(rollback, e)
				continue
			}
			if e := moveFile(filepath.Join(backup, rel), p); e != nil {
				rollback = append(rollback, e)
			}
		}
		if len(rollback) > 0 {
			err = errors.Join(err, fmt.Errorf("恢复媒体树时也发生错误，旧内容保留在 %s: %w", backup, errors.Join(rollback...)))
		}
	}()
	ensureBackup := func() error {
		if backup != "" {
			return nil
		}
		parent := root + ".backups"
		if info, e := os.Lstat(parent); e == nil && info.Mode()&os.ModeSymlink != 0 {
			return fmt.Errorf("媒体备份目录不能是符号链接")
		}
		if e := os.MkdirAll(parent, 0700); e != nil {
			return e
		}
		var e error
		backup, e = os.MkdirTemp(parent, time.Now().Format("20060102-150405")+"-")
		return e
	}
	next := treeManifest{Version: 1, Files: map[string]string{}}
	if len(revision) > 0 {
		next.Revision = revision[0]
	}
	for _, rel := range keys {
		if !all[rel] {
			continue
		}
		path, _ := safePath(root, rel)
		want, wanted := desired[rel]
		data, e := os.ReadFile(path)
		if e == nil && wanted && string(data) == want {
			next.Files[rel] = digest(data)
			continue
		}
		if e == nil {
			if err = ensureBackup(); err != nil {
				return 0, err
			}
			dst := filepath.Join(backup, rel)
			if err = os.MkdirAll(filepath.Dir(dst), 0755); err != nil {
				return 0, err
			}
			if err = moveFile(path, dst); err != nil {
				return 0, err
			}
			moved = append(moved, rel)
		}
		if !wanted {
			continue
		}
		if err = ensureBackup(); err != nil {
			return 0, err
		}
		if err = os.MkdirAll(filepath.Dir(path), 0755); err != nil {
			return 0, err
		}
		if err = writeFile(path, want); err != nil {
			return 0, err
		}
		written = append(written, rel)
		next.Files[rel] = digest([]byte(want))
	}
	if err = persist.WriteJSON(manifestPath, next); err != nil {
		return 0, err
	}
	// long: 只移除已经为空的旧目录，绝不递归清理媒体根目录或其他文件。
	for i := len(keys) - 1; i >= 0; i-- {
		dir := filepath.Dir(filepath.Join(root, keys[i]))
		for dir != root && strings.HasPrefix(dir, root+string(filepath.Separator)) {
			if os.Remove(dir) != nil {
				break
			}
			dir = filepath.Dir(dir)
		}
	}
	return count, nil
}

func TreeRevision(root string) (uint64, bool) {
	b, err := os.ReadFile(filepath.Join(root, manifestName))
	if err != nil {
		return 0, false
	}
	var m treeManifest
	if json.Unmarshal(b, &m) != nil || m.Version != 1 {
		return 0, false
	}
	return m.Revision, true
}

// long: 媒体根目录可能是独立挂载点，跨设备备份先校验副本再移除已登记的原文件。
func moveFile(src, dst string) error {
	err := os.Rename(src, dst)
	if !errors.Is(err, syscall.EXDEV) {
		return err
	}
	b, err := os.ReadFile(src)
	if err != nil {
		return err
	}
	if err = writeFile(dst, string(b)); err != nil {
		return err
	}
	copied, err := os.ReadFile(dst)
	if err != nil {
		return err
	}
	if digest(copied) != digest(b) {
		return fmt.Errorf("媒体备份校验失败")
	}
	current, err := os.ReadFile(src)
	if err != nil {
		return err
	}
	if digest(current) != digest(b) {
		return fmt.Errorf("备份期间媒体内容发生变化，保留原文件")
	}
	return os.Remove(src)
}
