# Watch torrent engine

The Cosmotune release workflow builds this helper from source with pinned Go modules. It uses [anacrolix/torrent](https://github.com/anacrolix/torrent) for peer discovery, verified pieces and seek-aware reads. It serves one selected video over an ephemeral loopback HTTP endpoint, with up to 96 MiB of piece data in memory. Closing playback terminates the helper.

Run `python native/watch-torrent/build.py desktop` before a local desktop build. For phone and TV, run `python native/watch-torrent/build.py android --ndk /path/to/ndk` before building the APKs. Both commands use the installed Go toolchain. Gradle picks up the generated files under `build/watch-torrent`.

Run `go test ./...` in this directory to stream and seek from a local BitTorrent peer without contacting public trackers.
