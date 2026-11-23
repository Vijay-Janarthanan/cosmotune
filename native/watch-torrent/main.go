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
	"sync"
	"time"

	"github.com/anacrolix/torrent"
	"github.com/anacrolix/torrent/iplist"
)

const cacheBytes = 96 << 20

// One request on stdin. Mode "" streams one video over loopback HTTP, "files" lists what the torrent holds, "download" keeps the
// chosen files (Indexes) in Dir and may seed afterwards, "share" makes a torrent of Path and seeds it. The torrent is an info
// hash (Hash) or the bytes of a .torrent file the listener opened (Torrent, base64). Bind keeps every connection on one network
// connection (a VPN's) and stops when it drops.
type offer struct {
	Hash        string
	Index       *int
	Filename    string
	Trackers    []string
	Mode        string
	Torrent     string
	Dir         string
	Path        string
	Indexes     []int
	Seed        bool
	SeedMinutes int
	Bind        string
}

type reply struct {
	URL      string     `json:"url,omitempty"`
	Error    string     `json:"error,omitempty"`
	Name     string     `json:"name,omitempty"`
	Hash     string     `json:"hash,omitempty"`
	Files    []fileInfo `json:"files,omitempty"`
	Progress *progress  `json:"progress,omitempty"`
	Complete bool       `json:"complete,omitempty"`
	Magnet   string     `json:"magnet,omitempty"`
}

func main() {
	input := bufio.NewScanner(os.Stdin)
	input.Buffer(make([]byte, 64<<10), 8<<20)
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
	var lock sync.Mutex
	encoder := json.NewEncoder(os.Stdout)
	send := func(message reply) {
		lock.Lock()
		defer lock.Unlock()
		_ = encoder.Encode(message)
	}
	config := torrent.NewDefaultClientConfig()
	config.ListenPort = 0
	config.NoDefaultPortForwarding = true
	config.NoUpload = true
	config.DisableWebtorrent = true
	config.DisableWebseeds = true
	config.IPBlocklist = nonPublicPeers{}
	config.EstablishedConnsPerTorrent = 32
	config.DefaultStorage = &pieceCache{capacity: cacheBytes}
	if request.Bind != "" {
		host, err := interfaceIPv4(request.Bind)
		if err != nil {
			send(reply{Error: err.Error()})
			return
		}
		config.ListenHost = func(string) string { return host }
		config.DisableIPv6 = true
		go watchInterface(ctx, cancel, request.Bind, host, 2*time.Second, send)
	}
	var err error
	switch request.Mode {
	case "files":
		err = list(ctx, config, request, send)
	case "download":
		err = download(ctx, config, request, send)
	case "share":
		err = share(ctx, config, request, send)
	default:
		err = run(ctx, config, request, send)
	}
	if err != nil && ctx.Err() == nil {
		send(reply{Error: err.Error()})
	}
}

func run(ctx context.Context, config *torrent.ClientConfig, request offer, send func(reply)) error {
	client, err := torrent.NewClient(config)
	if err != nil {
		return errors.New("The built-in torrent engine could not start")
	}
	defer client.Close()
	item, err := add(ctx, client, request)
	if err != nil {
		return err
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
	if err := waitInfo(ctx, item); err != nil {
		return nil, err
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
