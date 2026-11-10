package main

import (
	"container/list"
	"context"
	"errors"
	"io"
	"sync"

	"github.com/anacrolix/torrent/metainfo"
	"github.com/anacrolix/torrent/storage"
)

// The helper handles one torrent. Its cache holds only the recently used pieces and never writes media to disk.
type pieceCache struct {
	mu       sync.Mutex
	capacity int64
	used     int64
	recent   list.List
	entries  map[int]*list.Element
}

type entry struct {
	index    int
	data     []byte
	complete bool
}

type cachedPiece struct {
	cache  *pieceCache
	index  int
	length int64
}

func (c *pieceCache) OpenTorrent(_ context.Context, info *metainfo.Info, _ metainfo.Hash) (storage.TorrentImpl, error) {
	if info.PieceLength <= 0 || info.PieceLength > c.capacity/3 {
		return storage.TorrentImpl{}, errors.New("unsupported torrent piece size")
	}
	c.entries = make(map[int]*list.Element)
	capacity := func() (int64, bool) { return c.capacity, true }
	return storage.TorrentImpl{
		Capacity: &capacity,
		Piece:    func(p metainfo.Piece) storage.PieceImpl { return cachedPiece{c, p.Index(), p.Length()} },
		Close:    func() error { return nil },
	}, nil
}

func (p cachedPiece) ReadAt(dst []byte, offset int64) (int, error) {
	c := p.cache
	c.mu.Lock()
	defer c.mu.Unlock()
	item := c.entries[p.index]
	if item == nil || offset < 0 || offset >= p.length {
		return 0, io.EOF
	}
	c.recent.MoveToFront(item)
	n := copy(dst, item.Value.(*entry).data[offset:])
	if n < len(dst) {
		return n, io.EOF
	}
	return n, nil
}

func (p cachedPiece) WriteAt(src []byte, offset int64) (int, error) {
	c := p.cache
	c.mu.Lock()
	defer c.mu.Unlock()
	if offset < 0 || offset > p.length || int64(len(src)) > p.length-offset || p.length > c.capacity {
		return 0, io.ErrShortWrite
	}
	item := c.entries[p.index]
	if item == nil {
		for c.used+p.length > c.capacity {
			last := c.recent.Back()
			old := last.Value.(*entry)
			delete(c.entries, old.index)
			c.used -= int64(len(old.data))
			c.recent.Remove(last)
		}
		item = c.recent.PushFront(&entry{index: p.index, data: make([]byte, p.length)})
		c.entries[p.index] = item
		c.used += p.length
	}
	c.recent.MoveToFront(item)
	return copy(item.Value.(*entry).data[offset:], src), nil
}

func (p cachedPiece) MarkComplete() error {
	c := p.cache
	c.mu.Lock()
	defer c.mu.Unlock()
	item := c.entries[p.index]
	if item == nil {
		return io.ErrUnexpectedEOF
	}
	item.Value.(*entry).complete = true
	return nil
}

func (p cachedPiece) MarkNotComplete() error {
	c := p.cache
	c.mu.Lock()
	defer c.mu.Unlock()
	if item := c.entries[p.index]; item != nil {
		item.Value.(*entry).complete = false
	}
	return nil
}

func (p cachedPiece) Completion() storage.Completion {
	c := p.cache
	c.mu.Lock()
	defer c.mu.Unlock()
	item := c.entries[p.index]
	return storage.Completion{Ok: true, Complete: item != nil && item.Value.(*entry).complete}
}
