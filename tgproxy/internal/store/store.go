package store

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"tgproxy/internal/persist"
)

// Channel is a Telegram media source channel.
type Channel struct {
	ID    int64  `json:"id"`    // telegram peer id (channel id, -100 prefixed form kept raw)
	Ref   string `json:"ref"`   // user-entered reference: @name, t.me link, or numeric id
	Title string `json:"title"` // resolved title, filled after sync
	Added bool   `json:"added"` // has been resolved & indexed
}

// Item is one media message indexed from a channel.
type Item struct {
	ChannelID int64  `json:"channel_id"`
	MessageID int    `json:"message_id"`
	Name      string `json:"name"`
	Size      int64  `json:"size"`
	MIME      string `json:"mime"`
	Date      int64  `json:"date"`
}

// Library is the media index (channel list + all items).
type Library struct {
	mu       sync.RWMutex
	path     string
	Channels []Channel `json:"channels"`
	Items    []Item    `json:"items"`
	Revision uint64    `json:"revision"`
	index    map[string]Item
}

func Open(dataDir string) (*Library, error) {
	if err := os.MkdirAll(dataDir, 0o700); err != nil {
		return nil, err
	}
	p := filepath.Join(dataDir, "library.json")
	l := &Library{path: p}
	b, err := os.ReadFile(p)
	if err == nil {
		if err := json.Unmarshal(b, l); err != nil {
			return nil, err
		}
	} else if !os.IsNotExist(err) {
		return nil, err
	}
	l.reindexLocked()
	return l, nil
}

// long: commitLocked 只在持久化成功后切换内存，避免界面成功而重启后丢失修改。
func (l *Library) commitLocked(channels []Channel, items []Item) error {
	next := &Library{Channels: channels, Items: items, Revision: l.Revision + 1}
	if err := persist.WriteJSON(l.path, next); err != nil {
		return err
	}
	l.Channels, l.Items, l.Revision = channels, items, next.Revision
	l.reindexLocked()
	return nil
}

func itemKey(ch int64, msg int) string { return fmt.Sprintf("%d_%d", ch, msg) }
func (l *Library) reindexLocked() {
	l.index = make(map[string]Item, len(l.Items))
	for _, it := range l.Items {
		l.index[itemKey(it.ChannelID, it.MessageID)] = it
	}
}

func (l *Library) ListChannels() []Channel {
	l.mu.RLock()
	defer l.mu.RUnlock()
	out := make([]Channel, len(l.Channels))
	copy(out, l.Channels)
	return out
}

func (l *Library) AddedChannels() []Channel {
	l.mu.RLock()
	defer l.mu.RUnlock()
	var out []Channel
	for _, c := range l.Channels {
		if c.Added {
			out = append(out, c)
		}
	}
	return out
}

// UpsertChannel adds or updates a channel by Ref.
func (l *Library) UpsertChannel(c Channel) error {
	l.mu.Lock()
	defer l.mu.Unlock()
	channels := append([]Channel(nil), l.Channels...)
	for i, old := range channels {
		// long: @用户名和数字 ID 指向同一频道时复用记录，避免重复扫描与重复导出。
		if old.Ref == c.Ref || old.ID == c.ID {
			channels[i] = c
			return l.commitLocked(channels, l.Items)
		}
	}
	return l.commitLocked(append(channels, c), l.Items)
}

func (l *Library) RemoveChannel(ref string) (bool, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	var channels []Channel
	var removedID int64
	found := false
	for _, c := range l.Channels {
		if c.Ref == ref {
			removedID = c.ID
			found = true
		} else {
			channels = append(channels, c)
		}
	}
	if !found {
		return false, nil
	}
	keep := false
	for _, c := range channels {
		if c.ID == removedID {
			keep = true
		}
	}
	var items []Item
	for _, it := range l.Items {
		if keep || it.ChannelID != removedID {
			items = append(items, it)
		}
	}
	return true, l.commitLocked(channels, items)
}

func (l *Library) SetItems(channelID int64, items []Item) error {
	l.mu.Lock()
	defer l.mu.Unlock()
	found := false
	for _, c := range l.Channels {
		if c.ID == channelID {
			found = true
			break
		}
	}
	if !found {
		return fmt.Errorf("频道已移除，取消写入扫描结果")
	}
	out := make([]Item, 0, len(l.Items)+len(items))
	for _, it := range l.Items {
		if it.ChannelID != channelID {
			out = append(out, it)
		}
	}
	seen := map[int]bool{}
	for _, it := range items {
		if it.ChannelID != channelID || it.MessageID <= 0 {
			return fmt.Errorf("扫描结果包含无效频道或消息 ID")
		}
		if !seen[it.MessageID] {
			out = append(out, it)
			seen[it.MessageID] = true
		}
	}
	return l.commitLocked(l.Channels, out)
}

func (l *Library) Snapshot() (uint64, []Item) {
	l.mu.RLock()
	defer l.mu.RUnlock()
	return l.Revision, append([]Item(nil), l.Items...)
}

func (l *Library) AllItems() []Item {
	l.mu.RLock()
	defer l.mu.RUnlock()
	out := make([]Item, len(l.Items))
	copy(out, l.Items)
	return out
}

// FindItem locates an item by channel/message id.
func (l *Library) FindItem(channelID int64, msgID int) (Item, bool) {
	l.mu.RLock()
	defer l.mu.RUnlock()
	it, ok := l.index[itemKey(channelID, msgID)]
	return it, ok
}
