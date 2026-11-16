# Upstream review — 16 November 2026

Baseline: Cosmotune **1.0.23**, commit `4237679e30fad42f66c9d6403851c51655bb450a`. The working tree was clean when the review began. This is a source/release comparison and integration proposal, **not an implementation or release**.

## 1.0.24 implementation follow-up

The original comparison below is retained as review evidence. Its selected implementation is now recorded in [audio/providers](UPSTREAM_AUDIO_2026-11-16.md), [library/downloads](UPSTREAM_LIBRARY_2026-11-16.md), [Watch](UPSTREAM_WATCH_2026-11-16.md), [Manga](UPSTREAM_MANGA_2026-11-16.md), [player](UPSTREAM_PLAYER_2026-11-16.md), [Podio](UPSTREAM_PODIO_2026-11-16.md) and [system playback](UPSTREAM_SYSTEM_2026-11-16.md). [Release notes](releases/1.0.24.md) distinguish implemented behavior, automated validation and hardware limits. Parser dependency/contract migrations and platform-specific signed loaders are part of this follow-up, rather than the original comparison.

## Method and scope

Checked the 20 repositories from the previous inventory, plus AETHER, Euthopiar, OpenCanvas and the previously selected Mochy ports: **24 repositories, 13 advanced, 11 unchanged, 848 additional commits**. Commits include merges, tests, reverts, repeated fixes and dependency work; 848 does not mean 848 new features.

Used the [9 November review](UPSTREAM_REVIEW_2026-11-09.md) where available, the [2 November snapshot](UPSTREAM_SNAPSHOT_2026-11-02.json) for unchanged older pins, the [11 November AETHER/Euthopiar review](UPSTREAM_REVIEW_AETHER_EUTHOPIAR_2026-11-11.md), and Mochy's selected-port pin in [UPSTREAM.md](../UPSTREAM.md). The [new snapshot](UPSTREAM_SNAPSHOT_2026-11-16.json) records full base/head hashes, branches and counts. OpenCanvas's base agrees with the actual import manifest.

Fetched all comparison commit pages, not just the first 100. Read commit subjects across the ranges, release notes and selected implementation/test diffs, then checked corresponding Cosmotune code. This is not a line-by-line review of every changed file. GitHub's comparison file list caps at 300: full raw diffs were also fetched for LastWave Native (**377 files**) and Mochy (**676 files**). Euthopiar's comparison JSON supplied zero line statistics for many changed files; its full diff was read and selected hunks normalized to establish actual behavior changes. No upstream app was installed, no account authenticated and no playback was exercised.

Raw evidence is under ignored `build/upstream-reviews/2026-11-16/` (comparison JSON, complete commit pages, releases, release-tag pins and full diffs). The tracked snapshot makes the comparison reproducible without relying on that cache.

## Changed repositories

