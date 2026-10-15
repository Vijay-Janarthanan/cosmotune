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
