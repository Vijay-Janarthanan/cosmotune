# AETHER and Euthopiar review

Reviewed on 11 November 2026 against Cosmotune 1.0.21. This is a review of source, release notes and the existing Cosmotune implementation. The upstream APKs were not installed or exercised on hardware. Availability, rendering quality and provider compatibility still need execution tests before adoption.

## Revisions and scope

| Project | Reviewed source | Latest published release |
| --- | --- | --- |
| AETHER | [fd019594](https://github.com/GHOSTxASR/AETHER/tree/fd019594548e52be06f6339bb758ff5661f15f3d) | [2.1.0](https://github.com/GHOSTxASR/AETHER/releases/tag/v2.1.0) |
| Euthopiar | [b7172cbc](https://github.com/TheARCreactorGUY/Euthopiar-/tree/b7172cbcb972cacdcae7fb35f87ac8cffa40d850) | [1.1.0-Stable](https://github.com/TheARCreactorGUY/Euthopiar-/releases/tag/v1.1.0-Stable) |

AETHER focuses on Android music playback and a monochrome visual identity. Euthopiar focuses on Android films, series and anime. Cosmotune spans music, video, Watch, Podio, Manga and Read, with phone and desktop implementations and separate TV/watch/browser surfaces. Their cohesive interfaces are references for individual Cosmotune modes.

## AETHER findings

### Lyrics

| Capability | Upstream implementation | Cosmotune comparison |
| --- | --- | --- |
| Verse | Word and syllable sweep, held-note letter growth/glow, backing vocals, duet alignment, instrumental gaps, tap to seek and spring scrolling. Its source credits a BitChord port. | Cosmotune already has the corresponding model and effects, including the active Orbit renderer. This is presentation and consistency work rather than a missing lyric engine. |
| Constellations | A Canvas view arranging words as stars. | A distinct optional visualization to add. |
| Continuous line | A prepared drawing writes phrases as one connected line, with instrumental drawings between phrases. Preparation runs off the main thread and playback position controls its progress. | A distinct optional visualization. Font shaping, Tamil/Telugu, right-to-left text, seeking, reduced motion and desktop rendering require validation. |
| Local romanisation | Android ICU transliteration, Korean pronunciation rules, Chinese Pinyin and Japanese kana. Japanese kanji is deliberately left alone without a dictionary. | Cosmotune currently requests romanisation through its network translation path. An offline path is a useful addition. |
| Provider aggregation | Seven libraries, parallel requests, word timing preferred over line timing, bounded waiting, attribution and cache. | Cosmotune already supports the seven named libraries and additional providers, recording matching and provider selection. Adding another copy of the aggregator would duplicate existing work. |
| Timing fallback | Untimed/line-timed words can be distributed for animated presentation and identified as estimated. | Preserve the difference between supplied timing and estimated animation. A new appearance does not establish lyric timing accuracy. |
| Local lyrics | Sidecar LRC and embedded lyrics take precedence for local files. | Existing Cosmotune sidecar/embedded support overlaps. |

Sources include [VerseModel](https://github.com/GHOSTxASR/AETHER/blob/fd019594548e52be06f6339bb758ff5661f15f3d/app/src/main/kotlin/app/aether/ui/lyrics/VerseModel.kt), [ContinuousLine](https://github.com/GHOSTxASR/AETHER/blob/fd019594548e52be06f6339bb758ff5661f15f3d/app/src/main/kotlin/app/aether/ui/lyrics/line/ContinuousLine.kt), [Romaniser](https://github.com/GHOSTxASR/AETHER/blob/fd019594548e52be06f6339bb758ff5661f15f3d/app/src/main/kotlin/app/aether/ui/lyrics/Romaniser.kt) and [LyricsChain](https://github.com/GHOSTxASR/AETHER/blob/fd019594548e52be06f6339bb758ff5661f15f3d/core/lyrics/src/main/kotlin/app/aether/core/lyrics/LyricsChain.kt).

Cosmotune evidence includes `sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/nowplaying/CosmoLyricsView.kt`, `shared/src/jvmSharedMain/kotlin/com/cosmotune/music/data/lyrics/LyricLine.kt`, `LyricsRepository.kt`, `LyricsSource.kt` and `LyricsTranslation.kt`.

### Other useful additions and overlaps

- Broader headphone correction catalogue and import formats. AETHER advertises measured corrections for 39 models plus AutoEq, squig.link, Equalizer APO and Wavelet imports. Cosmotune has EQ and model-specific AutoEq GraphicEQ support, with a narrower explicitly validated device list.
- Sound-based alignment when moving to a better stream. Its code compares two seconds of decoded loudness onsets and searches offsets within 500 milliseconds. Cosmotune already probes upgraded streams silently before replacement. The acoustic alignment and dual-player handover are the interesting incremental work. This method does not cover long intros or different song edits.
- An accessible last-crash note that stays local until the listener shares it. Cosmotune has logging, but a consistent in-app diagnostic export remains worth reviewing.
- Explicit offline states, kept-song indicators and a compact active-download count. The strongest lesson in 2.1 is preserving names and artwork across queues, notifications and offline reopening.
- Optional listening/share-card treatments and animated artwork transitions. Cosmotune already has listening statistics, sharing, animated artwork and OpenCanvas, so these are style choices.
- Automatic gapless-album protection around crossfades is worth checking against Cosmotune’s existing crossfade/Automix behavior. Crossfade itself is already implemented.

AETHER’s USB bit-perfect implementation has narrower behavior than its broad marketing wording suggests. The reviewed code requests an Android 14 bit-perfect mixer for known lossy decoded PCM16 formats and explicitly excludes local lossless formats from that request. It should not replace Cosmotune’s audio path on the assumption that it automatically improves high-resolution DAC playback. See [BitPerfect.kt](https://github.com/GHOSTxASR/AETHER/blob/fd019594548e52be06f6339bb758ff5661f15f3d/player/src/main/kotlin/app/aether/player/BitPerfect.kt).

## Euthopiar findings

| Feature or repair | Current Cosmotune position | Proposed addition |
| --- | --- | --- |
| Trakt and Simkl | No corresponding Watch integration found. | Sync watched state, watchlists and progress, with explicit account controls. |
| Cloud Watch progress | Cosmotune cloud sync covers music playlists/likes/artists and Watch add-on configuration. | Extend it to Watch progress, watchlists and appropriate settings. Keep existing Firebase infrastructure. |
| JavaScript Nuvio plugins | Cosmotune accepts HTTP manifest-based Stremio/Nuvio-compatible add-ons. Its separate music provider runtime does not establish compatibility with Watch scraper scripts. | A dedicated JS Watch adapter, with tested host APIs, timeouts, cancellation and isolation. |
| Native trailers | Cosmotune’s title-page Trailer action opens a YouTube URL externally. | Reuse Video’s resolver/player for an in-app trailer preview. |
| Card density | No matching Watch-specific Compact/Balanced/Flagship preference found. | A shared phone/desktop setting that scales poster dimensions, labels, gaps and focus targets together. |
| Ratings | Cosmotune’s title summary currently has a single rating and a source. | Add separate, attributed IMDb/Rotten Tomatoes fields where a configured source supplies them. Never relabel TMDB as IMDb. |
| Search and regional discovery | Cosmotune has Watch discovery, text search grouped into films/series/channels, and live-region facilities. The reviewed Watch search UI has no genre/year selector. | Type/genre/year filters, dedicated regional Top 10 rows, clearer anime grouping and separation of daily soaps from web series. |
| Profile separation | Multiple authentication accounts do not provide Euthopiar’s household Watch profiles. | Optional profile-specific watchlists and Continue Watching, with a clear account/profile distinction. |
| Anime skips and subtitles | Watch subtitle plugins exist. AniSkip/SubDL integration was not found. | Optional AniSkip opening/ending timestamps and an additional subtitle provider, with required credentials. |
| Casting and debrid | Music casting and built-in Watch torrent playback exist. Equivalent Watch casting/debrid integrations were not found. | Assess Watch casting separately. Debrid integrations require their own accounts and are optional provider work. |
| Download storage preflight | No free-space preflight was found in `WatchDownloads.start`. | Check space and selected destination before starting, then report recoverable insufficient-space failures. |
| DASH/CDN/Matroska handling | Existing players support HLS/DASH and torrents, but that does not prove the same edge cases are handled. | Review relative manifest base URLs, retained headers after close, MIME detection, and multi-audio sequential MKV behavior with regression samples. |

Euthopiar has actual implementations for [JS plugin execution](https://github.com/TheARCreactorGUY/Euthopiar-/blob/b7172cbcb972cacdcae7fb35f87ac8cffa40d850/core/src/main/java/com/euthopiar/core/js/PluginRuntime.kt), [Trakt](https://github.com/TheARCreactorGUY/Euthopiar-/blob/b7172cbcb972cacdcae7fb35f87ac8cffa40d850/core/src/main/java/com/euthopiar/core/sync/trakt/TraktClient.kt), [Simkl](https://github.com/TheARCreactorGUY/Euthopiar-/blob/b7172cbcb972cacdcae7fb35f87ac8cffa40d850/core/src/main/java/com/euthopiar/core/sync/simkl/SimklClient.kt) and [playback sync scheduling](https://github.com/TheARCreactorGUY/Euthopiar-/blob/b7172cbcb972cacdcae7fb35f87ac8cffa40d850/app/src/main/java/com/euthopiar/data/sync/supabase/PlaybackSyncCoordinator.kt).

Its playback coordinator flushes on pause/seek after three seconds and on exit/completion immediately, with a 15-minute fallback heartbeat. Its sync manager restores before pushing and includes a 45-second deletion cooldown. These are useful behaviors to assess against Cosmotune’s existing serialized cloud sync and journals.

The source’s free-tier limits are concrete tradeoffs. It selects the latest 50 history items, 100 watchlist items and 10 playlists for pushes. The release’s 5,000-user scalability statement was not independently load-tested in this review. Those caps should not be adopted silently.

## Release history

Dates below use Asia/Calcutta time. Summaries are paraphrases of the published release bodies. All three AETHER releases and all ten Euthopiar releases returned by the GitHub release API were reviewed.

### AETHER

| Release | Date | Changes |
| --- | --- | --- |
| [1.0.0](https://github.com/GHOSTxASR/AETHER/releases/tag/v1.0.0) | 9 Nov | Initial Android music player, local files, offline playlists, source server, audio-path reporting, headphone EQ, animated halftone art, dial player, share cards and two lyric visualization modes. |
| [2.0.0](https://github.com/GHOSTxASR/AETHER/releases/tag/v2.0.0) | 10 Nov | Seven lyric libraries, Verse, local romanisation, crossfade, better-source handover, video playback, local/offline album shelves, public music-folder exports with artwork/lyrics, immersive screen and updater. Mid-song recovery improved. |
| [2.1.0](https://github.com/GHOSTxASR/AETHER/releases/tag/v2.1.0) | 11 Nov | Repairs kept-track metadata/art, queue restore, empty See all pages, duplicate-card crashes and portrait/high-quality films. Consolidates the video toggle and playlist menus, adds playlist search/offline cues and crash-note export, adjusts download placement, and removes automatic download of new playlist songs. |

### Euthopiar

| Release | Date | Changes |
| --- | --- | --- |
| [1.0.1](https://github.com/TheARCreactorGUY/Euthopiar-/releases/tag/v1.0.1-stable) | 25 Oct | Streaming stability, multi-audio selection, playback buffers, passthrough/skip/storage/Trakt settings and in-app updater. |
| [1.0.2](https://github.com/TheARCreactorGUY/Euthopiar-/releases/tag/v1.0.2-Stable) | 26 Oct | Failover, add-on and details-page repairs, with glass styling consistency. |
| [1.0.3](https://github.com/TheARCreactorGUY/Euthopiar-/releases/tag/v1.0.3-Stable) | 27 Oct | Experimental live TV/add-on home catalogues, source/quality selection repairs, cloud stream support and donation UI. |
| [1.0.4](https://github.com/TheARCreactorGUY/Euthopiar-/releases/tag/v1.0.4-Stable) | 28 Oct | Automatic source failover disabled by default, icon alignment and resume-overlay blur fixes. |
| [1.0.5](https://github.com/TheARCreactorGUY/Euthopiar-/releases/tag/v1.0.5-Stable) | 28 Oct | Regional catalogues, daily-soap/web-series separation and broader backup/restore. |
| [1.0.6](https://github.com/TheARCreactorGUY/Euthopiar-/releases/tag/v1.0.6-Stable) | 30 Oct | Home deduplication, safer plugin-to-title matching, plugin updates and Simkl login repair. |
| [1.0.7](https://github.com/TheARCreactorGUY/Euthopiar-/releases/tag/v1.0.7-Stable) | 3 Nov | Guided setup, missing-engine guidance, onboarding/back fixes, safer insets, motion and touch-target fixes. |
| [1.0.8](https://github.com/TheARCreactorGUY/Euthopiar-/releases/tag/v1.0.8-Stable) | 6 Nov | Trakt/Simkl synchronization, native trailers, icon overhaul, DASH/relative URL/header fixes, source UA fallback and poster restoration. |
| [1.0.9](https://github.com/TheARCreactorGUY/Euthopiar-/releases/tag/v1.0.9-Stable) | 8 Nov | Google sign-in with Supabase sync, restore during onboarding, sync categories, profile isolation, card-density options, rating badges and serialized token refresh. |
| [1.1.0](https://github.com/TheARCreactorGUY/Euthopiar-/releases/tag/v1.1.0-Stable) | 9 Nov | Cloud add-on/JS plugin restore, deletion cooldown, capped/coalesced sync, startup jitter, storage preflight/destination choices, sequential multi-audio MKV repair and updater repository fallback. |

## Proposed order

1. Add offline romanisation and optional Constellations/Line views behind the existing shared lyric model. First validate Tamil, Telugu and duet/word-timed samples and preserve the current readable view.
2. Add native Watch trailers, shared density controls, properly attributed additional ratings and clearer regional/anime discovery.
3. Extend Cosmotune account sync to Watch progress/watchlists and test restore/delete reconciliation across phone and desktop.
4. Add Trakt/Simkl and JS Nuvio compatibility as distinct integrations with their own account, source and failure tests.
5. Add Watch download-space preflight and validate source handover, DASH and MKV edge cases against actual samples.

## Reuse and validation

AETHER includes a GPL-3.0 licence and identifies several ports from BitChord/PODIUM. Preserve source attribution for reused code. No licence file was found in the reviewed Euthopiar repository tree, so copying its implementation needs permission clarification. Independently implementing the behaviors remains an option.

Neither project’s Android-specific rendering, ICU imports, QuickJS binaries or Media3 extensions can be assumed to work on Cosmotune desktop, TV or Wear. Share data models, settings and policy where appropriate, and test each supported platform through its own entry points.

Only this review document was added to tracked Cosmotune files. No application feature, build configuration, tag or release was changed for this review.
