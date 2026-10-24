# Cosmotune feature tracker

Updated 2026-10-24. This is the central local tracker for features from other apps and original Cosmotune work. A reviewed feature is not automatically implemented; compiled code is not automatically device-tested or released.

**Release scope:** the user has now authorized implementation of every row in one coordinated 1.0.4 update. Hold the release until that scope is implemented and checked; see [implementation order and evidence](docs/IMPLEMENTATION_1.0.4.md). Earlier proposed “after the current release” sequencing is superseded by this instruction.

## Status and evidence rules

* **Released**: present in a published Cosmotune release, with a release link.
* **Existing**: present before the current integration; its exact coverage still needs the relevant tests.
* **Implemented**: code and automated checks are complete for every platform the row names and are recorded in the row's evidence; it is not released yet, and any physical-device or account check that remains is listed in the row. Use this only when the evidence supports it.
* **In progress**: implementation exists in the working tree and still has outstanding checks or integration.
* **Planned**: requested/selected, but no complete implementation is claimed.
* **Reviewing**: source/behaviour/dependencies are still being audited.
* **Decision recorded**: an explicit platform or product constraint is documented; this must not be silently treated as completion.

For each named app, inspect source, configuration/settings, tests, packaging and platform-specific modules. Compare the complete feature inventory against Cosmotune, including companion apps. Keep build tests, real-device tests and publication distinct. Record omissions rather than dropping them from the table.

## Sources and review depth

