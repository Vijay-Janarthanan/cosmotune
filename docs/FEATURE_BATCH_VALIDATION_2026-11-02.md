# Feature batch validation — 2 November 2026

Status: published in **v1.0.14** on 2 November 2026 after local validation and the complete multi-platform release workflow. The earlier working-tree baseline was v1.0.13.

## Delivered source scope

- Shared phone/desktop navigation/settings/provider repairs, persistent artist artwork, and working Video mini-player controls after Back.
- Bounded progressive Video range reuse on restore, advancing smoothing fallback and truthful output cadence. Video Auto audio override is separate from its actual selected representation, preserving original/dub/descriptive track identity.
- Device-local headphone EQ save/apply/bypass/opt-in reconnect, measured versus advertised output/spatial facts, explicit approximate AutoEq import and vendor setup guidance. The app effect is labelled stereo widening, with system-spatial/Atmos guards.
- Broader bounded music candidates, recording/version deduplication, persisted explicit feedback/undo and editable playlist previews. Native Home supplies actual history, liked IDs and watched artists. The existing model remains 3,186,092 bytes compressed / 4,176,439 bytes unpacked.
- Actual new-look Android release pages and desktop collections use the same list controls. Filtered/sorted/selected queues retain their occurrence order; canonical complete pages retain bounded playback. Continuation errors preserve retryable tokens, duplicate playlist recordings survive paging, and cyclic tokens stop visibly.
- Shared Video Home has independent account/topic paging, explicit errors/retry, broader interests and persisted suppression/reset. Manga opens useful discovery, moves source setup under Settings and blocks normalized source identities containing `hent`. The first-source decision persists, so removing the last source survives restart.
- Phone/desktop library and verified-file synchronization, opt-in playlist download updates, phone-authoritative Watch Podio, and strict Windows/Linux desktop DoP source transport are implemented within the scopes documented separately.
- Read now includes Books and Manga on both native hosts, real EPUB/PDF engines, local imports/collections/progress/bookmarks/preferences, attributed catalogue search/trending, edition metadata and configured OPDS acquisition. Paperback runtime and commercial DRM/loan workflows remain separate. Reader validation is recorded below.

## Checks

Final results include broad suites and subsequent focused corrections, not one uninterrupted green command. The earlier failures were corrected without dropping their behavioural expectations.

| Check | Result |
| --- | --- |
| Shared JVM | 2,605 cases, zero failures/errors; 12 skips |
| Providers JVM | 155 cases, zero failures/errors; 3 skips |
| Remote JVM | 18 cases, zero failures/errors |
| Shared UI JVM | 675 cases, zero failures/errors |
| Manga JVM, including reading catalogue fixtures | 85 cases, zero failures/errors; 1 optional store-network skip |
| Video JVM | Broad 810-case run had only the removal-document inventory failure; the corrected 26-case legal/removal suite passes. Broad suite had 2 skips. |
| Real Compose interactions | Complete 100-case suite passes against final source |
| Desktop JVM | Complete 533-case suite, zero failures/errors; 20 platform/optional skips |
| Android production-debug unit tests | Complete 1,216-case suite passes; 1 skip |
| Wear OS unit tests | 12 cases, zero failures/errors |
| Desktop and Android/TV compilation | Pass; Windows native window/audio DLL rebuilt; TV production manifest processing passes |
| Tracker/whitespace | 67 implemented rows checked, zero evidence problems; whitespace check passes |

The live official MANGA Plus English probe returned **74 catalogue items**, loaded a series with **8 chapters** and **15 page links**, and decoded one **278,739-byte** image. This is one real source/series check, not a universal catalogue availability claim. Reading adapters have four local fixtures covering commercial metadata, OPDS relations, credential origins, redirects and input limits; no private user server was available for certification.

Local logs and render artifacts are under ignored `build/video-playback-review/`, particularly `combined-feature-recheck.log`, `final-platform-recheck.log`, `final-source-and-locale-gate.log`, `final-ui-android-full.log`, `native-restore-report.txt` and `native-smoothing-report.txt`. Manga discovery renders are in `build/ui-1.0.9/` and `uiTests/build/reports/manga-ui/`.

## Practical limits

- The native restore probe kept its position and upstream request count **6 → 6** with no repeated ranges. The earlier probe made 10 requests and repeated about 5 MB.
- Real 640×360 native decode/smoothing/raster readback still showed timing gaps: the later run averaged about **50 submissions/second**, with a rolling output rate of **55 FPS** and zero decoder drops. Controlled fixtures prove changing intermediates and honest gap accounting; sustained physical-display 60 FPS is not established.
- S24 Ultra, WH-1000XM5, Buds4 Pro, CS-Pro DAC and paired-watch walkthroughs remain unperformed. Vendor-controlled codecs/ANC, downstream DAC state and hardware DSD are not inferred. Direct Android USB DSD, macOS DoP and raw native DSD remain unsupported prerequisites, not capability badges.
- Local Windows checks do not substitute for physical playback. The final multi-platform CI and publication evidence is recorded below; universal book availability is not claimed.

