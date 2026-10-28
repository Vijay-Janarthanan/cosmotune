# Listen Together backend validation — 28 October 2026

The source builds and passes its local Go checks. A public Cosmotune server has **not** been deployed or selected by this work.

## Toolchain and reproducible checks

`backend/go.mod` requires Go 1.27.0. The [official Go downloads](https://go.dev/dl/) publish Go 1.27.3; that supported patch release was used without changing the module requirement. The portable Windows amd64 archive is 79,010,309 bytes, verified against the official SHA256 `f26081f438319757f8a1b46d7d0b2c5ec7583d4038e485e0aa725cfde7885fff` before extraction under ignored `build/tooling/go`. Installation did not alter global PATH or operating-system services. The Go module dependency was verified through the normal Go proxy/checksum mechanism and committed `go.sum`.

From `backend/`, with the portable toolchain on the current command's path only:

```sh
gofmt -l .
go vet ./...
go test -count=1 -timeout=90s ./...
CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go build -trimpath -o ../build/backend-validation/cosmotune-server-windows-amd64.exe .
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -o ../build/backend-validation/cosmotune-server-linux-amd64 .
CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -trimpath -o ../build/backend-validation/cosmotune-server-linux-arm64 .
```

Results: formatting and vet pass; **44 top-level tests / 47 passing cases including subtests**, six packages with tests and no failed cases. `go mod verify` passes, and the complete suite also passes with `GOPROXY=off` and `-mod=readonly`. The Windows amd64 binary is 14,064,128 bytes, Linux amd64 13,881,971 bytes and Linux arm64 12,988,258 bytes. Linux binaries were cross-compiled, not executed on Windows. Sanitized test events are in ignored `build/backend-validation/tests.jsonl`; archive metadata is in `build/tooling/go-download.json`.

The real HTTP/WebSocket smoke uses Go's ephemeral **loopback-only** `httptest` server. It creates a synthetic party and verifies allowed browser CORS preflight, unlisted-origin rejection, authenticated WebSocket subprotocol selection without echoing the token, and a welcome snapshot. Existing integration tests exercise REST, native WebSockets, queue/control behaviour, rate limiting, presence and invite handling. It stops its own listener on completion and does not use a user account, occupied application port or public server.

## Repairs made during validation

- `gofmt` normalized backend source formatting. Backend-local `.gitattributes` keeps Go files LF on Windows so future formatting checks remain reproducible.
- The legacy deploy helper cloned the upstream repository. It now clones Cosmotune and refuses an existing checkout whose origin is a different repository. It preserves existing service/directory names for compatibility; it does not silently migrate or overwrite another deployment.
- A browser handshake regression now tests the exact Origin/subprotocol path used by the browser companion, rather than treating a native no-Origin handshake as browser proof.

## Before any public deployment

The operator must select an owned hosting target and its plan/billing, approve deployment credentials, and provide an HTTPS party domain with DNS/TLS. Configure the real browser player's exact origins in `JAM_ALLOWED_ORIGINS`, set `JAM_PUBLIC_ORIGIN`, and point the native/browser app settings at that address. The Shutter Island product/download site is not automatically a deployed player or party backend.

Keep one server instance: rooms and membership are in memory, and process restarts lose them. Choose capacity and availability appropriate to the selected plan, then check two real clients, browser/native controls, reconnection, queue handoff and idle behaviour on that target. Do not assume the old template's service name, Unix user or checkout path identifies an owned Cosmotune deployment.

The Ubuntu script is a privileged provisioning template; it was **not executed**. It installs packages, configures a service/firewall and restarts processes, so it must be reviewed for the selected server. The Render template was not applied. Caddy currently answers `/healthz` itself: a successful proxy health response does not prove the Go process is alive. Test the application endpoint directly and perform a real create/join/WebSocket check after deploying. No Dockerfile/container image exists in this backend.

The existing manual Check workflow already runs backend build, vet and tests using the version from `go.mod`. This local result does not establish that its latest GitHub run passed; reconcile CI with the exact commit being released.
