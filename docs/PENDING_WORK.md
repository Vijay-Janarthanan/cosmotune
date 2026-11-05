# Remaining work

Reviewed 2 November 2026 against the 1.0.13 baseline, updated for published 1.0.14 and the [new upstream comparison](UPSTREAM_REVIEW_2026-11-02.md). A source implementation, passing checks, device/account validation and publication are separate evidence.

## Current release: 1.0.14

The release workflow passed for Android phone/watch/TV, Windows, Linux, both macOS architectures and browser helpers. [1.0.14 notes](releases/1.0.14.md) describe the supported scope; physical-device and provider-account limits remain below.

The subsequent [UI/provider parity pass](UI_PROVIDER_PARITY_2026-11-02.md) repairs missing Android mode navigation, provider discovery and stale-store session persistence, settings categories, and shared Visuals controls. It also explicitly lists upstream features that remain unintegrated. These changes are included in published 1.0.14.

Versions 1.0.12 and 1.0.13 added the Video feed/account/local-player/PiP/smoothing work and subsequent lifecycle, Up next and shutdown repairs. The 1.0.13 release is published with 49 assets. The published tag includes the completed-quit deadline cancellation and slow-macOS Compose test-harness repair. See [1.0.12 notes](releases/1.0.12.md) and [1.0.13 notes](releases/1.0.13.md).

The current upstream integration adds guarded translated-title/guest-credit matching and search, session-scoped private-upload hints, per-track Atmos metadata support, corrected Simplified Chinese music-region routing, a compatible Manga parser refresh, and advancing Windows installer metadata. These edits need a new build; they do not update existing 1.0.13 installers. Validation is recorded in the [2 November audit](UPSTREAM_REVIEW_2026-11-02.md).

The working tree now contains the three previously deferred areas in specific supported scopes: [phone/desktop library and file synchronization plus playlist download additions](DEVICE_SYNC_AND_PLAYLIST_UPDATES.md), [phone-authoritative Watch Podio](WATCH_PODIO.md), and [strict desktop DoP transport on Windows/Linux](DSD_TRANSPORT_AND_EQ.md). These source changes are not published and still await the current combined validation gate and physical device checks. Raw native DSD, macOS DoP and Android userspace USB audio remain unsupported; a route label or USB descriptor does not establish those transports.

The user approved the [headphone and discovery scope](HEADPHONES_AND_DISCOVERY_PLAN_2026-11-02.md). Device-local EQ profiles, platform output/spatial facts, broader bounded music discovery, explicit feedback, editable mixes, shared list controls and recoverable Video Home feeds are implemented in the working tree; source, verification and publication remain separate. See [music scope](MUSIC_DISCOVERY_AND_FEEDBACK.md) and [video repair evidence](VIDEO_PLAYBACK_REPAIRS_2026-11-02.md). Shared Manga browsing/source-management cleanup and the requested normalized `hent` exclusion are implemented. [Read](READING_BOOKS_PLAN_2026-11-02.md) now adds Books alongside Manga, real EPUB/PDF reading, bookmarks, local imports, catalogue search/trending and configured OPDS acquisition in 1.0.14. Paperback extension execution and native library-loan DRM are not implemented. Other optional upstream features remain in the current audit.

## Version 1.0.11 release and validation

The 1.0.11 source adds recording-based JioSaavn matching, measured video discovery/expiry recovery, truthful audio telemetry, opt-in direct desktop output, playlist import/activity views, public podcast archives/downloads/OPML and TV/browser Podio, Manga collections/backups/scheduling/archive imports/verification, provider manifest preparation and browser Automix. See [release notes](releases/1.0.11.md). The complete local release gate and repaired GitHub release workflow passed. [Version 1.0.11](releases/1.0.11.md) is published with 49 non-empty assets. [Validation details](releases/1.0.11-validation.md).

Watch Podio, library/file synchronization and DSD/DoP were unimplemented in the 1.0.11 release. Their current working-tree scopes are described above; this does not change that historical release. Public party-server deployment needs a selected hosting target. Physical-device/account validation and unsupported media/source layouts remain separate limitations, described below and in the release notes.

