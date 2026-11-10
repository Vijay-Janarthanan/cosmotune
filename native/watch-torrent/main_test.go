package main

import (
	"bytes"
	"context"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/anacrolix/torrent"
	"github.com/anacrolix/torrent/bencode"
	"github.com/anacrolix/torrent/metainfo"
)

func TestStreamsAndSeeksFromLocalBitTorrentPeer(t *testing.T) {
	dir := t.TempDir()
	name := filepath.Join(dir, "cosmotune-fixture.mp4")
	payload := bytes.Repeat([]byte("Cosmotune local test media"), 4096)
	if err := os.WriteFile(name, payload, 0600); err != nil {
		t.Fatal(err)
	}
	info := metainfo.Info{PieceLength: 16 << 10}
	if err := info.BuildFromFilePath(name); err != nil {
		t.Fatal(err)
	}
	data, err := bencode.Marshal(info)
	if err != nil {
		t.Fatal(err)
	}
	meta := &metainfo.MetaInfo{InfoBytes: data}
	config := func() *torrent.ClientConfig {
		c := torrent.NewDefaultClientConfig()
		c.ListenHost = func(string) string { return "127.0.0.1" }
		c.ListenPort = 0
		c.DisableUTP, c.DisableIPv6, c.NoDHT, c.NoDefaultPortForwarding = true, true, true, true
		c.DisableTrackers, c.DisableWebtorrent, c.DisableWebseeds = true, true, true
		return c
	}
	ctx, cancel := context.WithTimeout(context.Background(), 12*time.Second)
	defer cancel()
	seedConfig := config()
	seedConfig.DataDir, seedConfig.Seed = dir, true
	seed, err := torrent.NewClient(seedConfig)
	if err != nil {
		t.Fatal(err)
	}
	defer seed.Close()
	source, err := seed.AddTorrent(meta)
	if err != nil {
		t.Fatal(err)
	}
	if err := source.VerifyDataContext(ctx); err != nil {
		t.Fatal(err)
	}
	playerConfig := config()
	playerConfig.DefaultStorage = &pieceCache{capacity: cacheBytes}
	player, err := torrent.NewClient(playerConfig)
	if err != nil {
		t.Fatal(err)
	}
	defer player.Close()
	item, _ := player.AddTorrentInfoHash(meta.HashInfoBytes())
	item.AddPeers([]torrent.PeerInfo{{Addr: seed.ListenAddrs()[0]}})
	file, err := resolveFile(ctx, item, offer{})
	if err != nil {
		t.Fatal(err)
	}
	urls := make(chan string, 1)
	finished := make(chan error, 1)
	go func() { finished <- serve(ctx, file, func(result reply) { urls <- result.URL }) }()
	var address string
	select {
	case address = <-urls:
	case err := <-finished:
		t.Fatal(err)
	case <-ctx.Done():
		t.Fatal(ctx.Err())
	}
	for _, check := range []struct {
		rangeHeader string
		start, end  int
	}{{"bytes=40000-40063", 40000, 40064}, {"bytes=8-39", 8, 40}} {
		request, err := http.NewRequestWithContext(ctx, http.MethodGet, address, nil)
		if err != nil {
			t.Fatal(err)
		}
		request.Header.Set("Range", check.rangeHeader)
		response, err := http.DefaultClient.Do(request)
		if err != nil {
			t.Fatal(err)
		}
		content, err := io.ReadAll(response.Body)
		response.Body.Close()
		if err != nil || response.StatusCode != http.StatusPartialContent || !bytes.Equal(content, payload[check.start:check.end]) {
			t.Fatalf("bad response for %s: status %d, %d bytes, %v", check.rangeHeader, response.StatusCode, len(content), err)
		}
	}
	cancel()
	select {
	case <-finished:
	case <-time.After(time.Second):
		t.Fatal("server did not stop after playback ended")
	}
}