| Repository / comparison | New commits | Head | Findings |
| --- | ---: | --- | --- |
| [LastWave Native](https://github.com/Clash-Projects/LastWave-Native/compare/276d94579b...7070bb2ea203e58b78ba8638caa77a5254c7cc9a) | 270 | `7070bb2ea2` | Audio/USB/normalization, quality-switch guards, download correctness, queue/like identity, less background work, landscape/player lyrics and recognition. Significant refactoring and intermediate compile repairs are mixed into the range. |
| [Lokal Mobile](https://github.com/lokal-music/lokal-mobile/compare/616877c428...a2b79d5895b339c91a39c1342dd745620d6bff41) | 239 | `a2b79d5895` | Cast ownership/recovery/receiver acknowledgements, tablet layouts, portable M3U and pending tracks, per-download provider quality, stable local favorites, exact catalogue identities and portrait continuity. |
| [Lokal Desktop](https://github.com/lokal-music/lokal/compare/72630a26e4...5077665df6662115b75bad9dd0fbd8ca4fcc34ef) | 169 | `5077665df6` | Exact artist/album IDs and portraits, library/watch startup performance, duplicate-safe downloads, Spotify playlist completeness, M3U relocation, per-job quality and LAN/Cast repairs. |
| [Mochy Music](https://github.com/realidkroo/Mochy-Music/compare/8dd8f3b0...6e5b13711785035efa15054788b86d346531c224) | 105 | `6e5b137117` | TV remote/player work, crossmix/skip behavior, queue occurrence rules, playlist art, per-song speed/pitch, storage choices, optional Bluetooth/mute behavior, metadata/interconnectivity controls, foldables and later Spotify Canvas work. Many terse commit messages require file-level follow-up before a port. |
| [Kotatsu Parsers Redo](https://github.com/Kotatsu-Redo/kotatsu-parsers-redo/compare/68f3fed3952f5c9cf1ad8cc00bdb1ff047688dd8...a90648aebdf61c47b9eed4bec351332306aeff6a) | 23 | `a90648aebd` | Source repairs/removals and a signed separately published Android parser bundle. |
| [gPodder](https://github.com/gpodder/gpodder/compare/629c652bc4...6109e60dae1d0ba12dbf9d7998a71f67b2572be7) | 11 | `6109e60dae` | yt-dlp updater on Windows/macOS, duration detection fallbacks, MPRIS tab-close handling, iPod path/string safety. |
| [Mihon](https://github.com/mihonapp/mihon/compare/7eec566d9c...4823ba69ca65fa707fb89406f3a4c43c9e0053dd) | 10 | `4823ba69ca` | Compose scrollbar API migration, DI cleanup, lint and toolchain/dependency updates. |
| [AntennaPod](https://github.com/AntennaPod/AntennaPod/compare/08107984b8...45de161929a2ab0da97e7481439c10d48517870a) | 6 | `45de161929` | Preserve colons in speaker-labelled SRT transcripts, chapter artwork in Auto/notification, inbox freshness after subscribing, proxy-reset and test fixes. |
| [Euthopiar](https://github.com/TheARCreactorGUY/Euthopiar-/compare/b7172cbcb972cacdcae7fb35f87ac8cffa40d850...b6bb9210f1354a964258af5ffa29e4c38ecef506) | 4 | `b6bb9210f1` | 1.2.0: application-scoped batch episode downloads, adaptive landscape/tablet geometry, sync efficiency, malformed source URL and add-on episode/type matching repairs. |
| [LastWave Desktop](https://github.com/Clash-Projects/LastWave-Desktop/compare/d997c06b33...2436953aaa37a5f3a1667443ae311461a1631fe2) | 3 | `2436953aaa` | macOS native Now Playing/media keys through MediaPlayer, release and issue-template changes. |
| [Tsuki](https://github.com/TsukiAndroid/Tsuki/compare/264d7d6eae...f410269b9bf705fcb475f58668138a270d8e11b5) | 3 | `f410269b9b` | Parser dependency advances to `d8589131c9`; no separate reader feature in this range. |
| [anypod](https://github.com/thurstonsand/anypod/compare/baf34d7710...96f508d63fd1fec45a9cf655ed87614dff71eceb) | 3 | `96f508d63f` | Dependency updates, three-day release-age policy except yt-dlp, workflow lint. |
| [AETHER](https://github.com/GHOSTxASR/AETHER/compare/fd019594548e52be06f6339bb758ff5661f15f3d...d952c18c57849f8a150373fa2b6acaa7cc5538e2) | 2 | `d952c18c57` | 2.2/2.3: unsupported locale recovery, detected visitor country, provider reconnect/backoff, output-route gating, playlist sync/sorting, missing artists and M4A export tags. |

Unchanged against their last reviewed branches: **BitChord, Mornye, Mornye Extension, OpenCanvas, Podgrab, Dotify, audimo, p-fm-bot, votify, spotifeed and podpull**. No new provider availability or fixed gateway follows from unchanged Mornye repositories.

## Releases versus main

- [AETHER 2.3.0](https://github.com/GHOSTxASR/AETHER/releases/tag/v2.3.0): published 11 November; release and reviewed head `d952c18c57` agree. 2.2 was also published on 11 November.
- [Euthopiar 1.2.0-Stable](https://github.com/TheARCreactorGUY/Euthopiar-/releases/tag/v1.2.0-Stable): published 15 November; release commit `b6bb9210f1` agrees with the reviewed head.
- [LastWave Native 5.2.1](https://github.com/Clash-Projects/LastWave-Native/releases/tag/5.2.1): published 13 November, tag resolves to `6db7b99bb2`. The reviewed head is newer. Do not advertise the whole comparison, including later recognition/inline-lyrics work, as features verified in that APK.
- [LastWave Desktop 2.1.0](https://github.com/Clash-Projects/LastWave-Desktop/releases/tag/v2.1.0): published 10 November in Asia/Calcutta. The macOS bridge is a later main-branch commit, so the release name alone does not certify it is packaged there.
- [Mochy 8.4.1](https://github.com/realidkroo/Mochy-Music/releases/tag/mochy-8.4.1): published 14 November, tag `eafbbe6d1c`; the reviewed main head is newer. Upstream itself labels this beta. Later Canvas/history/export/orientation changes must be distinguished from that release.
- Lokal uses rolling release labels (`desktop`, `android`, and iOS channels); compare source pins and asset qualification rather than treating a rolling label as a permanent source revision.

## What to bring into Cosmotune

### 1. Playback and audio correctness — first

LastWave Native changes worth detailed porting:

- `763cbe9875`: avoid quiet YouTube DRC renditions, correct normalization/Dolby makeup gain. Cosmotune has loudness normalization in `shared/.../audio/LoudnessNormalizer.kt` and uses stream loudness metadata, but the inspected shared resolver has no explicit `isDrc` handling. Trace format selection and gain together before changing it; do not stack two normalization gains or infer Atmos from provider flags.
- `e087367045`: prevent a mid-song upgrade to another language/version; `159ebcfc79`, `ffde06b83f`, `0fb4fb377b`: quality switching and bit-perfect transition handling. Reuse Cosmotune's recording matcher and existing upgrade audition. Automatic acoustic upgrade alignment remains deliberately off in the 1.0.22 scope until real-rendition/clock validation, not something to enable merely because another project does.
- `60dbf4b97d`, `0e7e583019`, `d20b9449df`: explicit fast-start/best-quality-first/no-upgrade policy and preload the next song in chosen quality. Assess against the existing resolver race and prepared next deck. Add one shared policy exposed consistently on phone/desktop, not platform-specific unexplained defaults.
- `8842d3d1de`, `68992a8018`, `6db7b99bb2`: measure downloaded Atmos from bytes, produce seekable FLAC when extracting fMP4, parse full-box timing correctly. Inspect our existing tag/remux paths before adapting algorithms.
- USB clock interface, volume scaling and scratch-buffer work (`6d2c30c7e2`, `8fad9a715e`, `5ce8215409`, `15114b4ef0`) needs checks against Cosmotune's own native audio paths and actual DACs; this is not a safe blanket JNI import.

Tests before shipping: original/dubbed/reprise pairs, DRC/non-DRC offers, no double gain, failed upgrade keeps old audio, paused/seek/next during upgrade, prepared-next quality, seekable downloaded FLAC and truthful output-format reporting. Run through Android Media3 and desktop decoders separately.

### 2. Providers and YouTube locale recovery

AETHER 2.3 now retries sources that failed during startup on network return and periodically, exposes the reason/manual retry, and backs off repeated failures. It asks for a stream before using a search-row URL fallback. Its YouTube client distinguishes stale configuration, rejected language/country and ended sign-in.

Cosmotune already has provider sessions, network restrictions, health checks (`ProviderServiceHealth.kt`), Retry-After handling (`ProviderSignedSession.kt`) and fixed language/region mappings (`YouTubeLocaleRegionTest`). These do **not** establish a shared automatic recovery/circuit-breaker policy across all providers. Port the behavior into the existing runtime/resolvers, keeping transient network/rate-limit errors separate from invalid authentication; temporary errors must not disable the provider or erase credentials.

For unsupported locale recovery, add bounded request/response fixtures to the shared InnerTube path. Preserve the user's explicit discovery region/language choices; AETHER's detected-country default must not silently override Cosmotune's regional controls.

Do not directly adopt AETHER's `LosslessOutput` boolean: it intentionally accepts high-bitrate Bluetooth families for an add-on eligibility rule. Eligibility, codec reporting and an end-to-end lossless claim are distinct. Keep Cosmotune's measured quality labels and unknown-codec states.

### 3. Catalogue identity, artist portraits and local library integrity

Lokal's largest useful change is identity-safe catalogue navigation. Search, album/artist pages, follows, caches and saved albums retain verified provider IDs. Ambiguous namesakes do not replace an exact match; saved portraits stay visible while newer images resolve. Relevant changes include `5764a7c18d`, `0319af7688`, `7c4271443f`, `34030203cf` and `7dd0f148fe` in desktop, with matching mobile work.

Cosmotune already models browse/artist/album/source IDs and has artwork fallback/cache code. Audit the **whole path**—global search → page → follow/save → reopen → rescan—rather than add more name aliases. Add shared fixtures for same-name artists, translated titles, reindexed favorites, stale artwork and delayed/out-of-order results. Use the existing identity models on both platforms.

Lokal's startup/watch improvements are also candidates: index changed files instead of rescanning roots, keep filesystem probes out of SQLite write transactions, don't run recurring legacy repair work on every launch, and keep hidden pages from launching network lookups. Adapt this policy to our JVM/database implementation; Electron IPC and Flutter widgets do not transplant.

### 4. Casting and macOS system controls

Lokal Mobile now checks receiver identity/epochs on asynchronous callbacks, requires explicit STOP acknowledgement for autoplay handover, restores the receiver's last position, handles suspension separately from termination, and offers Return to phone when a receiver disappears. It coordinates Cast-owned notifications, media buttons and hardware volume. Desktop receiver next/previous commands route through the sender queue.

Cosmotune 1.0.22 already added and tested Watch DLNA ownership/disposal fixes (`WatchCastingTest`), and has separate music Cast/DLNA machinery. Audit **both**, then adapt missing receiver-identity and acknowledgement guards without replacing the fixed Watch lifecycle. Watch Google Cast remains a distinct unimplemented feature.

LastWave Desktop's new `macos/Runner/NowPlayingChannel.swift` publishes `MPNowPlayingInfoCenter` metadata and routes `MPRemoteCommandCenter` commands, with an anchored timeline so late artwork does not reset progress. Cosmotune has Windows media controls and Linux MPRIS, but no corresponding MediaPlayer bridge was found in the inspected desktop source. Implement a macOS bridge behind our existing player controls via a small native/JNA/JNI boundary, not Flutter's method channel. Test play/pause/seek/next, artwork races, stopped-state cleanup and mode ownership. LastWave Desktop's reviewed licence situation needs rechecking before source reuse; independent implementation against Apple's APIs is the fallback.

### 5. Watch batch downloads and adaptive screens

[Euthopiar 1.2](https://github.com/TheARCreactorGUY/Euthopiar-/releases/tag/v1.2.0-Stable) adds:

- Application-owned `BatchDownloadCoordinator`, surviving details-page disposal; season/episode selection, unwatched/all/custom filtering, quality preference, progress and counts for queued/skipped/failed items.
- Wide/tall window geometry, navigation clearance and adaptive details/settings/player sheets; fixes stacked season selectors and episode lists in landscape.
- Five-minute auto-pull cooldown and per-domain content-hash caching for sync, plus a UI blur performance switch.
- Episode-containing add-on metadata can be recognized as series even when its type is misleading; release matching excludes generic technical metadata; source URL normalization handles spaces/brackets/inline headers.

Our `WatchDownloads` has direct-file downloads, retry, storage preflight and selected destinations, but no comparable season batch resolver/selection queue was found. Video mode's batch-download machinery is separate. Build the Watch coordinator at repository/application scope, persist jobs before navigation/process loss, and reuse the existing downloader instead of duplicating it. Add pause/resume and actual-process-restart cases; state surviving navigation alone is not disk durability.

Cosmotune already has phone/desktop shared Watch frames and 1.0.22 profile/settings work. Add landscape/foldable size and keyboard-inset render/interaction tests to those components; preserve Orbit. Adapt sync cooldown/hash ideas to existing **Firebase** journals/`CloudAutoSync`, not Euthopiar's Supabase schema or collection caps. Normalize source URLs with a proper URL parser and bounded validated headers; do not weaken redirect/network restrictions.

Euthopiar has no licence in the previously reviewed tree and the fetched change does not introduce one. Independently implement these behaviors; do not copy its classes.

### 6. Portable playlists and explicit download quality

Both Lokal apps now support relocated M3U libraries using unique path-tail/filename and title/artist evidence; ambiguous editions are not silently substituted. Mobile keeps unmatched entries as durable pending items. Both expose a provider manifest's per-download quality choice and carry it through queue/IPC/runtime with provider identity retained.

Cosmotune already has `MusicPlaylistImport.kt`, EXTINF labels, granted-path/URI matching, explicit catalogue review and generic per-job download quality overrides. Incremental work is longest-unique relocated-path matching within the **granted library**, durable unresolved rows, and a provider-specific manifest quality picker. Do not auto-open arbitrary imported paths or auto-search/replace an unknown recording. Job identity must distinguish intentional quality/provider choices while suppressing accidental duplicate work.

### 7. Manga source updates

Our `manga/build.gradle.kts` remains pinned to `68f3fed395`. The new parser range repairs or retargets LegionScans, KomikLokal, BatoTo, MangaPark and KlikManga; adds Comizy; removes broken redirect clones/duplicate NetTruyen entries. Some changed sources are adult sources: **retain the existing adult/name exclusion policy**, including the user's `hent` restriction, after every catalogue update.

Start with a compatible pinned parser refresh and compile/search/chapter/page fixtures on Android and JVM. The new [separate signed bundle protocol](https://github.com/Kotatsu-Redo/kotatsu-parsers-redo/blob/master/docs/BUNDLE.md) publishes Android DEX, not a desktop JVM JAR. Dynamic updates require signature/hash/size/version/API checks, atomic install/rollback and a desktop-compatible build/loader. Do not feed Android DEX into the desktop or silently load unchecked executable code. Tsuki's three new commits are dependency bumps; they do not themselves demonstrate integration of this complete dynamic updater.

### 8. Additional music UI and companion ideas

LastWave's inline Player Lyrics, optional auto/center alignment, landscape lyrics/art layout, less offscreen animation/ticker work and request coalescing are useful ideas. Keep the current Orbit lyric renderer, duet lanes, word timing, accessibility and Android/desktop consistency. Constellations and Continuous Line remain explicitly excluded. Do not import repeated upstream theme/navigation redesigns, reverted font toggles or experimental defaults wholesale.

Mochy's TV phone remote/player work, per-song speed/pitch, live sleep-timer controls, local metadata editing, optional Bluetooth resume and pause-on-mute deserve focused follow-up. Verify reachability and existing equivalents first. Recognition, Spotify Canvas, queue tiers/duplicate occurrences, playlist import, public-folder exports, ordinary crossfade and headphone corrections already exist here; another implementation would add duplication. Preserve the user's preference that downloading new playlist songs is explicit rather than silently automatic.

AETHER's M4A title/artist/album/lyrics/artwork export is also largely an existing capability: `app/.../download/Mp4Tagger.kt` and offline package tests cover those atoms. Compare edge cases and artwork retries, not a second tag writer. Playlist sync/sorting and missing-artist recovery also need comparison with our current account/library paths before being labelled missing features.

### 9. Podio and lower-priority changes

AntennaPod's transcript fix splits a speaker cue at the first colon only, preserving times, URLs and later colons; a speaker-only cue is ignored. This is useful regression behavior if/when Podio exposes SRT transcripts, not proof the identical bug exists in our current RSS catalogue. Chapter images in system controls and initial subscription freshness are separate candidates. Reuse the existing podcast progress/media owner rather than routing podcasts through music normalization/history.

gPodder's updater/duration and MPRIS exit handling are architectural references. Its Python/iPod code is not a Cosmotune module. Mihon scrollbar and anypod dependency/workflow changes are maintenance ideas, not new reading/podcast content sources. Version/toolchain bumps must be assessed against our Compose/Kotlin/Gradle matrix, not copied merely because upstream advanced.

## Proposed integration order and release gate

1. **Correctness:** audio/rendition selection, provider retry/session state, artist/album identity, stale-request/queue occurrence guards.
2. **Cross-platform functionality:** parser refresh, Cast handover and macOS media controls, portable M3U/quality jobs, Watch batch downloads.
3. **Presentation/performance:** foldable/landscape layout, inline lyric options, startup/offscreen work, sync traffic and companion refinements.

For each item, pin the relevant source commit, adapt one shared behavior where possible, wire both app entry points, and add failure/race cases before a release build. Audio/USB/Bluetooth/Cast require physical or authorized live validation beyond fixtures. Keep release notes clear about branch-only changes and untested hardware. No application files, dependency pins or version tags were changed by this review.
