# Remaining work

Reviewed 2026-10-28 against FEATURE_TRACKER.md, published releases through 1.0.10. A source implementation, a passing automated check, a hardware/account check and publication are separate evidence.

## Version 1.0.10 release and integration checks

The [1.0.10 release build](https://github.com/Vijay-Janarthanan/cosmotune/actions/runs/40649671899) completed successfully. The [release](https://github.com/Vijay-Janarthanan/cosmotune/releases/tag/v1.0.10) is published with 49 non-empty assets. Its [notes](releases/1.0.10.md) cover unreadable MP4 recovery, avoidance of replayed footage, an eight-second search budget, film/album captions, provider artist matching and fallback, decoder quality pills and desktop shell controls. Download links now resolve to published 1.0.10 installers. Do not move the existing tag for documentation changes.

The three vendored OpenCanvas repairs still need porting to its standalone repository; the host's import record tracks them. The website documents this distinction rather than advertising them in the published 1.0.0 library artifact. Windows shell API checks and rendered tray cards do not replace interactive taskbar/tray testing. macOS tray/Dock execution, the new Amazon match's audio request with a verified session, and actual immersive output remain separate checks.

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

- Podio: full podcast archive pagination, episode downloads/OPML/sleep timers, directory breadth and more public Spotify shows, physical Android and macOS/Linux listening checks, and TV/Wear/browser-specific interfaces. Public radio/RSS works without a listener account; Spotify links require a matching public publisher feed. Direct Spotify-only and paid-only audio is unsupported. [Implemented scope and evidence](PODIO_RESEARCH.md).
- OpenCanvas: a reliable film-video sync for the stereo Germaniyin Senthen Malare recording. The animated-artwork upload is rejected; the tested 5.1 remaster fails alignment. Preserve the cover fallback until a matching, publisher-verified clip is established.

- Provider packages: manifest-aware HLS/DASH stream relaying, SDK encoding options beyond stream copy, additional post-processing hooks, and final real-provider Android walkthroughs (MN-04). Native `ffmpeg.mov_key` decryption/remux and SDK file inspection/stream copy are implemented on desktop and Android; see [preparation evidence](PROVIDER_MEDIA_PREPARATION.md). Real macOS/Linux provider execution remains to be checked.
- Manga: isolated verification browsers and cookie/user-agent handoff on Android and desktop for sites with browser checks (MG-08).
- Party server: live deployment and server validation; the tracker records Go/native CI checks as outstanding and needs results reconciled against the actual release jobs (BC-36).
- Android bounded queues: continuation growth, kill/restart restoration and Cast handover on a device (BC-05).
- Windows: media-card seeking, fullscreen/taskbar restoration, own-playlist ordering and own-key Last.fm with real OS/account state; desktop-owned rows still lack the upstream right-click menu (BC-35).

## Device/account validation

The existing device checklist covers Android/watch pairing and watch audio, TV remote navigation, both macOS architectures and Video mode, Linux media integration, USB DACs, Chromecast handover, browser-helper installation, signed-in providers/scrobbling and real Subsonic/Komga/party servers. The 4:5 layout and taller artist headers need final native visual checks as part of 1.0.6.

## Deliberately deferred or not adopted

Alternative branded icons/onboarding; Discord integration; desktop exclusive/hog-mode audio and native DSD/DoP; concerts, haptics and desktop widgets; Lite builds; per-video sync offsets; custom Chromecast receiver, desktop casting and TV as a Cast target; Soulseek. Some require product choices, services, artwork or hardware, rather than just additional code. Monthly dataset-refresh scheduling remains deferred at the user's request.

Manga UI translation, more archive formats, broader extension compatibility, password-free Subsonic artwork transport and macOS/Linux system-keychain integration remain limitations documented in the 1.0.4 release notes. Android's transport now permits HTTP for public Podio media; a real HTTP Komga server still needs a device walkthrough rather than retaining the old claim that Android blocks every remote HTTP address.
