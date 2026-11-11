# Continue the AETHER / Euthopiar integration

> **Continued after this handoff.** The build issues below were fixed and items 1 to 6 of "Still finish / assess" were worked through; the current done / unverified / not-done matrix is in [AETHER_EUTHOPIAR_INTEGRATION.md](AETHER_EUTHOPIAR_INTEGRATION.md). The rest of this page is the handoff as written.

Handoff requested 11 November 2026 because the user's Codex tokens are nearly exhausted. **Incomplete: do not claim everything is done, restart research, discard working files, or release untested code.**

## Request and workspace

User: integrate all Euthopiar features and all AETHER features **except Constellations and Continuous Line**. Preserve Cosmotune's Orbit design, improve the existing lyric presentation, and implement Android/desktop parity. Latest steering asks how to let Claude continue.

Workspace `C:\Dev\Cosmotune`, PowerShell. Starting HEAD `751b2b2676fc235b7127c47fc3bdc511e1b1795d`, main, public **v1.0.21**. No version bump, commit, push or release for this integration. Do not invent a release tag. User's earlier preference: failed release builds must be repaired under the same tag, not incremented again.

**Many added implementation/test files are untracked.** Inspect `git status --short` as well as diff. Preserve all work. No Gradle run is active; last session31372 completed failed. Do not overlap builds. No AGENTS.md found. Applied skill `C:\Users\Zebu\.agents\skills\redesign-existing-projects\SKILL.md`; shared existing design primitives, no image generation.

## Existing research

Read `docs/UPSTREAM_REVIEW_AETHER_EUTHOPIAR_2026-11-11.md`. Its ending saying only a review was added describes the earlier audit, not current code. `docs/AETHER_EUTHOPIAR_INTEGRATION.md` also predates later work; this handoff supersedes its implementation/pending list.

Ignored research and logs: `build/upstream-reviews/2026-11-11/`. Source excerpts, release JSONs, official contracts already downloaded; do not redo the research.

- AETHER `fd019594548e52be06f6339bb758ff5661f15f3d`, release2.1.0, GPL3. Hangul rules and39-model headphone catalogue adapted with attribution and `licenses/AETHER-LICENSE.txt`. New acoustic comparison also credits AETHER/PODIUM in its header; finish/check NOTICE attribution.
- Euthopiar `b7172cbcb972cacdcae7fb35f87ac8cffa40d850`, release1.1.0-Stable. No licence found: behaviors implemented independently against protocols, no copying unlicensed source. JSON snapshots have double-hyphen filenames, e.g. `Euthopiar--tree.json`.
- Official Trakt/Simkl device/list/history contracts saved. Trakt auth `auth.trakt.tv`, API `api.trakt.tv`; public client flow, no embedded secrets. Simkl V2 `/oauth2/device` and `/oauth2/token`, form read/write scopes; activities check before expensive list reads.
- AniSkip OpenAPI saved; SubDL individual unpacked subtitle contract reviewed.
- CryptoJS4.2.0 vendored MIT, npm integrity verified, licence in `providers/third-party/LICENSE.crypto-js`. No third-party scraper collection was installed/executed, only a public Nuvio manifest inspected; SDK tests use fixtures.

## Implemented in working tree

### Music and lyrics

- Offline romanisation: `shared/.../data/lyrics/OfflineRomanization.kt`, `HangulRomanization.kt`, platform `PlatformTransliteration.kt` actuals; `LyricsTranslation.kt` cache `romanize-offline-v1`. Android system ICU, desktop ICU4J76.1 only jvmMain. Preserves original/backing timings. Mixed Japanese kana/kanji avoids Mandarin readings; kanji-only language remains inherently ambiguous.
- `shared/.../audio/HeadphoneEq.kt`, `headphone-eq.json`:39 measurements, GraphicEQ/APO parametric imports, preview/explicit apply/attribution. **Seven-band approximations**, not exact parametric convolution. Shared `ListeningDeviceSheet.kt`; existing strict AutoEq validation retained.
- `LyricsMetrics.layoutHeightPx` and `CosmoLyricsView.kt`: reserves wrapped/scaled row height to avoid collisions while preserving existing word/syllable/backing/duet renderer. Excluded visualizations not added.
- Local crash notes `shared/.../diagnostics/CrashNotes.kt`, shared `CrashNoteDialog.kt`, Android/Desktop hooks and settings viewer/copy/delete. No automatic upload; omits exception messages/media URLs.

### Watch data and UI

