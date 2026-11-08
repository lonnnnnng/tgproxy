package telegram

import (
	"context"
	"encoding/json"
	"os"
	"sort"
	"sync"
	"tgproxy/internal/persist"

	"github.com/gotd/td/telegram/peers"
)

// peerMemStorage is a volatile peers cache; resolved peers are re-fetched via RPC as needed.
type peerMemStorage struct {
	mu      sync.Mutex
	values  map[peers.Key]peers.Value
	phones  map[string]peers.Key
	contact int64
	path    string
}

type savedPeer struct {
	Key   peers.Key
	Value peers.Value
}
type savedPeers struct {
	Values   []savedPeer
	Phones   map[string]peers.Key
	Contacts int64
}

func openPeerStorage(path string) (*peerMemStorage, error) {
	s := newPeerMemStorage()
	s.path = path
	if path == "" {
		return s, nil
	}
	b, err := os.ReadFile(path)
	if os.IsNotExist(err) {
		return s, nil
	}
	if err != nil {
		return nil, err
	}
	var saved savedPeers
	if err = json.Unmarshal(b, &saved); err != nil {
		return nil, err
	}
	for _, v := range saved.Values {
		s.values[v.Key] = v.Value
	}
	if saved.Phones != nil {
		s.phones = saved.Phones
	}
	s.contact = saved.Contacts
	return s, nil
}
func (s *peerMemStorage) saveLocked() error {
	if s.path == "" {
		return nil
	}
	data := savedPeers{Phones: s.phones, Contacts: s.contact}
	for k, v := range s.values {
		data.Values = append(data.Values, savedPeer{k, v})
	}
	sort.Slice(data.Values, func(i, j int) bool {
		a, b := data.Values[i].Key, data.Values[j].Key
		if a.Prefix == b.Prefix {
			return a.ID < b.ID
		}
		return a.Prefix < b.Prefix
	})
	return persist.WriteJSON(s.path, data)
}

func newPeerMemStorage() *peerMemStorage {
	return &peerMemStorage{values: map[peers.Key]peers.Value{}, phones: map[string]peers.Key{}}
}

func (s *peerMemStorage) Save(_ context.Context, key peers.Key, value peers.Value) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	old, ok := s.values[key]
	if ok && old == value {
		return nil
	}
	s.values[key] = value
	if err := s.saveLocked(); err != nil {
		if ok {
			s.values[key] = old
		} else {
			delete(s.values, key)
		}
		return err
	}
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
	old, ok := s.phones[phone]
	if ok && old == key {
		return nil
	}
	s.phones[phone] = key
	if err := s.saveLocked(); err != nil {
		if ok {
			s.phones[phone] = old
		} else {
			delete(s.phones, phone)
		}
		return err
	}
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

func (s *peerMemStorage) GetContactsHash(_ context.Context) (int64, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.contact, nil
}
func (s *peerMemStorage) SaveContactsHash(_ context.Context, hash int64) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	old := s.contact
	s.contact = hash
	if err := s.saveLocked(); err != nil {
		s.contact = old
		return err
	}
	return nil
}
