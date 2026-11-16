# Library/catalogue/playlist/download implementation — 16 November 2026

Target: next 1.0.24. Independent implementation of reviewed Lokal behavior against Cosmotune's existing models; no upstream implementation source was copied. No version/release changes or commits were made by this work.

## Reachable behavior

- Source catalogue requests reject a response carrying a different explicit artist/album/playlist ID. Search rows, structured artist credits, albums, follows/saves and saved pages retain their source-qualified IDs. Name-only YouTube and Apple lookups refuse multiple exact namesakes; desktop Replay no longer opens an arbitrary first artist hit.
- Android download metadata and native playlists now retain every structured artist ID and exact album ID. Desktop song/playlist preference codecs gain a compatible structured-credit tail field. Android rescans inherit catalogue IDs only from the record for that exact downloaded URI.
- Followed/saved source portraits appear immediately during network refresh. Sparse refreshes keep artwork only for the same exact source-qualified identity. Apple art memory/disk keys and page/search effects now include browse identity; failed/no-art refreshes preserve the prior portrait. Orbit artist discs retain the last decoded image while a replacement loads, and clear this retained image on identity changes.
- Device-file favorites work on Android without YouTube sign-in and survive restart, temporary missing files and MediaStore row reindexing. An absolute lexical path is the identity when MediaStore supplies one; otherwise the granted URI remains the identity. No title/artist alias migration is guessed. Notification/widget favorite actions share this local persistence. Desktop already persisted path-based favorites; local rating requests now stop before the YouTube call. Local/content/file IDs do not travel through cloud likes.
- Desktop normal watcher events update changed audio files only. Directory changes, watcher overflow, manual scans and setting changes use a full bounded snapshot. A durable size/mtime/file-key metadata index avoids re-reading unchanged tags on startup/full scans. Existing startup and watch scans already run on Dispatchers.IO; this retains that behavior. Android continues using MediaStore's index rather than adding another filesystem watcher or database.
- Portable M3U imports compare the longest unique path tail within the granted library, independent of slash style. Filename-only matching requires title/artist evidence; conflicting labels and ambiguous editions stay unresolved. Remote/content references remain exact-only and imported filesystem paths are never opened. Duplicate occurrences retain order.
- Import reviews are atomically saved in the existing ListeningStore before navigation can discard them. Unresolved rows retain their original metadata/location/position and can be resumed after files are added. Resuming validates saved track paths against the current granted snapshot. Saving a partial native playlist retains the full included review; resuming and saving again deliberately creates a new reviewed playlist (the native create callback does not return a mutable playlist ID). Catalogue lookups remain explicit; M3U references never silently search for a replacement.
- The shared Orbit provider-quality picker is wired to both single-song download actions, including Android's existing storage/notification permission continuation. It lists the selected package manifest's exact options plus existing generic download settings. Exact provider+quality choices are persisted through jobs, retry, runtime invocation and distinct filenames. Repeated in-flight requests for the same choice are suppressed; different explicit choices can coexist. Manager progress/cancel/retry address the exact job while catalogue IDs remain unchanged. Removed manifest choices fail visibly rather than falling back. Generic per-job overrides and existing bulk behavior remain supported.

## Regression coverage added

- `MusicPlaylistImportTest`: Windows/POSIX relocation, longest tails, filename evidence, ambiguity/live-edition refusal, no network/content probing, pending restart/order and untrusted saved-path admission.
- `LocalFileRatingsTest`: reindex identity, restart, clear, namesake-file separation, cloud exclusion and damaged-file preservation.
- `ProviderCatalogueTest` / `SourceLibraryStoreTest`: exact-ID mismatch, translated labels, structured links, separate namesake follows and identity-only artwork continuity.
- `MusicDownloadJournalTest` / desktop `DesktopDownloadQueueTest` / Android `DownloadSessionTest`: independent quality jobs, duplicate taps, late completion, exact-key cancellation/retry and preserved catalogue IDs.
- `ProviderPreparedStreamTest`: the selected manifest ID actually reaches the provider SDK, with playback-override and removed-option rejection.
- `DesktopLocalMusicIndexTest`: process-reopened metadata cache and changed-file tag invalidation.
- `AppleArtistArtTest`: ambiguous namesakes, identity-scoped cache and retained stale portrait after failed refresh.
- Android `DownloadStoreTest`: backward-compatible exact album/all-artist metadata serialization.
- UI interaction fixtures cover exact manifest choice/default/cancel and durable unresolved import rows after partial saving.

## Deferred verification

No tests, builds or Gradle tasks were run during implementation, as instructed. After all agents finish, run these commands sequentially from the repository root (then the root agent's broader gates):

```powershell
.\gradlew.bat :shared:jvmTest --tests "com.cosmotune.listening.MusicPlaylistImportTest" --tests "com.cosmotune.music.data.LocalFileRatingsTest" --tests "com.cosmotune.music.data.downloads.MusicDownloadJournalTest" --tests "com.cosmotune.listening.ListeningStoreTest"
.\gradlew.bat :providers:jvmTest --tests "com.cosmotune.providers.ProviderCatalogueTest" --tests "com.cosmotune.providers.SourceLibraryStoreTest" --tests "com.cosmotune.providers.ProviderPreparedStreamTest"
.\gradlew.bat :sharedUi:jvmTest --tests "com.cosmotune.music.data.canvas.AppleArtistArtTest" --tests "com.cosmotune.music.data.canvas.ArtistArtDiskCacheTest"
.\gradlew.bat :desktopApp:test --tests "com.cosmotune.music.desktop.DesktopDownloadQueueTest" --tests "com.cosmotune.music.desktop.DesktopLocalMusicIndexTest" --tests "com.cosmotune.desktop.DesktopLocalMetadataTest" --tests "com.cosmotune.music.desktop.DesktopSourceCatalogueTest"
.\gradlew.bat :app:testProdDebugUnitTest --tests "com.cosmotune.music.DownloadStoreTest" --tests "com.cosmotune.music.DownloadSessionTest" --tests "com.cosmotune.music.WidgetAccountGatingTest"
.\gradlew.bat :uiTests:test --tests "com.cosmotune.ui.MusicPlaylistImportInteractionTest" --tests "com.cosmotune.ui.ProviderDownloadQualityInteractionTest" --tests "com.cosmotune.ui.SourceLibraryInteractionTest"
```

Provider accounts/network availability, real permission dialogs, physical Android MediaStore reindexing and desktop filesystem watcher delivery still require platform exercise. The provider picker applies to installed package manifests declaring qualityOptions; HTTP add-on source settings retain their existing protocol/settings behavior. No new cross-provider identity mapping or arbitrary path access is inferred.
