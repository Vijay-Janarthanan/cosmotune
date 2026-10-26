# Remaining work

Reviewed 2026-10-26 against FEATURE_TRACKER.md and the published 1.0.4/1.0.5 notes. A source implementation, a passing automated check, a hardware/account check and publication are separate evidence.

## Unfinished integrations

- Provider packages: manifest-aware HLS/DASH stream relaying and the SDK FFmpeg API; final real-provider and file-only Android walkthroughs (MN-04).
- Manga: isolated verification browsers and cookie/user-agent handoff on Android and desktop for sites with browser checks (MG-08).
- Party server: live deployment and server validation; the tracker records Go/native CI checks as outstanding and needs results reconciled against the actual release jobs (BC-36).
- Android bounded queues: continuation growth, kill/restart restoration and Cast handover on a device (BC-05).
- Windows: media-card seeking, fullscreen/taskbar restoration, own-playlist ordering and own-key Last.fm with real OS/account state; desktop-owned rows still lack the upstream right-click menu (BC-35).

## Device/account validation

The existing device checklist covers Android/watch pairing and watch audio, TV remote navigation, both macOS architectures and Video mode, Linux media integration, USB DACs, Chromecast handover, browser-helper installation, signed-in providers/scrobbling and real Subsonic/Komga/party servers. The 4:5 layout and taller artist headers need final native visual checks as part of 1.0.6.

## Deliberately deferred or not adopted

Alternative branded icons/onboarding; Discord integration; desktop exclusive/hog-mode audio and native DSD/DoP; concerts, haptics and desktop widgets; Lite builds; per-video sync offsets; custom Chromecast receiver, desktop casting and TV as a Cast target; Soulseek. Some require product choices, services, artwork or hardware, rather than just additional code. Monthly dataset-refresh scheduling remains deferred at the user's request.

Manga UI translation, more archive formats, broader extension compatibility, local HTTP Komga, password-free Subsonic artwork transport and macOS/Linux system-keychain integration remain limitations documented in the 1.0.4 release notes.
