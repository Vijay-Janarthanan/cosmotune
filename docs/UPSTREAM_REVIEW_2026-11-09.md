# Upstream review · 9 November 2026

Compared the 20 tracked branches with the [2 November snapshot](UPSTREAM_SNAPSHOT_2026-11-02.json). Twelve advanced. The table records branch heads and selected changes for comparison against Cosmotune, including work that may already have an equivalent here. Integration history is recorded separately in [UPSTREAM.md](../UPSTREAM.md).

| Repository | Reviewed head | Changes to compare against Cosmotune |
| --- | --- | --- |
| [BitChord](https://github.com/kushagrasinghx/BitChord/compare/b690dc1460...acf219a7e4) | `acf219a7e4` | v1.9.1 add-on matching and security, bit-perfect playback and the TV build. |
| [LastWave Native](https://github.com/Clash-Projects/LastWave-Native/compare/4c11bc2b34...276d94579b) | `276d94579b` | Synced music-video matching, exact seeks, singer-specific reprise matching, independent add-ons and lower-memory recovery. |
| [LastWave Desktop](https://github.com/Clash-Projects/LastWave-Desktop/compare/5c58c9d7c0...d997c06b33) | `d997c06b33` | AutoMix stall and silence recovery, batched lyric lookups, and same-title artwork matching. |
| [Lokal Desktop](https://github.com/lokal-music/lokal/compare/0cfaab0662...72630a26e4) | `72630a26e4` | Native synced FLAC lyrics, timed karaoke and voice-marker handling. |
| [Lokal Mobile](https://github.com/lokal-music/lokal-mobile/compare/e615b11581...616877c428) | `616877c428` | Canvas compositing, persisted library identity, player lifecycle, and a Cast sender prototype for iOS. |
| [Mornye](https://github.com/mornyeapp/Mornye/compare/7db0fc76f6...45df986066) | `45df986066` | Custom provider endpoint grants and registry runtime filtering. |
| [Mornye Extension](https://github.com/mornyeapp/Mornye-Extension/compare/a1c99f8b33...12530ceda4) | `12530ceda4` | Amazon search session renewal using fresh cookies, title-and-artist search, and custom Tidal/Qobuz endpoints and stream fallback. |
| [AntennaPod](https://github.com/AntennaPod/AntennaPod/compare/e10f5f6f34...08107984b8) | `08107984b8` | Apply skip-silence settings across selected subscriptions and keep sort direction visible with long labels. |
| [gPodder](https://github.com/gpodder/gpodder/compare/825593f7ca...629c652bc4) | `629c652bc4` | Maximum-episode handling and isolation of per-download options. |
| [Mihon](https://github.com/mihonapp/mihon/compare/e588e04359...7eec566d9c) | `7eec566d9c` | Toolchain, dependency and testing updates. |
| [Tsuki](https://github.com/TsukiAndroid/Tsuki/compare/549ef6e13d...264d7d6eae) | `264d7d6eae` | Alpha build history, download archive and documentation changes. |
| [anypod](https://github.com/thurstonsand/anypod/compare/370a0de066...baf34d7710) | `baf34d7710` | Dependency updates. |

Same heads as 2 November: Podgrab, Dotify, audimo, p-fm-bot, votify, Kotatsu Parsers Redo, spotifeed and podpull.

Also checked [OpenCanvas](https://github.com/Vijay-Janarthanan/OpenCanvas/commits/main/) separately. Its reviewed head `1d5c7b0cb6` recovers publisher videos using subtitles and alternate encodings. The preceding `717362dff8` refreshes expired video stream URLs while retaining measured sync maps.

First port candidates to verify against the current Cosmotune tree are Mornye’s Amazon session renewal, LastWave’s synced-video and reprise matching, and BitChord’s add-on security changes. The wider desktop audio and mobile lifecycle changes warrant targeted regression checks against Cosmotune’s own implementations.

## Cosmotune integration

| Change | Status in Cosmotune |
| --- | --- |
| Mornye provider registries and custom download endpoints | Mixed registries filter out unsupported runtimes before validation. A user-entered URL in an explicit network-grant setting authorizes only its scheme, host and port. Existing restrictions on private addresses, redirects and credentials still apply. |
| Amazon Music 2.3.19 | Available through the existing hash-verified provider update flow. The upstream archive hash and its 25 offline session and resolution cases were verified. Installed copies require the user's existing package review flow to update. |
| OpenCanvas and LastWave video matching | The OpenCanvas publisher proof, alternate encodings and signed-URL refresh were already present. Added a recording-edition gate for reprises and singer-specific versions, plus rejection of video game edits. Cosmotune already uses Media3's [exact seek default](https://github.com/androidx/media/blob/release/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/SeekParameters.java). |
| Desktop AutoMix | A stalled incoming deck leaves the outgoing track audible before handoff. After handoff, the engine retries the announced track at the heard position. Repeated faults switch the session to plain fades and then to gapless playback. The watchdog monitors decoder and mixer progress. |
| LastWave artwork and memory | Desktop cover requests and system media artwork share a bounded decoded cache. Decorative desktop artwork loops use a smaller decode size and frame budget, while synchronized music videos keep their normal resolution and timing. Android and desktop recheck cached animated covers when the album credit arrives and skip wrong-release Apple results. |
| BitChord Android bit-perfect playback | The playback decks hold a local wake lock while playing to support direct audio with the screen off. Device-specific driver behavior still needs USB hardware validation. Cosmotune uses its own scoped provider credentials and has no BitChord attestation service. |
| Lokal FLAC lyrics | Android and desktop share a bounded FLAC reader and prefer timestamped content. Leading and split `vN` labels map to vocal lanes while preserving word and syllable timing. Independently timed backing lines attach by onset. [Lokal karaoke parsing](https://github.com/lokal-music/lokal/pull/603). |
| AntennaPod and gPodder | Podio can apply skip-silence settings across selected follows on mobile and desktop, and one show at a time on TV. The Following inbox has a per-show episode cap independent of archive browsing. |
| Live TV | Switching channels inside Watch replaces the current player page, so Back returns to the guide. |

The Lokal iOS Cast prototype requires an iOS host. Cosmotune currently builds Android, Android TV, Wear OS and desktop hosts. Mihon and anypod's toolchain changes and Tsuki's release documentation apply to those upstream projects.
