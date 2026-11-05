# Video playback validation — 17 October 2026

The OpenCanvas adapter now waits for the requested rendition within its 20-second lookup deadline instead of
settling 1.5 seconds after the preview. A synced preview remains a fallback if the full lookup times out.
Song cache entries and remembered artwork follow the quality setting. The vendored OpenCanvas engine is unchanged.

Windows preserves up to 1920 pixels on the longest edge, replacing its previous 540-pixel limit. The decoder
also drains delayed frames at EOF. Entering Video removes the music overlay, clears its open state and resets
its animation, so it cannot hide the watch page or reappear on returning to Music.

Verified locally on Windows:

- Live OpenCanvas lookup and FFmpeg decoding: 1920 × 1080, including seeks to 30, 90 and 150 seconds.
- Live NewPipe → stream selector → libmpv playback: 1920 × 1080, starting at 30 seconds and advancing past 31.
- Packaged Windows app: searched for and played a song, opened Now Playing, used Watch Video and paused the
  resulting video on its watch page.
- Android 16 emulator: the app's canvas settings/repository/cache/Media3 path rendered 640 × 360 at 360p,
  then 1920 × 1080 at 1080p. The instrumented test restores the previous settings.
- Shared, shared UI, desktop and Video JVM regression suites pass. Android unit tests have the same 17
  existing locale failures recorded in `BASELINE.md`; the canvas tests pass.
- Android debug APKs, the Windows app image, EXE installer and MSI build. macOS hardware playback was not exercised locally.

Reproduce the network checks with `VIDEO_LIVE=true`: run `:desktopApp:test --tests '*OpenCanvasClarityLiveTest'`
and `:video:jvmTest --tests '*DesktopVideoLiveTest'`. These tests use public test videos and do not run on normal builds.
The Android test is `:app:connectedDevDebugAndroidTest` with
`-Pandroid.testInstrumentationRunnerArguments.class=com.cosmotune.canvas.CanvasQualityLiveTest` and
`-Pandroid.testInstrumentationRunnerArguments.videoLive=true`.

Available source quality still limits clarity; selecting 1080p cannot restore detail absent from the uploaded video.