## Version 1.0.10 release and integration checks

After 1.0.10: the wide Now Playing header now reserves space for the audio-quality row and grows with the text scale. Quality pills use an explicit line height so inherited typography cannot clip their labels. This source repair requires a new app build; the published 1.0.10 installers do not contain it.

The same pending repair pass preserves completed provider offers, applies useful desktop upgrades without waiting for stalled siblings, distinguishes native YouTube from a same-named failed package, exposes safe HTTP status codes, recognizes the Udit Narayan/Narayanan catalogue spelling and normalizes S/O film names for video discovery. Live checks decoded Aathi from Amazon as 24-bit FLAC, decoded Ayyo Ayyo from JioSaavn and aligned its Think Tapes video. Amazon Ayyo still returned HTTP 404; the current YouTube package errored and Tidal's Aathi probe timed out. See the [20-repository review and detailed evidence](UPSTREAM_REVIEW_2026-10-28.md). New upstream features in that report have not been imported wholesale.

The 1.0.10 release build completed successfully. The [release](releases/1.0.10.md) is published with 49 non-empty assets. Its [notes](releases/1.0.10.md) cover unreadable MP4 recovery, avoidance of replayed footage, an eight-second search budget, film/album captions, provider artist matching and fallback, decoder quality pills and desktop shell controls. Download links now resolve to published 1.0.10 installers. Do not move the existing tag for documentation changes.

The MP4 frame-table recovery, alternate-stream retry, search budget and film-abbreviation repairs are now also in standalone OpenCanvas source; the host's import record tracks its pinned revision. Cosmotune's steady framing switch and forward-only map guard remain host integration choices. The published 1.0.0 library artifact is older. Windows shell API checks and rendered tray cards do not replace interactive taskbar/tray testing. macOS tray/Dock execution, the original Amazon artist-match case with a verified session, and actual immersive output remain separate checks.

## Version 1.0.9 radio and station pages

[1.0.9](releases/1.0.9.md) is published: satellite imagery, city-level zoom and ring selection, up to 3,000 directory rows, approximate locations, a curated extra-station list, station pages with weather/photos/articles, native sidebars and podcast charts/genres. Physical phone and desktop interaction checks remain outstanding. Directory coverage varies; approximate coordinates are disclosed. [Live product pages and deployment sources](LIVE_WEBSITES.md).

## Version 1.0.8 UI and discovery repairs

Version 1.0.8 is published. It adds shared Manga/Podio mode chrome, complete tonal palette roles, a persistent artist-photo fallback, lowercase radio language queries, larger radio result sets and an expanded map, and prominent regional discovery with curated language choices. See [1.0.8 notes](releases/1.0.8.md). Automated fixture/native compilation evidence does not replace physical-device interaction and listening checks.

## Version 1.0.7 features

Automatic package-source comparison, tappable audio details, discovery country/language controls and local playlist preview/save are included in the 1.0.7 source. See [the provider capability matrix](PROVIDER_CAPABILITIES.md), [the Amazon investigation](AMAZON_RESOLUTION_INVESTIGATION.md) and [release notes](releases/1.0.7.md). They require the 1.0.7 installers; the 1.0.6 downloads do not contain them.

The 1.0.7 Windows playback freeze repair shipped with native WASAPI recovery and UI-safe device readouts ([investigation](WINDOWS_PLAYBACK_FREEZE_2026-10-26.md)). Now Playing also restores desktop header dragging, and settings includes the creator credit. Actual upgrade and listening checks remain separate from automated validation.

The supplied Qobuz HTTP add-on has native FLAC decoding proof (24-bit/44.1 kHz and 16-bit/44.1 kHz for the two test songs). The separate Mornye Qobuz package has live session-verification proof through the dedicated window; its audio is a separate check. Amazon search, grant exchange, ticket delivery and [native preparation](PROVIDER_MEDIA_PREPARATION.md) now pass an actual Windows decode as 24-bit/44.1 kHz FLAC. E-AC-3/AC-4 decoding and immersive device output remain separate checks.

