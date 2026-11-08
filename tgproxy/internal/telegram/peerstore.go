package telegram

import (
	"context"
	"sync"

	"github.com/gotd/td/telegram/peers"
)

// peerMemStorage is a volatile peers cache; resolved peers are re-fetched via RPC as needed.
type peerMemStorage struct {
	mu      sync.Mutex
	values  map[peers.Key]peers.Value
	phones  map[string]peers.Key
	contact int64
}

func newPeerMemStorage() *peerMemStorage {
	return &peerMemStorage{values: map[peers.Key]peers.Value{}, phones: map[string]peers.Key{}}
}

func (s *peerMemStorage) Save(_ context.Context, key peers.Key, value peers.Value) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.values[key] = value
	return nil
}

func (s *peerMemStorage) Find(_ context.Context, key peers.Key) (peers.Value, bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	v, ok := s.values[key]
	return v, ok, nil
}

func (s *peerMemStorage) SavePhone(_ context.Context, phone string, key peers.Key) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.phones[phone] = key
	return nil
}

func (s *peerMemStorage) FindPhone(_ context.Context, phone string) (peers.Key, peers.Value, bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	k, ok := s.phones[phone]
	if !ok {
		return peers.Key{}, peers.Value{}, false, nil
	}
	v, ok := s.values[k]
	return k, v, ok, nil
}

func (s *peerMemStorage) GetContactsHash(_ context.Context) (int64, error) { return s.contact, nil }
func (s *peerMemStorage) SaveContactsHash(_ context.Context, hash int64) error {
	s.contact = hash
	return nil
}
