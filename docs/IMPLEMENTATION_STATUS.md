# Listening tools and reliability

Implemented 22 October 2026 following [the app review](APP_REVIEW.md), on top of `a0d2690f`. The Android player redesign was cancelled and has not been implemented. The six design images are reference material only.

## Changes available in the app

The profile drawer's **Your listening** entry opens the following working flows on Android and desktop (Windows, Linux and macOS use the same desktop adapter):

- **Sessions:** save, rename, delete and open named queues, preserving duplicate entries, complete track metadata, queue position, repeat and shuffle. Opening replaces the current queue and leaves playback paused. Missing local files are skipped without changing the saved session. Listen Together participation blocks queue replacement.
- **Smart playlists:** preview and save explicit liked/downloaded/recent-history rules; edit, pin, delete, play or save a fixed playlist to the existing local library. Rules use the currently available library, rather than claiming to search the entire catalogue.
- **Replay playlists:** use the real monthly/yearly listening statistics to create local playlists; handle empty history and prevent identical repeated submissions.
- **Getting started:** skippable setup with the platform's offered app languages, local-library navigation, existing music import and a lower-data preset (lower streaming quality and animated canvas off). Download quality is retained.
- **New releases:** artist search/follow, manual release checks, first-check baseline, persistent inbox, read state and release navigation. It reports newly discovered release IDs; it does not claim to determine original publication dates or schedule background notifications.
- **Connection:** independent catalogue and actual-source media checks, bounded reads/timeouts, persisted last check, useful failures and a reviewable report export that excludes secrets, URLs and raw provider errors.
- **Backup & transfer:** export/import sessions, smart rules and inbox metadata through Android SAF or desktop native file pickers. Stable-ID merges are idempotent; local edits win. Credentials and audio are excluded. Local paths are disclosed. Android content-provider grants cannot be used on desktop. This is explicit file transfer; automatic cloud sync and a complete device/account backup are outside this implementation.

Other completed changes:

- Recording-aware lyrics matching for Bini/LRCLIB; language/version identity and Indic vowel preservation in LrcRed; metadata-aware ISRC caching; identifiable YouTube Music Lyrics endpoints rather than arbitrary tab fallback.
- LRC offsets apply to nested word/syllable timing, repeated timestamps expand correctly, and whole-second/tenths line timestamps are supported.
- Desktop lyrics cache follows metadata, provider order/enabled state and syllable preferences; failures retry immediately. Existing timing adjustment is reachable, and another-source recovery invalidates rejected cached results.
- Desktop per-track loudness normalization uses the original source's metadata, with bounded gain, smooth changes and limiting. Sources without compatible metadata retain their original gain.
- Desktop selected-lyrics PNG export with preview, complex-script layout and native save dialog.
- Useful Downloads/sidebar actions, persisted desktop library pins, settings categories beside existing search, icon tooltips, removal of the unavailable Library mode from normal navigation, and reduced Home hero repetition while keeping full Recents.
- Android Chinese locale declarations/qualifiers and missing language labels repaired; playlist IDs no longer depend on milliseconds, and local playlist media references survive persistence.
- Manual check/release workflows cover shared UI, video and real UI tests. The packaged desktop runtime includes `jdk.accessibility`.

New listening-tools copy currently uses English; the existing translated interface and all offered locale tests are retained. Setup applies the selected app locale to existing translated screens.

## Verification

The original full test run plus Android debug build succeeded in 4 minutes 28 seconds. A broader follow-up succeeded in 4 minutes 18 seconds. After the confirmed live lyric-association fix, all suites, Android APKs and the Windows app image were validated together successfully in **3 minutes 56 seconds**:

| Suite | Total | Failed/errors | Skipped |
| --- | ---: | ---: | ---: |
| Shared | 2,105 | 0 | 6 |
| Shared UI | 586 | 0 | 0 |
| Video | 635 | 0 | 1 |
| Desktop | 361 | 0 | 1 |
| Real UI interactions | 5 | 0 | 0 |
| Android | 979 | 0 | 0 |
| **Total** | **4,671** | **0** | **8** |

