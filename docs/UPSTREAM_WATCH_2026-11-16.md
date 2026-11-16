# Watch additions reviewed 16 November 2026

Implemented public behavior independently from the Euthopiar 1.2 review; no Euthopiar source was copied (no upstream license). Existing DLNA ownership/lifecycle code remains unchanged.

## Delivered

- Series → Download episodes opens an Orbit dialog for current season, all regular seasons, unwatched regular episodes or a custom selection in the current season; quality is best available or a known resolution up to 480/720/1080/2160p. Specials are selected from their season page.
- `WatchDownloads.batches` owns the serial queue in the host's process scope. Android/desktop bind it to their shared repository when creating Downloads. `episode-jobs.json` persists stable episode targets, profile, destination, quality, release hint and status. Opening the app restores queued/running jobs; deliberately paused jobs stay paused. Progress/queue controls remain reachable in Downloads after leaving the title/player. Profile switches cancel the active worker and only unlocked active-profile queues run.
- Batch pause cancels and joins the actual range writer, preserving partial bytes. Resume/retry re-resolves sources, keeps the same selected release and retries failed episodes without repeating finished episodes. The existing direct-file downloader supplies space checks and folder approval. Completed files and queued keys are skipped; colon-containing add-on series IDs retain their episode identity; explicit filenames for a different episode or multi-episode release are refused.
- Downloads persist only a one-way source fingerprint. If a renewed address/headers differ, its partial file is discarded before restarting that release; combining bytes from potentially different files is unsafe without server validators. Identical addresses resume with real HTTP ranges. Filenames now incorporate the full target identity to avoid collisions between same-named titles/releases.
- Add-on network URL normalization repairs missing HTTP slashes, encodes spaces, accepts protocol-relative URLs, merges inline request headers with explicit headers taking precedence, and refuses custom/file schemes, credentials and control characters. Unsafe/hop-by-hop headers are dropped. Stremio manifests retain configured query strings on resource requests; duplicate resource declarations all participate in matching. Series inference requires episode coordinates, including suffix coordinates in video IDs; trailers alone do not infer a series. Native video IDs are selected by exact season/episode.
- Short landscape players keep the picture beside independently scrolling controls/details; larger tablets show player and details in separate columns. Player source/cast/download/together controls wrap instead of overflowing. Watch dialogs expand to a bounded tablet width, use narrower phone gutters and scroll within available height. Orbit styling and PiP/casting lifecycle remain intact.
- Sync retains Firebase/journal architecture, exact resume snapshots, safe portable artwork and the existing five-minute progress trigger coalescing. A computed completion flag in the trigger now emits credits/completion changes inside the same position bucket; it does not change the cloud record schema. No Supabase dependency/copy was added.
- No Watch-specific blur preference was added: Watch backgrounds use the existing lightweight wash, and shared glass already follows the app's Reduce dynamic blur policy.

## Verification to run after all integration work is complete

No tests or builds were run during implementation, per the requested sequencing. Run these commands one at a time from the repository root after the other integration owners are finished:

```powershell
.\gradlew.bat :watch:jvmTest --tests "com.cosmotune.watch.WatchBatchDownloadsTest" --tests "com.cosmotune.watch.WatchAddonNormalizationTest" --tests "com.cosmotune.watch.WatchDownloadsTest" --tests "com.cosmotune.watch.PluginClientsTest" --tests "com.cosmotune.watch.SyncWatchLibraryTest" --tests "com.cosmotune.watch.WatchCastingTest"
.\gradlew.bat :sharedUi:jvmTest --tests "com.cosmotune.ui.WatchPagesContractTest" --tests "com.cosmotune.ui.watch.WatchPipAspectTest"
```

New regressions cover persisted paused selection/restart, fresh source resolution, duplicate episode exclusion, unwatched selection across seasons, custom season boundaries, HLS rejection, insufficient storage before requests, failed retries, real range pause/resume with byte comparison, wrong-episode/multi-episode release rejection, quality bounds, configured source query preservation, safe inline headers, duplicate manifest resources, series inference/native episode lookup and immediate completion sync signals.

## Limits and device review

HLS/DASH/live/torrent/YouTube downloads remain unsupported in Watch; these jobs require direct MP4/MKV/WebM offers. Jobs run while Cosmotune is running; this does not add an Android foreground service or promise transfers while the OS suspends/kills the process. Restart resumes when the host constructs its application-scoped Downloads repository. Provider episode mappings remain the authority when filenames contain no explicit episode coordinates. Network/file errors remain visible for retry; invalid or unavailable configured destinations fail rather than silently relocating files.

Phone portrait, short phone landscape, split-screen/tablet, desktop resize, accessibility/font scaling, real authenticated add-ons and physical player/cast/PiP behavior need manual device review before release. No visual/device validation or live provider calls were performed here.
