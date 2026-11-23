package main

import (
	"bytes"
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/anacrolix/torrent"
)

func localConfig() *torrent.ClientConfig {
	c := torrent.NewDefaultClientConfig()
	c.ListenHost = func(string) string { return "127.0.0.1" }
	c.ListenPort = 0
	c.DisableUTP, c.DisableIPv6, c.NoDHT, c.NoDefaultPortForwarding = true, true, true, true
	c.DisableTrackers, c.DisableWebtorrent, c.DisableWebseeds = true, true, true
	return c
}

// A folder of two episodes and a note, shared with makeTorrent, listed and downloaded by a second client on loopback.
func TestSharesListsAndDownloadsAFolderBetweenLocalPeers(t *testing.T) {
	root := filepath.Join(t.TempDir(), "Season 1")
	if err := os.MkdirAll(root, 0700); err != nil {
		t.Fatal(err)
	}
	first := bytes.Repeat([]byte("episode one "), 30000)
	second := bytes.Repeat([]byte("episode two "), 20000)
	for name, data := range map[string][]byte{"E01.mkv": first, "E02.mkv": second, "notes.txt": []byte("hello")} {
		if err := os.WriteFile(filepath.Join(root, name), data, 0600); err != nil {
			t.Fatal(err)
		}
	}
	meta, info, err := makeTorrent(root)
	if err != nil {
		t.Fatal(err)
	}
	if info.Name != "Season 1" || info.PieceLength != 256<<10 {
		t.Fatalf("unexpected torrent %q with %d byte pieces", info.Name, info.PieceLength)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	seedConfig := localConfig()
	seedConfig.DataDir, seedConfig.Seed = filepath.Dir(root), true
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
	into := t.TempDir()
	getterConfig := localConfig()
	getterConfig.DataDir = into
	getter, err := torrent.NewClient(getterConfig)
	if err != nil {
		t.Fatal(err)
	}
	defer getter.Close()
	item, _ := getter.AddTorrentInfoHash(meta.HashInfoBytes())
	item.AddPeers([]torrent.PeerInfo{{Addr: seed.ListenAddrs()[0]}})
	if err := waitInfo(ctx, item); err != nil {
		t.Fatal(err)
	}
	listed := filesOf(item)
	videos := 0
	for _, f := range listed {
		if f.Video {
			videos++
		}
	}
	if len(listed) != 3 || videos != 2 {
		t.Fatalf("listed %v", listed)
	}
	index := -1
	for _, f := range listed {
		if strings.HasSuffix(f.Path, "E02.mkv") {
			index = f.Index
		}
	}
	chosen := choose(item.Files(), []int{index, 99})
	if len(chosen) != 1 {
		t.Fatalf("chose %d files", len(chosen))
	}
	chosen[0].Download()
	var replies []reply
	if err := keep(ctx, item, chosen, false, 0, 50*time.Millisecond, func(r reply) { replies = append(replies, r) }); err != nil {
		t.Fatal(err)
	}
	last := replies[len(replies)-1]
	if !last.Complete || last.Progress == nil || last.Progress.Done != int64(len(second)) {
		t.Fatalf("last reply %+v", last)
	}
	got, err := os.ReadFile(filepath.Join(into, "Season 1", "E02.mkv"))
	if err != nil || !bytes.Equal(got, second) {
		t.Fatalf("downloaded file differs: %v", err)
	}
}

func TestEmptyFoldersAndMissingConnectionsAreRefused(t *testing.T) {
	if _, _, err := makeTorrent(t.TempDir()); err == nil {
		t.Fatal("an empty folder made a torrent")
	}
	if _, err := interfaceIPv4("cosmotune-no-such-connection"); err == nil {
		t.Fatal("a missing connection was accepted")
	}
	if got := choose(nil, []int{0}); len(got) != 0 {
		t.Fatal("chose a file that is not there")
	}
}
