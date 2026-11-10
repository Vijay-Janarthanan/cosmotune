package main

import (
	"bufio"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"os"
	"path"
	"path/filepath"
	"strings"
	"time"

	"github.com/anacrolix/torrent"
	"github.com/anacrolix/torrent/iplist"
	"github.com/anacrolix/torrent/metainfo"
)

const cacheBytes = 96 << 20

type offer struct {
	Hash     string
	Index    *int
	Filename string
	Trackers []string
}

type reply struct {
	URL   string `json:"url,omitempty"`
	Error string `json:"error,omitempty"`
}

func main() {
	input := bufio.NewScanner(os.Stdin)
	input.Buffer(make([]byte, 4096), 8192)
	var request offer
	if !input.Scan() || json.Unmarshal(input.Bytes(), &request) != nil {
		return
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() {
		_, _ = io.Copy(io.Discard, os.Stdin)
		cancel()
	}()
	send := func(message reply) { _ = json.NewEncoder(os.Stdout).Encode(message) }
	config := torrent.NewDefaultClientConfig()
	config.ListenPort = 0
	config.NoDefaultPortForwarding = true
	config.NoUpload = true
	config.DisableWebtorrent = true
	config.DisableWebseeds = true
	config.IPBlocklist = nonPublicPeers{}
	config.EstablishedConnsPerTorrent = 32
	config.DefaultStorage = &pieceCache{capacity: cacheBytes}
	if err := run(ctx, config, request, send); err != nil && ctx.Err() == nil {
		send(reply{Error: err.Error()})
	}
}

func run(ctx context.Context, config *torrent.ClientConfig, request offer, send func(reply)) error {
	hashBytes, err := hex.DecodeString(request.Hash)
	if err != nil || len(hashBytes) != 20 {
		return errors.New("Invalid BitTorrent offer")
	}
	var hash metainfo.Hash
	copy(hash[:], hashBytes)
	client, err := torrent.NewClient(config)
	if err != nil {
		return errors.New("The built-in torrent engine could not start")
	}
	defer client.Close()
	item, _ := client.AddTorrentInfoHash(hash)
	if len(request.Trackers) > 0 {
		go func() {
			trackerCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
			defer cancel()
			for _, raw := range request.Trackers[:min(len(request.Trackers), 8)] {
				if tracker := publicTracker(trackerCtx, raw); tracker != "" && trackerCtx.Err() == nil {
					item.AddTrackers([][]string{{tracker}})
				}
			}
		}()
	}
	file, err := resolveFile(ctx, item, request)
	if err != nil {
		return err
	}
	return serve(ctx, file, send)
}

var carrierNAT = netip.MustParsePrefix("100.64.0.0/10")

func publicIP(ip net.IP) bool {
	address, ok := netip.AddrFromSlice(ip)
	if !ok {
		return false
	}
	address = address.Unmap()
	return address.IsGlobalUnicast() && !address.IsPrivate() && !carrierNAT.Contains(address)
}

type nonPublicPeers struct{}

func (nonPublicPeers) NumRanges() int { return 1 }
func (nonPublicPeers) Lookup(ip net.IP) (iplist.Range, bool) {
	return iplist.Range{First: ip, Last: ip}, !publicIP(ip)
}

func publicTracker(ctx context.Context, raw string) string {
	target, err := url.Parse(raw)
	if err != nil || target.Scheme != "udp" || target.Port() == "" || target.User != nil ||
		target.RawQuery != "" || target.Fragment != "" || target.Hostname() == "" {
		return ""
	}
	ips, err := net.DefaultResolver.LookupIPAddr(ctx, target.Hostname())
	if err != nil {
		return ""
	}
	for _, ip := range ips {
		if publicIP(ip.IP) {
			target.Host = net.JoinHostPort(ip.IP.String(), target.Port())
			return target.String()
		}
	}
	return ""
}

func serve(ctx context.Context, file *torrent.File, send func(reply)) error {
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		return errors.New("The player could not open its local streaming connection")
	}
	defer listener.Close()
	secret := make([]byte, 24)
	if _, err := rand.Read(secret); err != nil {
		return errors.New("The player could not create a streaming session")
	}
	route := "/" + hex.EncodeToString(secret) + "/video" + strings.ToLower(path.Ext(file.Path()))
	server := &http.Server{
		ReadHeaderTimeout: 5 * time.Second,
		BaseContext:       func(net.Listener) context.Context { return ctx },
		Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.URL.Path != route || (r.Method != http.MethodGet && r.Method != http.MethodHead) {
				http.NotFound(w, r)
				return
			}
			reader := file.NewReader()
			defer reader.Close()
			reader.SetContext(r.Context())
			reader.SetReadahead(4 << 20)
			w.Header().Set("Content-Type", "application/octet-stream")
			http.ServeContent(w, r, path.Base(file.Path()), time.Time{}, reader)
		}),
	}
	go func() { _ = server.Serve(listener) }()
	send(reply{URL: fmt.Sprintf("http://%s%s", listener.Addr(), route)})
	<-ctx.Done()
	return server.Close()
}

func resolveFile(ctx context.Context, item *torrent.Torrent, request offer) (*torrent.File, error) {
	timer := time.NewTimer(35 * time.Second)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return nil, ctx.Err()
	case <-timer.C:
		return nil, errors.New("No peers supplied the torrent metadata. Try another copy")
	case <-item.GotInfo():
	}
	files := item.Files()
	if request.Index != nil {
		i := *request.Index
		if i >= 0 && i < len(files) && isVideo(files[i].Path()) && files[i].Length() > 0 {
			return files[i], nil
		}
		return nil, errors.New("The add-on selected a file that cannot be played")
	}
	hint := filepath.Base(strings.ReplaceAll(request.Filename, "\\", "/"))
	var largest, matching *torrent.File
	for _, file := range files {
		if !isVideo(file.Path()) || file.Length() == 0 {
			continue
		}
		if strings.EqualFold(path.Base(file.Path()), hint) {
			matching = file
		}
		if largest == nil || file.Length() > largest.Length() {
			largest = file
		}
	}
	if matching != nil {
		return matching, nil
	}
	if largest == nil {
		return nil, errors.New("This torrent contains no supported video file")
	}
	return largest, nil
}

func isVideo(name string) bool {
	switch strings.ToLower(path.Ext(name)) {
	case ".mkv", ".mp4", ".m4v", ".webm", ".mov", ".avi", ".ts", ".m2ts", ".mpg", ".mpeg":
		return true
	default:
		return false
	}
}
