package persist

import (
	"encoding/json"
	"os"
	"path/filepath"
)

// long: WriteJSON 先完成同目录临时文件，再替换正式配置，避免中断时留下半份凭据或索引。
func WriteJSON(path string, value any) error {
	b, err := json.MarshalIndent(value, "", "  ")
	if err != nil {
		return err
	}
	f, err := os.CreateTemp(filepath.Dir(path), ".tgproxy-*.tmp")
	if err != nil {
		return err
	}
	name := f.Name()
	defer os.Remove(name)
	defer f.Close()
	if _, err = f.Write(b); err != nil {
		return err
	}
	// long: 先确认数据写入，再公布新文件；保存失败时调用方继续使用旧快照。
	if err = f.Sync(); err != nil {
		return err
	}
	if err = f.Close(); err != nil {
		return err
	}
	return os.Rename(name, path)
}
