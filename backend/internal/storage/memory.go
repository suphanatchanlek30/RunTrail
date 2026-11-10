package storage

import (
	"context"
	"net/url"
	"sync"
	"time"
)

// Memory is an in-memory Storage for tests. Put simulates the phone's upload.
type Memory struct {
	mu      sync.Mutex
	objects map[string]Object
	Deleted []string
}

func NewMemory() *Memory { return &Memory{objects: map[string]Object{}} }

func (m *Memory) Put(key string, size int64, contentType string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.objects[key] = Object{Size: size, ContentType: contentType}
}

func (m *Memory) Has(key string) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	_, ok := m.objects[key]
	return ok
}

func (m *Memory) PresignPut(_ context.Context, key, _ string, _ time.Duration) (string, error) {
	return "http://fake-s3/put/" + url.PathEscape(key), nil
}

func (m *Memory) PresignGet(_ context.Context, key string, _ time.Duration) (string, error) {
	return "http://fake-s3/get/" + url.PathEscape(key), nil
}

func (m *Memory) Head(_ context.Context, key string) (Object, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	o, ok := m.objects[key]
	if !ok {
		return Object{}, ErrNotFound
	}
	return o, nil
}

func (m *Memory) Delete(_ context.Context, key string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	delete(m.objects, key)
	m.Deleted = append(m.Deleted, key)
	return nil
}

func (m *Memory) Ping(context.Context) error { return nil }