There are **4,663 passing tests**. Skipped cases include existing opt-in/live checks; the live lyric reproduction is recorded separately. `git diff --check` passes, and vendored OpenCanvas sources are unchanged. Android debug APKs were built for arm64-v8a, armeabi-v7a, x86_64 and universal under `app/build/outputs/apk/dev/debug/`; these are development artifacts, not a published signed production release.

Windows `:desktopApp:createDistributable` also succeeded in 1 minute 6 seconds. The launcher is `desktopApp/build/compose/binaries/main/app/Cosmotune/Cosmotune.exe` and the complete adjacent `app/` and `runtime/` folders are required. `jimage list` confirms that its runtime includes `jdk.accessibility`. Packaging required a full JDK: the installed Android Studio JBR has no `jpackage.exe`. Official Temurin 21.0.12.1+1 was downloaded to ignored `build/tooling/`, verified against Adoptium's published SHA-256, and selected explicitly for packaging. No installer was published or installed, and this newly built app image has not received a live UI/playback walkthrough.

```powershell
.\gradlew.bat :shared:jvmTest :sharedUi:jvmTest :video:jvmTest :desktopApp:test :uiTests:test :app:testDevDebugUnitTest :app:assembleDevDebug --offline --console=plain
```

Real Compose interaction tests click the session, smart-playlist, Replay and diagnostic controls and inspect persistent results. A fifth test reaches saved-session actions at 420 dp and 1.5× text. Screenshots are in `uiTests/build/reports/listening-ui/`; these are isolated component screens using the Material fallback, not full-device screenshots. Existing source contracts, palette tests and offscreen player regressions remain in their original suite. UI-test dispatcher dependencies are isolated in `uiTests`.

The shared lyrics suite has 107 cases, including 30 new regression tests for wrong/dubbed recordings, Indic text, timing offsets, repeated timestamps, endpoint selection and exact-video catalogue verification. Desktop tests additionally cover cache configuration/rejection/retry and mixed-script PNG output. Store/network tests cover restart, missing tracks, duplicate queue entries, rules, inbox deduplication, additive import, corrupt-file preservation, diagnostic redaction and bounded media reads.

## Release checks still requiring devices or other hosts

- No Android device/emulator is connected (`adb devices -l` returned an empty list). APK compilation and Android unit tests do not establish real playback, background lifecycle, offline downloads or TalkBack behavior.
- Linux/macOS package installation and native runtime behavior need their own hosts. Their shared implementations and manual CI jobs include these fixes; this Windows run does not certify those operating systems.
- Windows screen-reader checks need Java Access Bridge plus NVDA/JAWS. Adding the JDK module and semantic labels does not establish accessibility certification.
- Desktop cannot find the configured CMake/Ninja/MinGW toolchain, so optional desktop analyser/window native build tasks are skipped. Android's NDK/CMake tasks completed. The Android Gradle plugin also warns about compile SDK 37 support.
- Live provider availability, exact audio/lyric alignment for alternate masterings/video intros, accounts, a two-device party, Cast hardware and the existing opt-in Tor route require their real service/device checks.
- Follow-up live diagnosis recovered the exact “Aadiyile Sedhi” video ID locally and confirmed YouTube's incorrect lyric association. The shared provider now verifies its catalogue counterpart through an explicit album track/video-ID link; the actual provider pipeline changed from 45 Telugu lines to 55 Tamil lines. These documents are untimed. See [LYRICS_DIAGNOSIS.md](LYRICS_DIAGNOSIS.md) for evidence, the bounded fallback and synchronization limits.

See [MAINTAINING.md](MAINTAINING.md) for storage/merge rules and [FORK.md](../FORK.md) for upstream integration points.
