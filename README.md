# Cosmotune

Music that plays with its video, remembers what you like and picks what comes next.
For Android, Windows, Linux and macOS. Free, open source (GPLv3).

Early alpha.

## Download

Releases: https://github.com/Vijay-Janarthanan/cosmotune/releases

| Platform | File |
| :-- | :-- |
| Android | `Cosmotune-<version>-android-*.apk` (most phones: arm64) |
| Windows | `Cosmotune-<version>-windows-x64-setup.exe` or the portable zip |
| Linux | `.AppImage`, `.deb` or `.rpm` |
| macOS | `.dmg` for Apple Silicon (`arm64`) or Intel (`x64`) |

Cosmotune is not signed by Apple. If macOS says the app is damaged, open Terminal and run
`xattr -cr /Applications/Cosmotune.app`, then open it again.

## Build

Needs JDK 21. `./gradlew :desktopApp:run` starts the desktop app;
`./gradlew :app:assembleDevDebug` builds the Android app. Notes for working on the code are in
`docs/MAINTAINING.md`.

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

## Disclaimer

Cosmotune uses YouTube's own player endpoints, which YouTube does not document and its terms do not
allow for third-party apps. Use it at your own risk.

## Acknowledgements

Cosmotune started as a fork of [BitChord](https://github.com/kushagrasinghx/BitChord) (GPLv3) and still
shares much of its playback engine. Its ideas and look draw on open-source players such as
[NewPipe](https://github.com/TeamNewPipe/NewPipe), [LibreTube](https://github.com/libre-tube/LibreTube),
[SimpMusic](https://github.com/maxrave-dev/SimpMusic) and [Namida](https://github.com/namidaco/namida).
The music-video canvas is [OpenCanvas](https://github.com/Vijay-Janarthanan/OpenCanvas). Licences and
notices are in `NOTICE.md`.
