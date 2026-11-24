package main

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"net"
	"os"
	"path/filepath"
	"time"

	"github.com/anacrolix/torrent"
	"github.com/anacrolix/torrent/bencode"
	"github.com/anacrolix/torrent/metainfo"
)

// One file inside a torrent, as the "files" mode lists it.
type fileInfo struct {
	Index  int    `json:"index"`
	Path   string `json:"path"`
	Length int64  `json:"length"`
	Video  bool   `json:"video"`
}

// How far a download (or a share) is, sent every second.
type progress struct {
	Done     int64 `json:"done"`
	Total    int64 `json:"total"`
	Rate     int64 `json:"rate"`
	Peers    int   `json:"peers"`
	Uploaded int64 `json:"uploaded"`
	Seeding  bool  `json:"seeding"`
}

// The torrent of request: the .torrent bytes the listener opened when given (their trackers checked like an add-on's, their web
// seeds and DHT nodes dropped), else its info hash.
func add(ctx context.Context, client *torrent.Client, request offer) (*torrent.Torrent, error) {
	trackers := request.Trackers
	var item *torrent.Torrent
	if request.Torrent != "" {
		raw, err := base64.StdEncoding.DecodeString(request.Torrent)
		if err != nil || len(raw) > 4<<20 {
			return nil, errors.New("That .torrent file could not be read")
		}
		meta, err := metainfo.Load(bytes.NewReader(raw))
		if err != nil {
			return nil, errors.New("That .torrent file could not be read")
		}
		for _, tier := range meta.UpvertedAnnounceList() {
			trackers = append(trackers, tier...)
		}
		meta.Announce, meta.AnnounceList, meta.UrlList, meta.Nodes = "", nil, nil, nil
		if item, err = client.AddTorrent(meta); err != nil {
			return nil, errors.New("That .torrent file could not be read")
		}
	} else {
		hashBytes, err := hex.DecodeString(request.Hash)
		if err != nil || len(hashBytes) != 20 {
			return nil, errors.New("Invalid BitTorrent offer")
		}
		var hash metainfo.Hash
		copy(hash[:], hashBytes)
		item, _ = client.AddTorrentInfoHash(hash)
	}
	if len(trackers) > 0 {
		go func() {
			trackerCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
			defer cancel()
			for _, raw := range trackers[:min(len(trackers), 8)] {
				if tracker := publicTracker(trackerCtx, raw); tracker != "" && trackerCtx.Err() == nil {
					item.AddTrackers([][]string{{tracker}})
				}
			}
		}()
	}
	return item, nil
}

func waitInfo(ctx context.Context, item *torrent.Torrent) error {
	timer := time.NewTimer(35 * time.Second)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-timer.C:
		return errors.New("No peers supplied the torrent metadata. Try another copy")
	case <-item.GotInfo():
		return nil
	}
}

func filesOf(item *torrent.Torrent) []fileInfo {
	files := item.Files()
	out := make([]fileInfo, 0, len(files))
	for i, file := range files {
		out = append(out, fileInfo{Index: i, Path: file.DisplayPath(), Length: file.Length(), Video: isVideo(file.Path())})
	}
	return out
}

// The "files" mode (listFiles): the torrent's name, info hash and files, then the helper ends.
func listFiles(ctx context.Context, config *torrent.ClientConfig, request offer, send func(reply)) error {
	client, err := torrent.NewClient(config)
	if err != nil {
		return errors.New("The built-in torrent engine could not start")
	}
	defer client.Close()
	item, err := add(ctx, client, request)
	if err != nil {
		return err
	}
	if err := waitInfo(ctx, item); err != nil {
		return err
	}
	send(reply{Name: item.Name(), Hash: item.InfoHash().HexString(), Files: filesOf(item)})
	return nil
}

// The "download" mode: keeps the chosen files in request.Dir, which is also where a stopped download picks up again (the pieces
// already there are checked, not fetched twice). It uploads only when the listener allowed seeding.
func download(ctx context.Context, config *torrent.ClientConfig, request offer, send func(reply)) error {
	dir := filepath.Clean(request.Dir)
	if stat, err := os.Stat(dir); request.Dir == "" || !filepath.IsAbs(dir) || err != nil || !stat.IsDir() {
		return errors.New("The download folder is unavailable")
	}
	config.DefaultStorage = nil
	config.DataDir = dir
	config.NoUpload = !request.Seed
	config.Seed = request.Seed
	client, err := torrent.NewClient(config)
	if err != nil {
		return errors.New("The built-in torrent engine could not start")
	}
	defer client.Close()
	item, err := add(ctx, client, request)
	if err != nil {
		return err
	}
	if err := waitInfo(ctx, item); err != nil {
		return err
	}
	chosen := choose(item.Files(), request.Indexes)
	if len(chosen) == 0 {
		return errors.New("Choose at least one file to download")
	}
	for _, file := range chosen {
		file.Download()
	}
	send(reply{Name: item.Name(), Hash: item.InfoHash().HexString(), Files: filesOf(item)})
	return keep(ctx, item, chosen, request.Seed, request.SeedMinutes, time.Second, send)
}

// The files at indexes (all of them for none); indexes outside the torrent are left out.
func choose(files []*torrent.File, indexes []int) []*torrent.File {
	if len(indexes) == 0 {
		return files
	}
	var chosen []*torrent.File
	for _, i := range indexes {
		if i >= 0 && i < len(files) {
			chosen = append(chosen, files[i])
		}
	}
	return chosen
}

