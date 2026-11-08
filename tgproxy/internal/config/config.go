package config

import (
	"strconv"
	"encoding/json"
	"os"
	"path/filepath"
)

// Config is the persistent configuration of tgproxy.
type Config struct {
	// Telegram credentials
	AppID   int    `json:"app_id"`
	AppHash string `json:"app_hash"`
	// Session data (serialized gotd session)
	Session []byte `json:"session,omitempty"`
	// Listen address for web UI + stream server
	Listen string `json:"listen"`
	// Media tree root directory (contains .strm files for Jellyfin)
	MediaRoot string `json:"media_root"`
	// Stream URL base used inside .strm files. Empty = derive from request host.
	StrmBase string `json:"strm_base,omitempty"`
	// Cache settings
	CacheMaxBytes int64 `json:"cache_max_bytes"` // LRU chunk cache size
	ChunkSize     int64 `json:"chunk_size"`      // fetch chunk size, must be 4096-aligned multiple
	PrefetchChunks int  `json:"prefetch_chunks"` // sequential prefetch count
}

func Defaults() *Config {
	return &Config{
		// Builtin credentials: the official Telegram Desktop app identity
		// (same as tdl's "desktop" app), so users only need account login.
		AppID:          2040,
		AppHash:        "b18441a1ff607e10a989891a5462e627",
		Listen:         ":8080",
		CacheMaxBytes:  512 << 20, // 512 MB
		ChunkSize:      512 * 1024,
		PrefetchChunks: 8,
	}
}

func Path(dataDir string) string {
	return filepath.Join(dataDir, "config.json")
}

func Load(dataDir string) (*Config, error) {
	c := Defaults()
	b, err := os.ReadFile(Path(dataDir))
	if err == nil {
		if err := json.Unmarshal(b, c); err != nil {
			return nil, err
		}
	} else if !os.IsNotExist(err) {
		return nil, err
	}
	// environment overrides (TG_API_ID / TG_API_HASH)
	if v := os.Getenv("TG_API_ID"); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			c.AppID = n
		}
	}
	if v := os.Getenv("TG_API_HASH"); v != "" {
		c.AppHash = v
	}
	return c, nil
}

func Save(dataDir string, c *Config) error {
	b, err := json.MarshalIndent(c, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(Path(dataDir), b, 0o600)
}