The [operator follow-up](PROVIDER_OPERATORS.md) fixes the missing native verification entry, immediate grant exchange, retry and ticket-header preservation for the four signed packages. [Dedicated windows](PROVIDER_VERIFICATION_BROWSER.md) now capture the result automatically; real Windows Qobuz verification passed without pasting. Android service compatibility, macOS/Linux browser execution and downstream audio availability remain separate from protocol/UI checks.

## Unfinished integrations

- Podio: archive pagination, finite downloads, OPML, resume/played progress, sleep controls and TV/browser pages are implemented. A phone-authoritative Watch Podio catalogue/action implementation is now in 1.0.14 ([scope and pairing requirements](WATCH_PODIO.md)); its CI gate passes; a physical phone/watch walkthrough remains outstanding. Physical Android/macOS/Linux listening and wider catalogue coverage remain separate checks. Public radio/RSS works without a listener account; Spotify links require a matching public publisher feed. Direct Spotify-only and paid-only audio is unsupported. [Implemented scope and evidence](PODIO_RESEARCH.md).
- OpenCanvas: a reliable film-video sync for the stereo Germaniyin Senthen Malare recording. The animated-artwork upload is rejected; the tested 5.1 remaster fails alignment. Preserve the cover fallback until a matching, publisher-verified clip is established.

- Provider packages: supported finite HLS/DASH preparation, stream copy, PCM16/24 WAV and verified-lossless FLAC encoding are implemented. Live/changing layouts, arbitrary processing hooks and final real-provider Android walkthroughs remain limited (MN-04). Native `ffmpeg.mov_key` decryption/remux and SDK file inspection/stream copy are implemented on desktop and Android; see [preparation evidence](PROVIDER_MEDIA_PREPARATION.md). Real macOS/Linux provider execution remains to be checked.
- Manga: isolated verification browsers and temporary cookie/user-agent handoff, collections/backups, optional update checks and additional TAR-family archives are implemented. Real extension/device compatibility and unsupported heavy archive decoders remain separate checks (MG-08).
- Party server: local Go formatting/vet, 44 top-level tests (47 cases), browser loopback handshakes and Windows/Linux amd64/Linux arm64 builds pass. Public deployment and real two-client/hardware checks still need a selected hosting target; the latest CI result must be reconciled with the released commit (BC-36). [Validation and deployment prerequisites](BACKEND_VALIDATION_2026-10-28.md).
- Android bounded queues: continuation growth, kill/restart restoration and Cast handover on a device (BC-05).
- Windows: media-card seeking, fullscreen/taskbar restoration, own-playlist ordering and own-key Last.fm with real OS/account state; desktop-owned rows now support secondary-click actions; physical shell/account checks remain (BC-35).

## Device/account validation

The existing device checklist covers Android/watch pairing and watch audio, TV remote navigation, both macOS architectures and Video mode, Linux media integration, USB DACs, Chromecast handover, browser-helper installation, signed-in providers/scrobbling and real Subsonic/Komga/party servers. The 4:5 layout and taller artist headers need final native visual checks as part of 1.0.6.

## Deliberately deferred or not adopted

Alternative branded icons/onboarding; Discord integration; raw native DSD, macOS DoP and Android userspace USB audio (the 1.0.14 strict Windows/Linux DoP path is described above; existing exclusive/hog-mode PCM output still requires hardware validation); concerts, haptics and desktop widgets; Lite builds; per-video sync offsets; custom Chromecast receiver, desktop casting and TV as a Cast target; Soulseek. Some require product choices, services, artwork or hardware, rather than just additional code. Monthly dataset-refresh scheduling remains deferred at the user's request.

Manga technical-text translation, RAR/7z/PDF decoding, broader extension compatibility, password-free Subsonic artwork transport and macOS/Linux system-keychain integration remain limitations documented in the 1.0.4 release notes. Android's transport now permits HTTP for public Podio media; a real HTTP Komga server still needs a device walkthrough rather than retaining the old claim that Android blocks every remote HTTP address.