// Reports progress every [every] until the chosen files are complete, then, when seeding is allowed, keeps sharing for
// seedMinutes (0 keeps going until the listener stops it from Downloads).
func keep(ctx context.Context, item *torrent.Torrent, chosen []*torrent.File, seed bool, seedMinutes int, every time.Duration, send func(reply)) error {
	var total int64
	for _, file := range chosen {
		total += file.Length()
	}
	ticker := time.NewTicker(every)
	defer ticker.Stop()
	var last int64 = -1
	completed := false
	var seedUntil time.Time
	for {
		var done int64
		for _, file := range chosen {
			done += file.BytesCompleted()
		}
		stats := item.Stats()
		rate := int64(0)
		if last >= 0 {
			rate = (done - last) * 1000 / max(every.Milliseconds(), 1)
		}
		last = done
		if done >= total && !completed {
			completed = true
			send(reply{Complete: true, Progress: &progress{Done: done, Total: total, Peers: stats.ActivePeers, Seeding: seed}})
			if !seed {
				return nil
			}
			if seedMinutes > 0 {
				seedUntil = time.Now().Add(time.Duration(seedMinutes) * time.Minute)
			}
		} else {
			send(reply{Progress: &progress{Done: done, Total: total, Rate: max(rate, 0), Peers: stats.ActivePeers,
				Uploaded: stats.BytesWrittenData.Int64(), Seeding: completed}})
		}
		if completed && !seedUntil.IsZero() && time.Now().After(seedUntil) {
			return nil
		}
		select {
		case <-ctx.Done():
			return nil
		case <-ticker.C:
		}
	}
}

// A torrent of the file or folder at root, its pieces sized so there are about two thousand of them (256 KiB to 16 MiB).
func makeTorrent(root string) (*metainfo.MetaInfo, *metainfo.Info, error) {
	var size int64
	_ = filepath.Walk(root, func(_ string, entry os.FileInfo, err error) error {
		if err == nil && !entry.IsDir() {
			size += entry.Size()
		}
		return nil
	})
	if size == 0 {
		return nil, nil, errors.New("There is nothing to share in that folder")
	}
	pieces := int64(256 << 10)
	for pieces < 16<<20 && size/pieces > 2000 {
		pieces *= 2
	}
	info := metainfo.Info{PieceLength: pieces}
	if err := info.BuildFromFilePath(root); err != nil {
		return nil, nil, errors.New("That folder could not be shared")
	}
	data, err := bencode.Marshal(info)
	if err != nil {
		return nil, nil, errors.New("That folder could not be shared")
	}
	return &metainfo.MetaInfo{InfoBytes: data, CreatedBy: "Cosmotune", CreationDate: time.Now().Unix()}, &info, nil
}

// The "share" mode: makes a torrent of request.Path, sends its magnet link and seeds it from where the files are, for
// SeedMinutes (0: until the listener stops it).
func share(ctx context.Context, config *torrent.ClientConfig, request offer, send func(reply)) error {
	root := filepath.Clean(request.Path)
	if stat, err := os.Stat(root); request.Path == "" || !filepath.IsAbs(root) || err != nil || (!stat.IsDir() && !stat.Mode().IsRegular()) {
		return errors.New("That folder could not be shared")
	}
	meta, info, err := makeTorrent(root)
	if err != nil {
		return err
	}
	config.DefaultStorage = nil
	config.DataDir = filepath.Dir(root)
	config.NoUpload = false
	config.Seed = true
	client, err := torrent.NewClient(config)
	if err != nil {
		return errors.New("The built-in torrent engine could not start")
	}
	defer client.Close()
	item, err := client.AddTorrent(meta)
	if err != nil {
		return errors.New("That folder could not be shared")
	}
	if err := item.VerifyDataContext(ctx); err != nil {
		return errors.New("The files changed while they were being read. Try again")
	}
	magnet := metainfo.Magnet{InfoHash: meta.HashInfoBytes(), DisplayName: info.Name}
	send(reply{Name: info.Name, Hash: meta.HashInfoBytes().HexString(), Magnet: magnet.String(), Files: filesOf(item)})
	return keep(ctx, item, item.Files(), true, request.SeedMinutes, time.Second, send)
}

// The IPv4 address of the network connection called name (a VPN's), when it is up.
func interfaceIPv4(name string) (string, error) {
	iface, err := net.InterfaceByName(name)
	if err != nil || iface.Flags&net.FlagUp == 0 {
		return "", errors.New("The chosen network connection is not up, so torrents did not start")
	}
	addrs, err := iface.Addrs()
	if err != nil {
		return "", errors.New("The chosen network connection is not up, so torrents did not start")
	}
	for _, address := range addrs {
		if network, ok := address.(*net.IPNet); ok && network.IP.To4() != nil {
			return network.IP.String(), nil
		}
	}
	return "", errors.New("The chosen network connection has no address, so torrents did not start")
}

// Stops everything the moment the chosen connection goes down or changes address, so no torrent traffic takes another route.
func watchInterface(ctx context.Context, stop context.CancelFunc, name, host string, every time.Duration, send func(reply)) {
	ticker := time.NewTicker(every)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
		if now, err := interfaceIPv4(name); err != nil || now != host {
			send(reply{Error: "The chosen network connection dropped, so torrents stopped"})
			stop()
			return
		}
	}
}
