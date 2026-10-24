# Lokal review and Cosmotune integration notes

Reviewed 2026-10-23 at [sipbuu/lokal](https://github.com/sipbuu/lokal), revision `3199796ef90743a5abbac6ffc96495beec855a4e`. Source inspected only; no Lokal runtime, provider package, account session or slskd process was executed. Feature status lives in [FEATURE_TRACKER.md](../FEATURE_TRACKER.md); this review does not claim all features implemented.

## Providers and playback

Lokal is an Electron/React local-library application. Its [HTTP add-on client](https://github.com/sipbuu/lokal/blob/3199796ef90743a5abbac6ffc96495beec855a4e/electron/online/addons.js) supports manifest, search, stream and optional album/artist endpoints, source-specific settings and caches. This is the same protocol family Cosmotune already supports, but each client's resource support still needs comparison. Lokal identifies itself to servers; a provider requiring another application's whitelist does not become compatible through changing our user agent.

It also implements a separate Mornye package system. The [QuickJS worker](https://github.com/sipbuu/lokal/blob/3199796ef90743a5abbac6ffc96495beec855a4e/electron/mornye/worker.js) runs extension code away from the UI, with VM memory/stack/time limits and a host bridge. [Package management](https://github.com/sipbuu/lokal/blob/3199796ef90743a5abbac6ffc96495beec855a4e/electron/mornye/packages.js) handles `.sflx`/`.spotiflac-ext` packages and repositories. Cosmotune would need an actual compatible runtime; adding a manifest URL is insufficient. Coordinate this with the Mornye review instead of building two package systems.

Lokal's [download queue](https://github.com/sipbuu/lokal/blob/3199796ef90743a5abbac6ffc96495beec855a4e/electron/download/manager.js) persists jobs, differentiates retryable failures, limits concurrent work and separately paces each add-on. Cosmotune's current global worker limit does not prove equivalent per-provider pacing or restart recovery. Those are tracked gaps.

The same HTTP client caps JSON at 2 MiB. We are adopting that bounded-metadata pattern in both Cosmotune clients, including chunked/unknown-length responses; it applies to metadata, not audio file size. Android shares a tested OkHttp reader; desktop uses streaming Ktor execution so the limit is enforced before an unbounded auto-buffer.

## Discovery, library and quality

Discovery can use Last.fm or YouTube Music account recommendations, with library-first resolution and configurable fallback sources. Its [Last.fm catalogue adapter](https://github.com/sipbuu/lokal/blob/3199796ef90743a5abbac6ffc96495beec855a4e/electron/discoveryCatalogue.js) calls artist top albums/tracks or album information and returns normalized records. These APIs supply metadata/candidates, not audio. Add this to our candidate-source evaluation rather than replacing the local model or assuming a provider feed is our own trained intelligence.

[Artist credit parsing](https://github.com/sipbuu/lokal/blob/3199796ef90743a5abbac6ffc96495beec855a4e/electron/artistSplit.js) preserves known punctuation-bearing names and distinguishes credits from version labels. That is useful for local files and search matching. Structured artist IDs should remain authoritative; a heuristic split must not overwrite a known artist identity.

[Library quality analysis](https://github.com/sipbuu/lokal/blob/3199796ef90743a5abbac6ffc96495beec855a4e/electron/quality/index.js) reads codec/sample rate/bit depth, exposes tiers and includes spectral inspection. The [upgrade module](https://github.com/sipbuu/lokal/blob/3199796ef90743a5abbac6ffc96495beec855a4e/electron/quality/upgrade.js) is separate from playback. A high-frequency cutoff can also be present in authentic masters, so any Cosmotune equivalent must present uncertainty; a FLAC container or spectral heuristic alone cannot prove authenticity. File replacement needs a preview, recording/duration matching and preservation of library identity.

## Feature and interface inventory

The [current feature inventory](https://github.com/sipbuu/lokal/blob/3199796ef90743a5abbac6ffc96495beec855a4e/README.md) also covers multi-source search, local/online artist and album pages, lyrics providers and duets, video synchronization, followed releases, mixes, weekly/monthly/yearly recaps, smart playlists, share cards, editable library columns, linked playlist sync, missing-download repair, duplicates, Cast, a mini-player, LAN phone remote, backups and integrations. Each area has its own row in the central tracker; an existing partial equivalent is not complete feature parity.

For Cosmotune's look, retain our existing design and adopt useful interactions: source/quality information close to the track, compact adaptive lists, clear source and failure state, discovery shelves with purpose, and selection actions that fit the window. Desktop hover and multi-selection cannot simply be copied to a touch screen. Avoid adding Electron to the native apps just to reproduce the appearance.

Soulseek uses a managed [slskd side process](https://github.com/sipbuu/lokal/blob/3199796ef90743a5abbac6ffc96495beec855a4e/electron/download/slskdManaged.js); the LAN remote likewise introduces a server and its authentication/lifecycle. Both need separate cross-platform design and package/runtime measurements. They are not included merely by copying a provider list.

The repository's top-level [licence](https://github.com/sipbuu/lokal/blob/3199796ef90743a5abbac6ffc96495beec855a4e/LICENSE) is MIT. Some files explicitly describe upstream-derived work, so preserve per-file and third-party provenance when selecting code, rather than assuming the top-level badge erases those notices. The current implementation of bounded response reads is written for Cosmotune's existing Kotlin transports.


## Implementation follow-up (2026-10-24)

The earlier review is a source audit. The current shared provider implementation, live public JioSaavn metadata/AAC decoding, Android emulator runtime proof and remaining compatibility gaps are recorded in [PROVIDER_PACKAGES.md](PROVIDER_PACKAGES.md). Music download recovery/pacing and local preference learning also now have implementations and separate evidence records. Editable HTTP add-on settings are implemented on Android and desktop; real HTTP tests verify saved values and preserve connection quality policy. This follow-up does not claim the full upstream application or every provider has been device-tested.
