package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/url"
	"testing"
	"time"

	"github.com/gorilla/websocket"

	"github.com/Vijay-Janarthanan/cosmotune/backend/config"
)

// A real loopback HTTP/WebSocket handshake with browser Origin and token rules.
func TestBrowserPartyLoopbackSmoke(t *testing.T) {
	const origin = "https://player.example"
	previous := config.AllowedOrigins
	config.AllowedOrigins = []string{origin}
	t.Cleanup(func() { config.AllowedOrigins = previous })
	server := setupTestServer()
	defer server.Close()
	client := &http.Client{Timeout: 3 * time.Second}

	for _, check := range []struct {
		origin string
		status int
	}{{origin, http.StatusNoContent}, {"https://unlisted.example", http.StatusForbidden}} {
		request, _ := http.NewRequest(http.MethodOptions, server.URL+"/api/parties", nil)
		request.Header.Set("Origin", check.origin)
		response, err := client.Do(request)
		if err != nil {
			t.Fatal(err)
		}
		response.Body.Close()
		if response.StatusCode != check.status {
			t.Fatalf("preflight status %d, want %d", response.StatusCode, check.status)
		}
		if check.origin == origin && response.Header.Get("Access-Control-Allow-Origin") != origin {
			t.Fatal("allowed browser origin missing from preflight")
		}
	}

	body, _ := json.Marshal(map[string]string{"userId": "browser-smoke", "deviceId": "browser-smoke", "displayName": "Loopback"})
	request, _ := http.NewRequest(http.MethodPost, server.URL+"/api/parties", bytes.NewReader(body))
	request.Header.Set("Origin", origin)
	request.Header.Set("Content-Type", "application/json")
	response, err := client.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusCreated {
		t.Fatalf("create status %d", response.StatusCode)
	}
	var created struct{ Code, Token string }
	if err := json.NewDecoder(response.Body).Decode(&created); err != nil {
		t.Fatal(err)
	}
	endpoint, _ := url.Parse(server.URL)
	endpoint.Scheme, endpoint.Path = "ws", "/ws/parties/"+created.Code
	dialer := websocket.Dialer{HandshakeTimeout: 3 * time.Second, Subprotocols: []string{socketProtocol, socketTokenPrefix + created.Token}}
	bad, denied, err := dialer.Dial(endpoint.String(), http.Header{"Origin": {"https://unlisted.example"}})
	if bad != nil {
		bad.Close()
	}
	if denied != nil {
		denied.Body.Close()
	}
	if err == nil || denied == nil || denied.StatusCode != http.StatusForbidden {
		t.Fatal("unlisted browser origin was not rejected")
	}

	connection, accepted, err := dialer.Dial(endpoint.String(), http.Header{"Origin": {origin}})
	if err != nil {
		t.Fatalf("allowed browser handshake failed: %v", err)
	}
	defer connection.Close()
	if accepted.Header.Get("Sec-WebSocket-Protocol") != socketProtocol {
		t.Fatal("the server did not select the public protocol alone")
	}
	connection.SetReadDeadline(time.Now().Add(3 * time.Second))
	var welcome struct{ Type string }
	if err := connection.ReadJSON(&welcome); err != nil || welcome.Type != "welcome" {
		t.Fatal("browser did not receive the initial party snapshot")
	}
}
