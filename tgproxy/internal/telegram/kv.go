package telegram

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"

	"github.com/iyear/tdl/core/storage"
)

// kvStore is a file-backed storage.Storage for sessions and peer cache.
type kvStore struct {
	dir string
}

func NewKVStore(dir string) storage.Storage {
	_ = os.MkdirAll(dir, 0o755)
	return &kvStore{dir: dir}
}

func (k *kvStore) path(key string) string {
	return filepath.Join(k.dir, "kv_"+sanitizeKey(key)+".json")
}

func sanitizeKey(k string) string {
	b, _ := json.Marshal(k)
	s := string(b)
	out := make([]rune, 0, len(s))
	for _, r := range s {
		if (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9') || r == '_' || r == '-' {
			out = append(out, r)
		} else {
			out = append(out, '_')
		}
	}
	return string(out)
}

func (k *kvStore) Get(_ context.Context, key string) ([]byte, error) {
	b, err := os.ReadFile(k.path(key))
	if err != nil {
		if os.IsNotExist(err) {
			return nil, storage.ErrNotFound
		}
		return nil, err
	}
	return b, nil
}

func (k *kvStore) Set(_ context.Context, key string, value []byte) error {
	return os.WriteFile(k.path(key), value, 0o600)
}

func (k *kvStore) Delete(_ context.Context, key string) error {
	err := os.Remove(k.path(key))
	if err != nil && !os.IsNotExist(err) {
		return err
	}
	return nil
}
