# Remaining work

Reviewed 2026-10-26 against FEATURE_TRACKER.md and the published 1.0.4/1.0.5 notes. A source implementation, a passing automated check, a hardware/account check and publication are separate evidence.

## Version 1.0.7 preparation

Automatic package-source comparison, tappable audio details, discovery country/language controls and local playlist preview/save are included in the 1.0.7 source. See [the provider capability matrix](PROVIDER_CAPABILITIES.md), [the Amazon investigation](AMAZON_RESOLUTION_INVESTIGATION.md) and [release notes](releases/1.0.7.md). They require the 1.0.7 installers; the 1.0.6 downloads do not contain them.

The Windows playback freeze is fixed in source with native WASAPI recovery and UI-safe device readouts ([investigation](WINDOWS_PLAYBACK_FREEZE_2026-10-26.md)). Now Playing also restores desktop header dragging, and settings includes the creator credit. Publication and actual upgrade testing remain separate from local validation.

The supplied Qobuz HTTP add-on has native FLAC decoding proof (24-bit/44.1 kHz and 16-bit/44.1 kHz for the two test songs). The separate Mornye Qobuz package has live session-verification proof through the dedicated window; its audio is a separate check. Amazon search, grant exchange, ticket delivery and [native preparation](PROVIDER_MEDIA_PREPARATION.md) now pass an actual Windows decode as 24-bit/44.1 kHz FLAC. E-AC-3/AC-4 decoding and immersive device output remain separate checks.

The [operator follow-up](PROVIDER_OPERATORS.md) fixes the missing native verification entry, immediate grant exchange, retry and ticket-header preservation for the four signed packages. [Dedicated windows](PROVIDER_VERIFICATION_BROWSER.md) now capture the result automatically; real Windows Qobuz verification passed without pasting. Android service compatibility, macOS/Linux browser execution and downstream audio availability remain separate from protocol/UI checks.

## Unfinished integrations

- Provider packages: manifest-aware HLS/DASH stream relaying, SDK encoding options beyond stream copy, additional post-processing hooks, and final real-provider Android walkthroughs (MN-04). Native `ffmpeg.mov_key` decryption/remux and SDK file inspection/stream copy are implemented on desktop and Android; see [preparation evidence](PROVIDER_MEDIA_PREPARATION.md). Real macOS/Linux provider execution remains to be checked.
- Manga: isolated verification browsers and cookie/user-agent handoff on Android and desktop for sites with browser checks (MG-08).
- Party server: live deployment and server validation; the tracker records Go/native CI checks as outstanding and needs results reconciled against the actual release jobs (BC-36).
- Android bounded queues: continuation growth, kill/restart restoration and Cast handover on a device (BC-05).
- Windows: media-card seeking, fullscreen/taskbar restoration, own-playlist ordering and own-key Last.fm with real OS/account state; desktop-owned rows still lack the upstream right-click menu (BC-35).

## Device/account validation

The existing device checklist covers Android/watch pairing and watch audio, TV remote navigation, both macOS architectures and Video mode, Linux media integration, USB DACs, Chromecast handover, browser-helper installation, signed-in providers/scrobbling and real Subsonic/Komga/party servers. The 4:5 layout and taller artist headers need final native visual checks as part of 1.0.6.

## Deliberately deferred or not adopted

Alternative branded icons/onboarding; Discord integration; desktop exclusive/hog-mode audio and native DSD/DoP; concerts, haptics and desktop widgets; Lite builds; per-video sync offsets; custom Chromecast receiver, desktop casting and TV as a Cast target; Soulseek. Some require product choices, services, artwork or hardware, rather than just additional code. Monthly dataset-refresh scheduling remains deferred at the user's request.

Manga UI translation, more archive formats, broader extension compatibility, local HTTP Komga, password-free Subsonic artwork transport and macOS/Linux system-keychain integration remain limitations documented in the 1.0.4 release notes.
