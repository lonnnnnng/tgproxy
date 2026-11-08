package media

import (
	"encoding/xml"
	"os"
	"strings"
)

func osMkdirAll(p string, perm os.FileMode) error { return os.MkdirAll(p, perm) }

func writeFile(path, content string) error {
	return os.WriteFile(path, []byte(content), 0o644)
}

func xmlEscape(s string) string {
	var b strings.Builder
	if err := xml.EscapeText(&b, []byte(s)); err != nil {
		return s
	}
	return b.String()
}