- New profile/preferences/playlists/PIN models and API; legacy active progress/MyList retained and mirrored. Salted PBKDF2 PINs, create/select/delete, session unlock, profile-specific lists/playlists/preferences. Active local viewer does not switch another device's viewer.
- `SyncWatchLibrary.kt`, AndroidCloud/DesktopCloud: opt-in existing Firebase sync for progress/MyList/named playlists/preferences, portable metadata, deletion journals, quantized progress changes plus pause/exit checkpoints. No PIN/token/local file paths/playback URLs in cloud. `SyncWatchPlugins.kt` syncs pinned JS source descriptors, not settings/secrets.
- Shared SETTINGS tab, max760dp settings column (function in `WatchPluginsPage.kt`), profile gate, named Playlists route/CRUD/membership. Desktop sidebar wired; same phone screens.
- Watch density, type/genre/year filters on fetched results; daily/regional Top10, anime/soap/scripted-series rows; viewer-aware home cache/dedup. Regional language defaults still ta/hi rather than dynamic selection.
- Native validated YouTube trailers via existing Watch/Video player; excluded from Continue Watching.
- Optional OMDb attributed IMDb/RT/Metacritic; SubDL raw individual subtitles, not ZIPs; explicit per-season MAL mapping and duration-checked AniSkip skip intervals. Shared title/settings/player controls wired.

### Trakt/Simkl, scripts, casting and debrid

- `watch/.../tracking/WatchTrackingService.kt`: public-client device login; encrypted viewer-scoped credentials; provider mutex token refresh; watchlist reconciliation/deletion baselines; remote history/resume import; watched-history export; playback reports. Latest cancellable OkHttp helper/history/cache changes need tests. User/maintainer must register public client IDs; **no live account auth was exercised**.
- `providers/WatchScriptRuntime.kt`, `WatchDomBridge.kt`: isolated CommonJS getStreams on QuickJS/Graal, fetch/axios, bounded Jsoup Cheerio subset, CryptoJS, settings, string-only host interfaces.15s invocation/30s VM/size limits, Android64MB VM. Explicit Watch public-network grant still blocks HTTP/private DNS/IP; generic music permissions not made wildcard.
- `watch/.../plugins/nuvio/NuvioPackages.kt`, `NuvioScriptPlugin.kt`: Nuvio manifest browser, single-script install, SHA256 pin, verified lazy cloud restore. Browser/Node/WASM compatibility is partial. Changed script currently requires review/reinstall; managed update UI unfinished. Install URI validation should disallow query consistently with fetch/restore.
- `WatchCasting.kt`, shared `WatchCastSheet.kt`: DLNA video/HLS casting, receiver play/pause/seek, remote progress. Music renderer gains movie metadata class without changing its default. **No physical receiver test, Watch Google Cast integration not implemented.** Review async disposal/route changes: old close can clear a new remote flag; next/retry/channel actions must disconnect/hide appropriately.
- `WatchDebridClient.kt`, `WatchDebridCard.kt`: optional RD/AD/TorBox tokens, explicit selected-file resolution. Never choose a pack's largest/wrong episode. RD mock exists; AD/TorBox/live accounts not exercised. Do not blindly delete remote transfers potentially owned by the user.
- `WatchDownloads.kt`: space preflight + during-transfer reserve; selected future destination persisted in approved-roots config. Desktop system folder picker; Android internal/app-specific external Movies folders, not arbitrary SAF. Existing files stay in original destinations; filename/root validation. Only direct MP4/MKV/WebM downloads, no new HLS/torrent download claim.

## Newest audio edits — uncompiled, review first

Added **after the latest build**:

1. `shared/.../audio/AcousticAlignment.kt` and test:2s onset comparison at10ms, ±500ms reliable padding shifts only; bounded timestamped PCM bins. Null on flat/ambiguous/unmatched data. Envelope aggregation optimization did apply despite a later patch error; inspect actual files because failed multi-file patches can partially apply.
2. Android `playback/UpgradeAlignment.kt`: cached pinned old/new renditions only, existing `AudioDecoder.decodeRegion`, bounded cancellation/no additional downloads. `PlaybackService.swapIn` captures original cache key before forcing upgrade and applies measured padding to seek after audition. Verify actual-start units, coroutine cancellation, original/new cache keys and logical lyric/progress clock/revert behavior.
3. Desktop `DesktopAudioDecoder` optional PCM callback; `DesktopAudioEngine` passive per-track envelope, upgrade preroll3.5s to compare already decoded sound, brief wait then aligned seek. **Review timestamp accuracy after trim/resample, cancellation decoder cleanup, source/logical clock and cost.** `current` is still plain `private var current: Track?` though new off-audio-thread access reads its immutable meter metadata: use safe publication/snapshot. A proposed volatile patch never reached that hunk.

