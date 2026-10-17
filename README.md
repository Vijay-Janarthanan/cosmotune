# Cosmotune

Music that plays with its video, remembers what you like and picks what comes next.
For Android, Windows, Linux and macOS. Free, open source (GPLv3).

**Beta software — feedback and bug reports are welcome.**

## Download

[**Download the beta**](https://github.com/Vijay-Janarthanan/cosmotune/releases/tag/v1.0.0-beta1) · [All releases](https://github.com/Vijay-Janarthanan/cosmotune/releases) · [Report a bug](https://github.com/Vijay-Janarthanan/cosmotune/issues)

| Platform | File |
| :-- | :-- |
| Android | `Cosmotune-<version>-android-*.apk` (most phones: arm64) |
| Windows | `Cosmotune-<version>-windows-x64-setup.exe` or the portable zip |
| Linux | `.AppImage`, `.deb` or `.rpm` |
| macOS | `.dmg` for Apple Silicon (`arm64`) or Intel (`x64`) |

Cosmotune is not signed by Apple. If macOS says the app is damaged, open Terminal and run
`xattr -cr /Applications/Cosmotune.app`, then open it again.

## Made for listening

- A music player with colours that follow the album artwork.
- Synced music-video backgrounds powered by OpenCanvas, with selectable video quality.
- A dedicated video mode with search, channels, subscriptions and playlists.
- Lyrics, queues, downloads and playback controls across phone and desktop.
- Local listening history and preferences; no Cosmotune account required.

Android and desktop are supported today. An iOS app is not available yet.

## Feedback and contributing

Found a layout or playback problem? [Open an issue](https://github.com/Vijay-Janarthanan/cosmotune/issues)
with your app version, operating system, steps to reproduce and a screenshot when helpful.
For display issues, include your screen size and display/text scaling. Remove private information from logs.

Contributions are welcome. Read [the maintenance guide](docs/MAINTAINING.md) before changing code;
[FORK.md](FORK.md) records the changes we keep when merging upstream.

## Build

Needs JDK 21. `./gradlew :desktopApp:run` starts the desktop app;
`./gradlew :app:assembleDevDebug` builds the Android app. Notes for working on the code are in
[the maintenance guide](docs/MAINTAINING.md).

## What it contacts

The music video needs an internet connection. With it on (the default), Cosmotune searches YouTube for each song's
video by title and artist, then reads the start of the song's file and of up to six candidate videos' files from
YouTube's servers to line them up: about 320 KB per file, and more for a file with a large header or another layout,
up to the whole file. It also asks `cdn.jsdelivr.net` (which serves the `Vijay-Janarthanan/OpenCanvas` repository on
GitHub) for a ready-made sync map, sending only the song's YouTube id, and still talks to the music and cover sources
it used before. The video lookup sends no account or sign-in data. The maps it measures are kept in the app's own
folder and the video itself in the app's own cache folder (up to 150 MB on Android; on the desktop the media cache,
512 MB by default). On Android the video is not looked up on mobile data (any metered connection) unless you turn on
Settings > Appearance > "Play animated cover over cellular", which is off by default. Switch the video off under
Settings > Appearance > "Dynamic YouTube Canvas (OpenCanvas)", which appears only while "Animated cover art" (Android)
or "Animated canvas" (desktop) is on. Even with the video switched off, the app makes three small requests to YouTube
each time it starts (two to open the connection and one for an anonymous visitor id), also on mobile data.

**Video mode** looks videos up on YouTube with no account and no cookies: search, video pages, comments, channel and
RSS feeds, thumbnails and the video files. When it is on, SponsorBlock gets the first four characters of a hash of each
video id you open (`sponsor.ajay.app`); DeArrow gets the same for titles and the video id for thumbnails
(`sponsor.ajay.app`, `dearrow-thumb.ajay.app`); Return YouTube Dislike gets the video id of each video page you open
(`returnyoutubedislikeapi.com`). Each can be switched off in Video settings. Your history, subscriptions and
playlists are kept on the device.

## Disclaimer

Cosmotune uses YouTube's own player endpoints, which YouTube does not document and its terms do not
allow for third-party apps. Use it at your own risk.

**Video mode.** Cosmotune is not affiliated with, authorised or endorsed by YouTube or Google. Video mode reads
YouTube's public pages without an account, which YouTube's terms do not allow for third-party apps. Use it at your own
risk. The mode can be switched off by an update if asked.

## Acknowledgements

Cosmotune started as a fork of [BitChord](https://github.com/kushagrasinghx/BitChord) (GPLv3) and still
shares much of its playback engine. Its ideas and look draw on open-source players such as
[NewPipe](https://github.com/TeamNewPipe/NewPipe), [LibreTube](https://github.com/libre-tube/LibreTube),
[SimpMusic](https://github.com/maxrave-dev/SimpMusic) and [Namida](https://github.com/namidaco/namida).
The music-video canvas is [OpenCanvas](https://github.com/Vijay-Janarthanan/OpenCanvas).

Video mode reads its catalogue with [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor)
(GPL-3.0-or-later), plays video on the desktop with [libmpv](https://mpv.io/) (taken from the runtime packages of
[mediamp](https://github.com/open-ani/mediamp)), and uses the data of [SponsorBlock](https://sponsor.ajay.app/) (skippable
segments), [DeArrow](https://dearrow.ajay.app/) (titles and thumbnails) and
[Return YouTube Dislike](https://returnyoutubedislike.com/) (dislike counts). Licences and notices are in [NOTICE.md](NOTICE.md).