| Project | Revision examined | Review record | Coverage |
| --- | --- | --- | --- |
| [BitChord](https://github.com/kushagrasinghx/BitChord) | `edc1e6f3`, baseline `fe95bb60aa9cd205cf4ab8bd25a5bec71a595b4f` | `UPSTREAM.md`, rows BC below | Native source delta, settings, tests and workflows under review. Wear OS was initially omitted and is now explicitly in scope. Web and TV require module-level review too. |
| [Mornye](https://github.com/mornyeapp/Mornye) | `fb4945be910b0a7a8cce25eab3d1b3d80b3541e4` | [Detailed review](docs/MORNYE_REVIEW.md) | Flutter home/autoplay, Rust runtime, manifests and inventory inspected. The pinned public JioSaavn extension passed shared-runtime metadata/AAC decoding checks; no full upstream app or physical-device execution. |
| [Mornye extensions](https://github.com/mornyeapp/Mornye-Extension) | `6b544663fb5b7a7dfbe1959f3c0edab017378f53` | [Provider findings](docs/MORNYE_REVIEW.md) | Source-backed providers and registry inspected; package-only providers are not assumed verified. |
| [Lokal](https://github.com/sipbuu/lokal) | `3199796ef90743a5abbac6ffc96495beec855a4e` | [Detailed review](docs/LOKAL_REVIEW.md) | Provider, runtime, download, quality and discovery source inspected. Full feature audit ongoing. |
| [Tsuki](https://github.com/TsukiAndroid/Tsuki) | `89b8d9cf6bb53303f340505f46c2ad2c1c8ac5b7` | [Manga plan](docs/MANGA_PLAN.md) | Android source loader, built-in/custom parsers, build and licence inspected. User confirmed this is the intended project. |
| [Kotatsu parser fork](https://github.com/Kotatsu-Redo/kotatsu-parsers-redo) | `dc223c3d8098415b29cf1acfedc401a88d01c9fe` | [Manga platform constraints](docs/MANGA_PLAN.md) | JVM context/API and build inspected; Manga Plus English catalogue/chapter/page/image check passed. |
| OpenCanvas | `b2255f4b6da996a3cf6649ec99e36e65c4d44273` | [Import manifest](docs/OPENCANVAS_IMPORT.json) | Separate published engine; preserve its import boundary. |

## BitChord integration

Android means the phone/tablet app. Desktop means Windows, Linux and both macOS architectures unless stated otherwise. Physical-device checks are still required where hardware/services are involved.

| ID | Feature | Platform | Status | Remaining work / evidence |
| --- | --- | --- | --- | --- |
| BC-01 | Artist photo and muted motion header | Android + desktop | In progress | Shared Apple artwork lookup and existing canvas decoder; current and legacy artist pages being wired. No motion availability guarantee for every artist. |
| BC-02 | Artist signatures and configurable API | Android + desktop | In progress | SVG decoding, optional provider settings, parsing/ranking and footer; no default hosted provider. Complete settings and rendering tests. |
| BC-03 | Audio format and encoded bitrate in player | Android + desktop | In progress | Compact decoder format line; unknown bitrate omitted, PCM throughput separate. Unit tests added. |
| BC-04 | Queue history above Now playing | Android + desktop | In progress | Shared queue logic and persistent settings toggle. Verify all layouts and queue edits. |
| BC-05 | Bounded album/playlist contexts | Android + shared | In progress | Registry, windows, persistence, repeat-all and resurrection fixes imported; test context transitions and restored queues. |
| BC-06 | Continuation-token parsing | Android + desktop | In progress | First-renderer/command-executor handling imported with shared Innertube changes. |
| BC-07 | Equalizer/limiter and DSP fixes | Android + desktop | In progress | Imported relevant DSP changes; regression suite required. No perceptual audio-quality conclusion from compilation. |
| BC-08 | Automix phrase lengths and transition effects | Desktop + shared planner | In progress | Beat repeat/flanger/reverb/textures and planner changes; validate bypass and audio continuity. |
| BC-09 | Idle Listen Together health/backoff | Android + desktop | In progress | Imported polling fixes; party connection and idle behaviour need verification. |
| BC-10 | Add-on quality/stereo negotiation | Android + desktop | In progress | Defaults and declared tier mapping retained; source tests required. |
| BC-11 | Queue/Cast remote-speaker seam | Android | In progress | Cast included; Wear OS receiver must be integrated as another speaker. |
| BC-12 | Wear OS companion app | Wear OS + Android | In progress | Initially omitted incorrectly. Audit/build/import companion, shared protocol, matching app IDs and signing keys. |
| BC-13 | Watch transport/seek/like/shuffle/repeat/queue controls | Wear OS + Android | In progress | Phone command service and watch controls integrated; production compilation/native regression passed. Physical round trips remain unverified. |
| BC-14 | Crown/bezel volume, progress ring and ambient player | Wear OS | In progress | Watch UI/lifecycle code integrated and companion builds; physical crown/bezel and ambient checks remain. |
| BC-15 | Watch as audio output/phone-backed streaming | Wear OS + Android | In progress | Phone-backed audio receiver/output picker integrated; documented DASH limitation retained. Real watch audio remains unverified. |
| BC-16 | Install/update watch app from phone | Wear OS + Android | In progress | Matching companion APK and SHA256 bundled; all four phone APKs passed package/version/certificate/embedded-byte checks. Physical installation remains unverified. |
| BC-17 | Hide player status bar with stable insets | Android | Implemented | Shared hook hides only the status bar (swipe shows it briefly; before Android 15 the window moves into the cutout with short-edges mode and is restored on leaving). Every player layout now reads the stable status-bar insets for its top edge: the classic player's drag strip and the new wide layout still read the visibility-dependent insets and would have jumped, both fixed. Android setting persisted, searchable and translated; desktop is a no-op by design. `StatusBarInsetsContractTest` (4) and `NowPlayingContractTest` pass; Android app and desktop compile. Still to check on hardware: a phone with a camera cutout on Android 9-14 and on 15+, portrait and landscape. |
| BC-18 | Weekly Replay and widget account gating | Android | Implemented | Android Replay "This week" (Monday to today) reads each entry's per-day data across a month boundary. Fixed on top of the import: the busiest day never parsed (always empty), entries filed before daily data were counted whole in the week (now left out; month/year/all-time unchanged), the earliest month was not set, and the week label was hard-coded English; zh-HK/zh-Hant week strings added. Like on the 4x1 widget, notification and watch follows one rule (`LikeOffer`: signed in and not a local file/download, or a source-owned track) and is republished on sign-in/out. `ReplayWeekTest` (5, fixed clock and temporary folder), `WidgetAccountGatingTest` (4), `LocaleStringsTest` (16) and `LocalizationTest` (2) pass. The week view omits the peak listening hour (hours are stored per month only). Still to check on a device: the widget on a launcher before/after sign-out and a Replay walkthrough. |
| BC-19 | Linux MPRIS fixes | Linux | In progress | Source and tests imported; native session interaction needs Linux verification. |
| BC-20 | Local folder/grouping improvements | Android | Reviewing | Data changes imported; audit all associated UI and file operations rather than declaring complete. |
| BC-21 | Account/Spotify sign-in and settings updates | Android + desktop | Reviewing | Compare all auth and account source deltas; preserve Cosmotune credential policy. |
| BC-22 | App icon alternatives and onboarding changes | Android | Reviewing | Manifest aliases/resources and setup must be considered together; not imported yet. |
| BC-23 | Desktop player redesign / desktopUi split | Desktop | Decision recorded | Cosmotune uses its own shared UI. Compare functional improvements individually; module move is not itself a user feature. |
| BC-24 | TV app, profiles/pins and PIN hardening | Android TV | In progress | 43 source files ported from the 55-file inventory; separate TV identity/update family, profile-specific PIN proof and paired remote. Twenty-three PIN/throttle/profile/QR/effect tests pass; signed CPU packages pass version/key/alias/model/ABI checks. Real emulator D-pad onboarding, public Home/settings, rendered QR decoding and paired-browser real search/play/seek/volume/pause pass. Account controls revoke the session; [evidence](docs/TV_REVIEW.md). Physical TV hardware remains unverified. |
| BC-25 | Browser player and helper extension | Web | In progress | 235 files inventoried; 193 source/fixture files ported with independent assets/key, disabled Discord and explicit origins/party server. Player, bridge and helper tests plus Chromium/Firefox/site production builds pass. Package notices and archive checks pass; a real browser shows navigation/settings/helper status. Installed-helper/account/live playback checks remain; [companion details](web/README.md). No public site is deployed. |
| BC-26 | Discord reconnect/activity changes | Native + web | Decision recorded | Current Cosmotune builds keep Discord disabled. Record separately; do not silently re-enable while importing unrelated changes. |
| BC-27 | Remaining native UI/behaviour deltas | Android + desktop | Reviewing | Compare changed files and commit inventory to this table before calling the upstream audit complete. |

## Mornye opportunities

| ID | Feature | Platform fit | Status | Integration requirement |
| --- | --- | --- | --- | --- |
| MN-01 | Provider-fed, cached regional home sections | Android + desktop | In progress | Regional provider browser, settings/region/language cache keys, stale effect cancellation and failed-refresh retention implemented. Native Android/desktop Home shelf integration and source-card metadata are now implemented and fixture-tested; broader account discovery remains; [provider evidence](docs/PROVIDER_PACKAGES.md). |
| MN-02 | Tiny online preference learner | Android + desktop | In progress | Local logistic/AdaGrad layer, sampled playback/explicit skips, opt-out/reset and atomic 65,568-byte state implemented. Base + personal files total 4,242,007 bytes; tests and emulator timing in [evidence](docs/LOCAL_PREFERENCE_LEARNING.md). Physical-phone/relevance evaluation remains. |
| MN-03 | Lossless provider expansion | Android + desktop | Reviewing | Actual audio sources/auth/availability must be validated; provider claims do not prove FLAC bytes or output fidelity. |
| MN-04 | `.sflx` extension runtime | Android + desktop | In progress | Shared reviewed package lifecycle, scoped JS/network/storage/files, catalogue and direct-stream transport implemented. Live JioSaavn AAC decoding passed. Provider PKCE/loopback/app-owned Android callbacks, operator-signed sessions/grants, platform ciphers and isolated cookies now have controlled tests, including Android encrypted-state/signing execution. Bounded patterned transforms, ordered segmented files, VM-thread progress/status and immediate disable/relay cleanup now have core/Compose/Android fixture coverage. File-only/FFmpeg/manifest-device integration and final size/provider walkthroughs remain; [details](docs/PROVIDER_PACKAGES.md). |
| MN-05 | USB bit-perfect/exclusive/DSD output | Device dependent | Reviewing | Audit current audio path versus upstream native drivers; separate source quality from DAC output; physical hardware tests required. |
| MN-06 | ReplayGain/EBU R128 library scanning | Android + desktop | Planned | Existing normalization is not proof of full library loudness scanning; test gain/bypass/clipping. |
| MN-07 | SMB/WebDAV/Subsonic/OpenSubsonic music | Android + desktop | Reviewing | Inventory current local/network capabilities first; user-owned server credentials and reachability. |
| MN-08 | Duplicate review/tag editing/M3U8 export | Android + desktop | In progress | Native local M3U8 export passes core/Compose/native checks. Shared duplicate review now distinguishes shared paths, identical bytes and metadata candidates, with cancellation/budgets and previewed session-location repairs; 29 focused checks pass. Tag editing and merge/removal remain outstanding; [coverage](docs/LOCAL_LIBRARY_TOOLS.md). |
| MN-09 | Lyrics providers, word timing, translation/romanization/share | Android + desktop | Existing | Several equivalents already present; audit remaining provider, duet/background vocal and file import/export gaps. |
| MN-10 | Rewind/backups/concerts/haptics/widgets | Varies | Reviewing | Separate existing Cosmotune equivalents from gaps; no blanket completion claim. |
| MN-11 | Full/Lite builds | Android + desktop | Reviewing | Measure optional component costs and release/support overhead before selecting a new flavour. |

## Lokal opportunities and existing equivalents

| ID | Feature | Platform fit | Status | Integration requirement / evidence |
| --- | --- | --- | --- | --- |
| LK-01 | Bounded HTTP add-on metadata | Android + desktop | In progress | Implementing 2 MiB byte limit including chunked bodies. Real HTTP and shared tests added. Audio transfers remain separate. |
| LK-02 | HTTP add-on search/stream, settings and source priority | Android + desktop | Existing | Editable manifest settings now persist on both platforms and reach declared query parameters; real HTTP tests preserve quality policy and UI checks cover masking. Priority already exists. Album/artist differences remain tracked in LK-03. |
| LK-03 | Add-on album/artist browse and manifest refresh | Android + desktop | In progress | Declared album/artist endpoints, saved settings, encoded identifiers, separate caches and forced catalogue refresh now have real HTTP coverage on both clients. Native album/artist pages and source-owned playback are wired and tested; checked artwork now reaches native Coil. Source-local ratings, save/follow actions, signed-out shelves and cached page fallback are implemented with core/Compose/native checks; [library limits and remaining work](docs/SOURCE_LIBRARY.md). |
| LK-04 | Mornye/SpotiFLAC packages and repository management | Android + desktop | In progress | One shared runtime with repository hash/identity checks, explicit package review, updates, settings, disable/remove and both native Sources entries. UI tests pass; full SDK compatibility remains tracked with MN-04. |
| LK-05 | Per-provider download pacing and recoverable persisted queue | Android + desktop | In progress | Durable intent/order/quality recovery, per-provider worker admission and transfer pacing implemented. Cancellation/retry/fairness tests pass; [recovery evidence and remaining byte-resume checks](docs/DOWNLOAD_RECOVERY.md). Provider workspace transfers additionally validate resume ranges/validators; ordinary desktop transfers now share validator/range/atomic checks and blocked-read cancellation/retry tests. HLS/DASH now share bounded/cancellable transport, desktop streaming indexing and Android generation preservation; targeted checks pass. Full app service/restart and real-provider manifest walkthroughs remain. |
| LK-06 | Last.fm / YouTube Music discovery, mixes and radio | Android + desktop | Reviewing | YouTube and local radio/ranker already exist; account-driven Last.fm candidate feeds are a separate addition. |
| LK-07 | Library-first exact-recording resolution | Android + desktop | Existing | Keep recording/version matching and fallback transparent. Test ambiguous editions/covers and unavailable sources. |
| LK-08 | Quality-library audit and lossless upgrade preview | Android + desktop | In progress | Local review detects container signatures, missing/empty/permission-restricted files and byte-identical copies. It does not infer original-master quality from FLAC or extensions. Decoder/spectral audit, upgrade preview and atomic replacement remain outstanding. |
| LK-09 | Local library columns/source/quality/genre filters | Android + desktop | In progress | Library review adds detected-format/problem filters and title/artist/album/source text search on both hosts. Main library columns, measured quality and genre filters remain outstanding. |
| LK-10 | Artist identity exceptions/metadata fallback | Android + desktop | In progress | Shared structured-credit preference and protected punctuation names integrated into matching/listening statistics. Exact-name versus partial-credit regressions added; broader metadata fallback audit remains. |
| LK-11 | Linked playlist synchronization / ghost songs | Android + desktop | Planned | Stable IDs and source-qualified placeholders; sync must preserve local edits, likes and history. |
| LK-12 | Missing-download repair and duplicate review | Android + desktop | In progress | Read-only local duplicate review and preview/apply for saved-session locations are implemented; 29 focused core/UI checks pass. Exact recording IDs and stale-preview checks preserve queue/history metadata. Download-registry repair, moved-file fingerprint reconciliation and merge/remove remain outstanding. |
| LK-13 | Followed-artist release inbox | Android + desktop | Existing | Existing Cosmotune release checks/inbox; audit import and background refresh gaps separately. |
| LK-14 | Smart playlists, recaps and share cards | Android + desktop | Existing | Existing listening tools/replay/lyrics share are partial equivalents; expand rule fields and recap/share coverage only after gap audit. |
| LK-15 | Music-video matching/synchronization, saved videos, sync nudges | Android + desktop | Existing | OpenCanvas covers matching/sync; audit saved/offline video and per-video adjustment gaps without forking the engine. |
| LK-16 | Cast receiver UI / desktop Cast / return-to-device | Android + desktop/TV | Reviewing | Android Cast already exists; desktop and custom receiver are distinct work. Test format compatibility and state transfer. |
| LK-17 | Soulseek/slskd integration | Desktop primarily | Planned | Additional process, account, update/storage/network lifecycle. No slskd is bundled or run by this review. Android would need a separate approach. |
| LK-18 | Phone remote and web mode | Desktop + browser/TV | In progress | Shared paired LAN control server/page/controller and desktop/TV player settings integration implemented. Seven loopback/lifecycle tests, native settings/server interaction and real browser fixture controls/reconnect/focus checks pass. TV rendered QR and real native search/play/seek/volume/pause now pass through a paired browser; opening account controls revokes the session. Separate browser audio player/helpers now build; installed-helper playback and physical-network checks remain; [remote evidence](docs/LAN_REMOTE.md). |
| LK-19 | Mini-player, colour/glass surfaces, adaptive layouts | Desktop + Android | Existing | Reuse Cosmotune components. Audit concrete interaction gaps; no blanket UI replacement. |
| LK-20 | Scrobbling, backups and plugins | Android + desktop | Reviewing | Existing scrobbling/transfer cover parts. Verify offline queue, tokens, exports and plugin contracts individually. |

## Manga from Tsuki

| ID | Feature | Platform fit | Status | Integration requirement |
| --- | --- | --- | --- | --- |
| MG-01 | Manga beside Music and Video | Android + desktop | In progress | MANGA mode and native routes implemented. Signed minified Android emulator passed Music → Manga → Music → Video → Manga without a runtime crash; desktop compiles. Remaining platform/physical walkthroughs are distinct checks. |
| MG-02 | Local CBZ/image-folder reading and progress | Android + desktop | In progress | CBZ/ZIP and image-folder imports, source-qualified identity and atomic progress implemented; containment/order/restart/concurrent-write tests and real reader interactions. |
| MG-03 | Shared Kotatsu source adapter | Android + desktop JVM | In progress | Pinned shared parser adapter plus platform HTTP/cookies/JS/bitmap implementations. Manga Plus English passed live catalogue/chapter/page/image decode; browser-dependent sources remain. |
| MG-04 | Browse/search, series details, chapters and language filters | Android + desktop | In progress | Source/language selection, pagination, chapters and bounded global enabled-source search implemented. Live Manga Plus catalogue/details/reader and restore pass on minified Android. Small-screen source management now scrolls as one list with named toggle targets; a 400 dp/1.5-font interaction passes. Broader management/localization/accessibility checks remain. |
| MG-05 | RTL/LTR pages, webtoon scroll and zoom | Android + desktop | In progress | RTL/LTR pager, webtoon list, transform zoom, keyboard and separate reader controls implemented; Compose paging/reopen checks. Long-image completion/offset restoration and viewport keyboard scrolling now pass core and actual-image UI tests. Physical touch/accessibility checks remain. |
| MG-06 | Library, unread state, downloads and source updates | Android + desktop | In progress | Chapter read/unread history, reader navigation restoration, offline transfers/retry reuse and manual refresh implemented. In-flight cache pins and interrupted-download recovery tested. Broader management/device checks remain. |
| MG-07 | Mihon/Keiyoushi APK extension support | Android only directly | In progress | Private APK import/review/hash pinning, settings/removal and modern protobuf stores implemented; API 1.4/1.6 bridge compiled. Real store metadata (1,426 packages) verified; Android emulator fixture loading, memo mapping, Keystore progress, changed-APK rejection and disabled-state update checks pass. Encrypted source preferences/settings adapters, safe migration and corruption preservation also pass two emulator checks. POST image-request preservation and the current JSON helper ABI now pass the signed Android fixture; blocked page-read cancellation/retry and request budgets also pass core HTTP tests. A real publisher-verified Manga Plus 1.6.67 APK now passed catalogue/chapter/page/image decoding on the emulator; generated-entry ownership and expected HTTP/decompression contracts were fixed. Seven direct Android runtime tests pass; Gradle UTP collection has a separate transport issue. Broader external APK compatibility remains unverified. |
| MG-08 | Custom website parsers and browser-assisted sources | Platform dependent | In progress | Shared declarative HTML source management, three WordPress-family presets, search/pagination/metadata/AJAX chapters/pages and checked covers now compile on both hosts; four HTTP/storage fixtures and a management interaction pass. JSON/API CMS types, other presets and isolated browser verification remain; arbitrary URL parsing is not guaranteed. |
| MG-09 | Komga/user library source | Android + desktop | In progress | Optional Komga test/save/configuration and authenticated catalogue/chapter/page adapter implemented. Fixture HTTP, response budgets and redirect-key isolation pass; live user-server check remains. |

## Original Cosmotune work and release state

| ID | Feature | Platform | Status | Evidence / remaining work |
| --- | --- | --- | --- | --- |
| CT-01 | Music and Video modes with Cosmotune shared design | Android + desktop | Released | [v1.0.3](https://github.com/Vijay-Janarthanan/cosmotune/releases/tag/v1.0.3), existing app shells and video module. |
| CT-02 | OpenCanvas engine integration and separate project/site | Android + desktop | Released | [OpenCanvas plan/import](docs/OPENCANVAS_VIDEO_PLAN.md); engine repository/site already updated before this work. |
| CT-03 | Tiny local recommendation model | Android + desktop | Released | [Model](docs/LOCAL_RECOMMENDATIONS.md): 3,186,092 packed bytes; under 5 MB. Windows speed measured; physical phone benchmark outstanding. |
| CT-04 | Onion/Yambda and broader recommendation training data | Training tools | Released | Included in existing model pipeline; raw histories/data are not shipped in app packages. |
| CT-05 | Recording/language-aware lyrics and timing fixes | Android + desktop | Released | [Diagnosis](docs/LYRICS_DIAGNOSIS.md), [translation](docs/LYRICS_TRANSLATION.md); continue collecting real playback edge cases. |
| CT-06 | Mac video audio and portable decoder fallbacks | Desktop | Released | [v1.0.3 notes](docs/RELEASE_1.0.3.md); physical Mac/Linux listening still needs verification. |
| CT-07 | Intel Mac ONNX beat-model compatibility | Desktop | Released | [Compatibility proof](docs/BEAT_MODEL_COMPATIBILITY.md) and strict target-runtime inference tests. Preserve converted model. |
| CT-08 | Android bundled-model cache integrity | Android | Released | SHA256/atomic model installation; preserve during upstream imports. |
| CT-09 | Target-native packaging and package size audit | All releases | Released | [Package sizes](docs/PACKAGE_SIZE.md). Watch/source runtimes require new measurements. |
| CT-10 | Listening tools: sessions, rules, releases, connection and transfer | Android + desktop | Existing | [Implementation status](docs/IMPLEMENTATION_STATUS.md). Older feature-audit snapshots do not supersede current implementation evidence. |
| CT-11 | Branded application, crash/startup/tray fixes and versioning | Android + desktop | Released | [v1.0.1](docs/releases/1.0.1.md), [v1.0.2](docs/releases/1.0.2.md), v1.0.3. Preserve launcher/update identity. |
| CT-12 | 1.0.4 integration and signed multi-platform release | Android + desktop + Wear/TV + browser | In progress | Not committed/tagged/published. 5,120 native tests and 131 separate browser tests pass. Signed phone/Wear/TV checkpoints and web/helper archives validate; remaining scope and final packages are still outstanding. Full tracker scope remains the release gate. |

## Current priorities and unclosed checks

1. Complete the BitChord native and Wear OS audit/integration. Track additional TV/web features explicitly; do not label them completed by a native build.
2. Finish 1.0.4 regression/build/package validation, including matching phone/watch IDs, keys and protocol, before tagging/publishing.
3. Finish the Lokal gap audit and implement selected provider/library features with Android and desktop behaviour considered together.
4. Build Manga's working local-reader slice and a tested shared network source. Preserve Music playback while reading and release hidden Video decoders.
5. Evaluate provider-fed home sections and small online preference learning. Keep the bundled model below 5 MB and measure phone ranking latency.

No scheduling was created; monthly refresh scheduling remains deferred at the user's request. Private add-on URLs, tokens, accounts and signed audio URLs must never enter this tracker.
