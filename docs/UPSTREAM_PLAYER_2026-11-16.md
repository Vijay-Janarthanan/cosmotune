# Player refinement evidence — 16 November 2026

UR-1116-08 for the planned 1.0.24 integration. Behavior references are the pinned LastWave Native/Mochy ranges in `UPSTREAM_REVIEW_2026-11-16.md` and `UPSTREAM_SNAPSHOT_2026-11-16.json`. These are independent Kotlin/Orbit adaptations; existing attribution remains. Constellations and Continuous Line remain excluded. No build, unit test, render test or live playback was run during implementation; the parent owns the sequential gate.

## Reachable changes

- The existing Orbit More menu now opens one shared **Player options** dialog on Android and desktop. It exposes per-recording speed/pitch memory, inline Player lyrics, Auto/Center Orbit Read alignment, and opt-in pause-on-mute. Android additionally exposes Bluetooth reconnect resume, because Android provides actual audio-device route events.
- Per-song tuning is stored independently from global music defaults in bounded `SongPlaybackMemory` (100 identities/7,000 characters). Provider-qualified recording IDs remain distinct. Removing an override restores defaults; no song override is accepted for Podio. Podcast rates remain their own runtime values, and global/song music pitch no longer changes episodes.
- Android applies the selected recording's speed/pitch to each Media3 player and restores that song's base speed after Automix stretches. Changes made during an active blend settle after it ends. Desktop resolves the current song's tuning in its existing DSP path; because its final pitch/time-stretch follows mixing, differently tuned adjacent songs hand over without an overlapping blend. This prevents either recording's tuning processing the other one. Direct DSD continues to refuse software rate/pitch processing.
- `PlaybackPosition.rate` carries decoder speed to shared live progress rings. Desktop MPRIS, macOS metadata and Discord use effective engine rate rather than a global preference when a song override is active. The lyric renderer's existing dated, learned-rate clock and word/backing/duet timing remain intact.
- Inline lyrics reuse `CosmoLyricsView` with the same full lyric/timing model, translations, line seeking and duet handling, at a compact text scale. The preview appears beneath the song card when a phone has sufficient vertical room; on wide windows it appears when the side panel shows another tab. Small windows retain the full Lyrics panel instead of squeezing transport controls. Default is off.
- Auto alignment retains each line's writing direction; Center centers single-voice Orbit Read/inline text and scales it around the center. Duets keep separate physical lanes even with Center selected. Existing RTL sweep, word lift, backing text, sharing and reduced-motion behavior remain.
- The phone landscape control column now scrolls vertically, keeping menu/transport/panel controls reachable in a short or folded window. A record with no available media height no longer forces a 120 dp disc over neighboring controls. The existing adaptive layout chooser and Orbit cards are preserved.
- Bluetooth resume is off by default. It requires a real newly added Bluetooth output after a locally playing music track disconnected, the same still-paused recording, a bounded reconnect interval, and local unlocked ownership. Initial device enumeration, another track, a manual pause, receiver ownership, Podio, an expired interval, or zero volume with pause-on-mute enabled prevents automatic resume. It does not generically auto-play whenever any Bluetooth device connects.
- Pause-on-mute is also off by default. Android observes actual media-volume changes; desktop observes its own app-volume control. Only a positive-to-zero transition pauses music. Unmuting does not resume. Desktop JVM mixer enumeration does not reliably identify Bluetooth connection events, so no guessed desktop reconnect behavior is advertised.
- Fixed a concrete speculative-work bug: next-Canvas prefetch now keys on the actual next recording and on both cellular permission/metered state. Reordering an equal-length queue or changing data policy cancels/replaces the old delayed lookup.

## Existing equivalents retained

- TV phone remote is already paired/authenticated and scoped to the active unlocked account/profile, with queue/search/playback/volume controls (`tv/.../TvPhoneRemote.kt`, `:remote` server/controller and the phone remote page). No parallel remote protocol was added.
- Live sleep countdown, Extend/Cancel and after-song behavior already exist in shared `SleepTimerUi`, Android/Desktop timer owners, Orbit More/status chips and TV's sleep sheet. No second timer was added.
- Lyric clocks already stop with their composed panel, preserve reduced-motion mode and draw timing in the draw phase; no wholesale ticker/render rewrite was needed.

## Verification handoff

Run only after all implementation workers are frozen, sequentially:

```powershell
.\gradlew.bat :shared:jvmTest --tests com.cosmotune.extras.PlaybackExtrasTest --tests com.cosmotune.podio.PodioPlaybackPolicyTest --tests com.cosmotune.design.LyricsFeaturesMetricsTest
.\gradlew.bat :sharedUi:jvmTest --tests com.cosmotune.ui.LyricsParityContractTest --tests com.cosmotune.ui.LyricsRtlPlacementTest --tests com.cosmotune.ui.LyricsCurrentLineTest --tests com.cosmotune.ui.NowPlayingPanelsContractTest --tests com.cosmotune.ui.NowPlayingContractTest --tests com.cosmotune.ui.NowPlayingSheetsVisualReviewTest --tests com.cosmotune.ui.SleepTimerUiTest
.\gradlew.bat :app:testProdDebugUnitTest --tests com.cosmotune.music.playback.SleepTimerTest --tests com.cosmotune.music.playback.LoudnessProcessorTest
.\gradlew.bat :desktopApp:test --tests com.cosmotune.music.desktop.DesktopAudioSpeedTest --tests com.cosmotune.music.desktop.DesktopSleepTimerTest
.\gradlew.bat :remote:jvmTest
```

`PlaybackExtrasTest` adds persistence/restart, provider identity, global-default restoration, Podio isolation, opt-in defaults, manual cancellation, stale/wrong-owner/wrong-recording reconnect and duet-centering cases. Existing RTL source contracts now account for the explicit Center branch while retaining physical-lane assertions. `NowPlayingSheetsVisualReviewTest.playerOptions` renders the real options dialog in dark/light portrait and 740×320 short landscape; artifacts are written under `build/old-screens/npsheets/` when the parent runs the test. These renders have not yet been generated or visually reviewed.

Physical Android Bluetooth/noisy-event ordering, media-volume events, audible pitch/speed transitions, direct DSD guards and desktop sink clocks still require device validation. JVM fixtures and source checks do not certify that hardware behavior.
