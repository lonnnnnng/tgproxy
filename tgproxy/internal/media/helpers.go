package media

import (
	"encoding/xml"
	"os"
	"path/filepath"
	"strings"
)

func osMkdirAll(p string, perm os.FileMode) error { return os.MkdirAll(p, perm) }

func writeFile(path, content string) error {
	f, err := os.CreateTemp(filepath.Dir(path), ".tgproxy-*.tmp")
	if err != nil {
		return err
	}
	name := f.Name()
	defer os.Remove(name)
	defer f.Close()
	if err = f.Chmod(0644); err != nil {
		return err
	}
	if _, err = f.WriteString(content); err != nil {
		return err
	}
	if err = f.Close(); err != nil {
		return err
	}
	return os.Rename(name, path)
}

func xmlEscape(s string) string {
	var b strings.Builder
	if err := xml.EscapeText(&b, []byte(s)); err != nil {
		return s
	}
	return b.String()
}
