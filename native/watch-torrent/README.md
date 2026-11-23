# Watch torrent engine

The Cosmotune release workflow builds this helper from source with pinned Go modules. It uses [anacrolix/torrent](https://github.com/anacrolix/torrent) for peer discovery, verified pieces and seek-aware reads. It serves one selected video over an ephemeral loopback HTTP endpoint, with up to 96 MiB of piece data in memory. Closing playback terminates the helper.

Run `python native/watch-torrent/build.py desktop` before a local desktop build. For phone and TV, run `python native/watch-torrent/build.py android --ndk /path/to/ndk` before building the APKs. Both commands require Go 1.26 or newer and use the installed Go toolchain. Gradle picks up the generated files under `build/watch-torrent`.

Besides streaming (the default), the request's `Mode` asks for `files` (the torrent's name, hash and file list), `download` (the chosen `Indexes` kept in `Dir`, which is also where a stopped download resumes; uploads only with `Seed`, for `SeedMinutes` after it is done) and `share` (a torrent made of `Path`, its magnet link sent, then seeded). `Torrent` carries a .torrent file the listener opened (base64) instead of `Hash`; its trackers are checked like an add-on's and its web seeds dropped. `Bind` names one network connection (a VPN's): the client listens only on its address and every mode stops as soon as it goes down. The helper never searches for torrents.

Run `go test ./...` in this directory to stream and seek from a local BitTorrent peer without contacting public trackers.

## Dependency security refresh (2026-11-16)

Android cross-builds require both target NDK `clang` and `clang++`; Go otherwise may choose the host C++ linker for Android objects. `build.py` supplies the matching compilers for every ABI and statically links the C++ runtime. The three build regressions pass. Actual NDK 27 builds for arm64-v8a, armeabi-v7a and x86_64 were inspected for CPU identity, Android interpreter, 16 KB LOAD alignment and absence of a shared C++ runtime dependency. This qualifies compilation/linkage, not playback on a physical Android device.

The module pins the reviewed security fixes: anacrolix/torrent `v1.61.1-0.20260911233437-76452a2c8a2f`, gorilla/websocket `v1.5.3`, pion/dtls/v3 `v3.1.4`, pion/stun/v3 `v3.1.5`, x/crypto `v0.59.0`, x/net `v0.62.0`, x/text `v0.44.0`, and the OpenTelemetry root/log/metric/trace family `v1.47.0`. Required transitive modules and checksums are resolved by Go's module graph; checksum verification remains enabled. The complete security refresh raises the minimum Go version from 1.24 to 1.26. No helper protocol or storage API changes were needed.

Validated locally with the official SHA-256-verified Go 1.27.4 Windows/amd64 archive: the release-tag local-peer Go suite passed, and `build.py desktop --go` with that exact compiler regenerated both the Windows helper and its third-party notices. The helper build metadata confirms Go 1.27.4, CGO disabled, and the patched module versions. Official `govulncheck v1.9.0` source/symbol and full-module scans both exited successfully with zero findings against the Go vulnerability database last modified 2026-11-11. The scan covers known database entries; it does not establish that no vulnerabilities exist.

The release-path checks are `CGO_ENABLED=0 go test -mod=readonly -tags=disable_libutp -count=1 -timeout=90s ./...` and `CGO_ENABLED=0 go build -mod=readonly -tags=disable_libutp -trimpath .`. The local-peer test exercises verified streaming and HTTP range seeks without public trackers. It does not certify public-peer/UTP interoperability or Android ABI/device behavior. Rebuild the distributed helpers and their generated third-party notices after a module update.
