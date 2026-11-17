// Package logbus keeps a bounded in-memory log ring for the web console.
package logbus

import (
	"fmt"
	"sync"
	"time"
)

// Entry is one log line returned by the API.
type Entry struct {
	Seq     int64  `json:"seq"`
	Time    string `json:"time"`
	Level   string `json:"level"`
	Message string `json:"message"`
}

// Bus is a concurrency-safe bounded log buffer.
type Bus struct {
	mu      sync.RWMutex
	entries []Entry
	limit   int
	seq     int64
}

func New(limit int) *Bus {
	if limit <= 0 {
		limit = 1000
	}
	return &Bus{limit: limit}
}

// Add appends one entry. Level is one of info/success/warn/error.
func (b *Bus) Add(level, format string, args ...any) {
	if b == nil {
		return
	}
	if level == "" {
		level = "info"
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	b.seq++
	b.entries = append(b.entries, Entry{
		Seq:     b.seq,
		Time:    time.Now().Format(time.RFC3339),
		Level:   level,
		Message: fmt.Sprintf(format, args...),
	})
	if len(b.entries) > b.limit {
		b.entries = append([]Entry(nil), b.entries[len(b.entries)-b.limit:]...)
	}
}

// Since returns entries with Seq greater than after and the latest sequence.
func (b *Bus) Since(after int64) ([]Entry, int64) {
	if b == nil {
		return nil, after
	}
	b.mu.RLock()
	defer b.mu.RUnlock()
	if after >= b.seq {
		return nil, b.seq
	}
	out := make([]Entry, 0, len(b.entries))
	for _, e := range b.entries {
		if e.Seq > after {
			out = append(out, e)
		}
	}
	return out, b.seq
}

// Logf adapts the bus to the callback signature used by the stream layer.
func (b *Bus) Logf(level, format string, args ...any) { b.Add(level, format, args...) }
