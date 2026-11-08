package config

import (
	"encoding/json"
	"fmt"
	"net"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"tgproxy/internal/persist"
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
	CacheMaxBytes  int64  `json:"cache_max_bytes"` // LRU chunk cache size
	ChunkSize      int64  `json:"chunk_size"`      // fetch chunk size, must be 4096-aligned multiple
	PrefetchChunks int    `json:"prefetch_chunks"` // sequential prefetch count
	JellyfinURL    string `json:"jellyfin_url,omitempty"`
	JellyfinAPIKey string `json:"jellyfin_api_key,omitempty"`
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
		} else {
			return nil, fmt.Errorf("TG_API_ID 必须为正整数")
		}
	}
	if v := os.Getenv("TG_API_HASH"); v != "" {
		c.AppHash = v
	}
	return c, c.Validate()
}

func Save(dataDir string, c *Config) error {
	if err := c.Validate(); err != nil {
		return err
	}
	if err := os.MkdirAll(dataDir, 0o700); err != nil {
		return err
	}
	return persist.WriteJSON(Path(dataDir), c)
}

func (c *Config) Validate() error {
	if c.AppID <= 0 || strings.TrimSpace(c.AppHash) == "" {
		return fmt.Errorf("Telegram API 凭证无效")
	}
	// long: 分块必须落在 Telegram 的 1 MiB 窗口内，整除窗口也保证后续偏移合法。
	if c.ChunkSize < 4096 || c.ChunkSize > 1<<20 || c.ChunkSize%4096 != 0 || (1<<20)%c.ChunkSize != 0 {
		return fmt.Errorf("chunk_size 必须为 4096～1048576 且整除 1048576")
	}
	if c.CacheMaxBytes < c.ChunkSize || c.CacheMaxBytes > 4<<30 {
		return fmt.Errorf("cache_max_bytes 必须不少于一个分块且不超过 4 GiB")
	}
	if c.PrefetchChunks < 0 || c.PrefetchChunks > 16 {
		return fmt.Errorf("prefetch_chunks 必须为 0～16")
	}
	if _, _, err := net.SplitHostPort(c.Listen); err != nil {
		return fmt.Errorf("listen 地址无效: %w", err)
	}
	for _, raw := range []string{c.StrmBase, c.JellyfinURL} {
		if raw == "" {
			continue
		}
		u, err := url.Parse(raw)
		if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" || u.User != nil || u.RawQuery != "" || u.Fragment != "" {
			return fmt.Errorf("服务地址必须为不含凭据、查询参数的 HTTP(S) URL")
		}
	}
	if (c.JellyfinURL == "") != (c.JellyfinAPIKey == "") {
		return fmt.Errorf("jellyfin_url 和 jellyfin_api_key 必须同时配置")
	}
	return nil
}