See [audio/discovery scope](HEADPHONES_AND_DISCOVERY_PLAN_2026-11-02.md), [music behaviour](MUSIC_DISCOVERY_AND_FEEDBACK.md), [video evidence](VIDEO_PLAYBACK_REPAIRS_2026-11-02.md), [reading plan and source matrix](READING_BOOKS_PLAN_2026-11-02.md), [sync/download updates](DEVICE_SYNC_AND_PLAYLIST_UPDATES.md), [DoP/EQ scope](DSD_TRANSPORT_AND_EQ.md) and [Watch Podio](WATCH_PODIO.md).

## 1.0.14 affected regression gate

`1.0.14-release-gate.log` passed on Windows: Manga/reading 99 cases (4 skipped), real Compose UI 105 (zero skipped), desktop 533 (20 skipped), shared UI 675, Android phone 1,216 (1 skipped), Watch 12, plus TV Kotlin compilation. All had zero failures/errors. The colour PDF test executes native raster conversion and checks red/blue pixels. The separate native WebKit probe also passed, rendered a generated illustrated EPUB, executed text search and saved `build/reports/reading-native/epub-colour-webkit.png`.

Four actual Compose screenshots were reviewed: Books library and discovery at narrow phone and wide desktop sizes. The library, saved-result availability and large-text interactions pass. A follow-up adds the shared Read-header/Manga-reader regression and official Gutenberg OPDS detail resolution; their results are recorded below.

The first live probe found and corrected an invalid Open Library trending query (HTTP422). The corrected provider-documented range query independently returned HTTP200 with 1,753 actual results. Gutendex then timed out; the source was replaced with Gutenberg's documented official OPDS catalogue and per-book acquisition feeds. No dead third-party metadata host is silently retried as a successful source. The final live acquisition result is recorded separately below.

### Final reader results

The live catalogue/acquisition/open probe passed in **12.105 seconds** using the actual repository implementation: Harry Potter metadata (no fabricated download), Open Library trending, official Gutenberg search, selected-book OPDS formats, illustrated Peter Rabbit EPUB download and validated offline document resources. This public-domain test uses a temporary directory; no book contents are bundled. The native WebKit pixel/search probe passed separately. These results do not certify a private library server or physical Android WebView.

The final Manga/Read wrapper regression passes: entering actual Manga pages hides the Read header/tabs, and Back restores the chapter list/header. Books/reader UI is visually reviewed at phone and desktop widths. The migration-source fixture now fixes its creation/progress times rather than relying on millisecond serialization timing; production stores already encode defaults explicitly.

`books-release-final.log` passed after the final format labels and stable migration fixture: full Manga/reading and real UI suites, Android phone Kotlin compilation and desktop Kotlin compilation. The browser companion typecheck and tests also pass (`1.0.14-web-typecheck.log`, `1.0.14-web-tests.log`). No production defaults were weakened for testing.

## Release packaging repair

The first tagged 1.0.14 workflow stopped at browser packaging because the helper package still declared 1.0.13. No assets were published. The repair aligns root/native/helper defaults, including the separate desktop fallback, and runs `tools/verify-release-version.py` before platform jobs. Its positive and negative mismatched-version checks pass. Actual browser production build and package verification pass locally. At the user's explicit request, the failed 1.0.14 tag is updated to the repaired commit and redeployed under the same version; no 1.0.15 release is created.

The next CI attempt exposed stale desktop/provider runtime literals and the check-workflow helper pin; the preflight now covers those too, matching `ReleaseVersionPinsTest`. The Video wiring contract now expects the real `ReadingApp` host while preserving its audio-unload assertions. Tor 15.0.25 has moved from the active distribution directory to the official package archive; all four archived platform URLs return HTTP200 and their official checksum-manifest SHA-256 entries match the existing pins. No dependency version or checksum was loosened. A macOS Intel runner separately failed DNS resolution of Google's dependency hosts before configuration.

The exact complete native release gate passed after all packaging/version/wiring repairs: {'tests': 5001, 'failures': 0, 'errors': 0, 'skipped': 41} across eight suites, in 6m39s (`1.0.14-complete-release-gate.log`). Both `ReleaseVersionPinsTest` and the real audio-unload/wiring suite pass. macOS matrix variants no longer cancel one another on a transient runner failure. The temporary local Gradle daemon was stopped after validation.

## Published 1.0.14

The final release workflow completed successfully on 2 November 2026. All ten jobs passed, including Linux, Windows, both macOS architectures, Android phone/watch/TV, browser packaging and publication. The release is published under the user-requested **v1.0.14** tag; no 1.0.15 release was created. Physical reader/headphone/DAC checks remain distinct from those automated results.

All **49/49** public release download links were HEAD-checked successfully with positive content lengths; `/releases/latest` resolves to `v1.0.14`. The per-file evidence is retained locally in `build/video-playback-review/1.0.14-download-verification.json`.

The Cosmotune product and Shutter Island portfolio targets were deployed after release verification. All six checked custom-domain/alias home/case URLs returned HTTP200 with 1.0.14 and Read content. See [live deployment inventory](LIVE_WEBSITES.md).