Do not ship a newly audible automatic path merely because it compiles. Add targeted fixtures and fix/remove unsafe behavior while retaining the requested alignment capability.

## Validation: precise state

Latest completed full run `integration-build-7.log`: **FAILED overall in5m08s**, `:watch:compileTestKotlinJvm`. New `WatchDownloadsTest` omitted `kotlin.test.assertFailsWith`; missing import plus cascading type-inference errors. **Import fixed after run, not rerun.**

- Provider tests173,0 failures,3 skipped in build7; both Watch JS runtime tests passed.
- Shared UI tests768,0 failures in build7.
- Android `:app:compileProdReleaseKotlin`, desktop `:desktopApp:compileKotlin`, actual native Compose preview tests completed successfully in that run.
- Watch XMLs show74 passing tests from an **older execution**, not build7 (test compile failed). New playlist/debrid/destination/history changes need rerun.
- Earlier shared romanisation/EQ/lyric metrics tests passed. **All newest acoustic code postdates those checks and is untested.**

Native Compose previews (no image generation): `build/old-screens/desktopui/watch-settings-phone-dark.png`, phone-light, desktop-dark, desktop-light, generated by `DesktopOrbitPanelsPreviewTest`. Earlier phone-dark/desktop-light visually checked, then settings width capped760dp and previews rerun in build7. Latest output not visually reopened. Fake repository tracking=null, so previews do not validate connected tracker-card layout. Existing lyric/queue previews also rendered.

### Resume build

Use existing JDK/init script, one build at a time. Save output to next numbered log. Android compileDebugKotlin is ambiguous; use production-release variant.

```powershell
$env:JAVA_HOME=(Resolve-Path 'build/tooling/jdk21/jdk-21.0.12.1+1').Path
.\gradlew.bat -I build/video-playback-review/limited-tests.init.gradle --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx2560m -XX:MaxMetaspaceSize=768m -XX:ActiveProcessorCount=4 -Dfile.encoding=UTF-8' --console=plain --continue :shared:jvmTest --tests '*OfflineRomanizationTest' --tests '*HeadphoneEqTest' --tests '*LyricsMetricsTest' --tests '*AcousticAlignmentTest' :providers:jvmTest :watch:jvmTest :sharedUi:jvmTest :app:compileProdReleaseKotlin :desktopApp:compileKotlin :desktopApp:test --tests '*DesktopOrbitPanelsPreviewTest' *> build/upstream-reviews/2026-11-11/integration-build-8.log
$buildResult=$LASTEXITCODE
Get-Content build/upstream-reviews/2026-11-11/integration-build-8.log -Tail 55
exit $buildResult
```

Gradle/cache access may need sandbox escalation. Existing missing CMake/Ninja warnings skip native Automix/window-helper builds; don't claim hardware validation. Existing junixsocket POM-as-zip and deprecation warnings did not prevent compilation.

## Still finish / assess

1. Validate and correct latest code before more expansion. Meaningful runtime/audio behavior tests, not implementation-mirroring assertions.
2. Guided Watch setup/first-use guidance; script repository update workflow; shared settings usability.
3. Album gapless policy: shared `TransitionPlanner` supports `albumSequential` but real Android `CrossfadeController` and desktop engine calls omit it. Android `.toTransitionInfo` omits album metadata. Use verified album origin/order plus shuffle/manual-queue guards, not naive same-album adjacency. DesktopApp has shuffle and next-track preparation around line1919. Audit existing offline music/queue/artwork rather than duplicate those already present.
4. Assess applicable relative-DASH/header retention/sequential multi-audio MKV repairs with regression fixtures. Android Media3 and desktop libmpv differ; don't transplant irrelevant workarounds.
5. Trakt/Simkl refresh/401/history deletion/offline event reconciliation and Simkl/AD/TorBox fixtures; real accounts/devices remain untested. Current public IDs must be registered, no universal working credentials included.
6. Casting lifecycle and route cleanup; decide required Google Cast parity before claiming universal casting support.
7. Final native UI review and documentation. README/user docs not updated for this batch. `docs/PENDING_WORK.md` has stale older release context; don't blanket mark unrelated work done. Update integration matrix with actual results, then obtain/obey user's release instruction.

Continue from these files. The user wants concrete working behavior and accurate status, not repeated research, confirmations or "all done" based only on compilation.
