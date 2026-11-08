package store

import (
	"encoding/json"
	"os"
	"path/filepath"
	"sync"
)

// Channel is a Telegram media source channel.
type Channel struct {
	ID    int64  `json:"id"`              // telegram peer id (channel id, -100 prefixed form kept raw)
	Ref   string `json:"ref"`             // user-entered reference: @name, t.me link, or numeric id
	Title string `json:"title"`           // resolved title, filled after sync
	Added bool   `json:"added"`           // has been resolved & indexed
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
}

func Open(dataDir string) (*Library, error) {
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
	return l, nil
}

func (l *Library) saveLocked() error {
	b, err := json.MarshalIndent(l, "", "  ")
	if err != nil {
		return err
	}
	tmp := l.path + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, l.path)
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
func (l *Library) UpsertChannel(c Channel) {
	l.mu.Lock()
	defer l.mu.Unlock()
	for i := range l.Channels {
		if l.Channels[i].Ref == c.Ref {
			l.Channels[i].Title = c.Title
			l.Channels[i].ID = c.ID
			l.Channels[i].Added = c.Added
			_ = l.saveLocked()
			return
		}
	}
	l.Channels = append(l.Channels, c)
	_ = l.saveLocked()
}

func (l *Library) RemoveChannel(ref string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	for i := range l.Channels {
		if l.Channels[i].Ref == ref {
			l.Channels = append(l.Channels[:i], l.Channels[i+1:]...)
			_ = l.saveLocked()
			return true
		}
	}
	return false
}

func (l *Library) SetItems(channelID int64, items []Item) {
	l.mu.Lock()
	defer l.mu.Unlock()
	var out []Item
	for _, it := range l.Items {
		if it.ChannelID != channelID {
			out = append(out, it)
		}
	}
	l.Items = append(out, items...)
	_ = l.saveLocked()
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
	for _, it := range l.Items {
		if it.ChannelID == channelID && it.MessageID == msgID {
			return it, true
		}
	}
	return Item{}, false
}
