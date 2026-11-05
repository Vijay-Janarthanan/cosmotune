# Device sync and playlist download updates

These features share the native **Your listening** workspace on desktop and Android. Both default to no sharing and no automatic playlist downloads.

## Device sync

1. Open **Device sync** and select local playlists to publish. Including audio files is a separate choice.
2. Enable paired-device sharing, then start **Phone remote** on a LAN interface. Open its private pairing link on the other device's Device sync page.
3. Preview the incoming and outgoing journal changes, then synchronize. Pairing credentials remain in memory; stopping the remote rotates the key on its next start.
4. Review the shared catalogue and choose **Apply shared playlists to this library**. Playlist removals require their own opt-in review. Files are retained.

Previously published playlists follow complete native snapshots: renames, track order and removals become journal changes. Unselected playlists never enter the shared catalogue. Retained tombstones stop stale pages reviving removed playlists; deterministic Lamport stamps resolve concurrent edits without depending on synchronized wall clocks. Incremental cursors and acknowledgements avoid resending the full journal after convergence.

Files use content hashes and private copies. Transfers resume in 32 KB chunks and become native playable paths only after full SHA-256 verification. The receiver never opens a sender-provided URI or filesystem path. Transfers are bounded to 2 GB per file, 8 GB of private sync storage and a 64 MB free-space reserve; timeouts and cancellation retain resumable parts. Explicit local-copy removal can reclaim space; it leaves source files and catalogue metadata unchanged.

The server reuses the existing explicitly started LAN pairing endpoint, bearer authentication, host/origin checks and bounded request handling. The client accepts literal local IPv4 addresses, rejects public addresses and redirects, and never follows a remote file URL. This remains a local HTTP transport rather than a cloud or TLS service. Do not share pairing links beyond intended devices on a trusted local network.

Native imports update stable local playlist IDs rather than creating another copy every exchange. A local-only recording requires its transferred, verified file before that playlist can be applied. Streamed recordings still need their provider configured on the receiving device; provider accounts and credentials are not synchronized.

## Playlist download updates

Link a playlist in **Linked playlists**, then enable **Download future additions** in **Playlist downloads**. Existing baseline IDs are remembered, so a track manually deleted from the device is not silently downloaded again. Stopping and later re-enabling updates retains that ID history. Online removals never delete local files. Full continuation loading must finish successfully; repeated tokens, failed pages and local storage limits produce an error rather than a shorter successful sync.

Up to 50 additions can be queued automatically. Larger batches remain in persistent review until approved. Native queue journals are flushed before foreground requests are considered handled. Android background requests also drain through the worker and only advance the baseline when their missing files arrived successfully; failures retain their IDs for retry.

Desktop checks run about every 12 hours while the application is open. Android uses a unique persisted WorkManager periodic job with download-network, storage and battery constraints; it can run after the activity/process closes. It schedules no periodic job until a playlist is opted in. A foreground notification exposes cancellation during background transfers. OS force-stop and battery restrictions can delay jobs until Android permits execution; this is not an exact alarm.

The Android phone/tablet workspace exposes the opt-in controls. The TV target reuses the same application and worker sources, so it also includes WorkManager and the data-sync foreground-service declaration; this does not add a TV-specific opt-in workspace. With no enabled watch it schedules no job.

The Android runtime uses [WorkManager 2.12.0](https://developer.android.com/jetpack/androidx/releases/work#2.12.0), compatible with the application's API 26 minimum. Foreground work declares the data-sync service type.

## Verification

New fixtures cover real paired client/server exchanges in both directions, incremental paging, conflict convergence, durable tombstones, interrupted/checksummed transfers, disabled/wrong pairing, stale previews, file/aggregate/free-space limits, complete playlist pagination, removal guards, large-batch review and UI publication/approval controls. These are automated fixtures; physical two-device/background walkthroughs remain separate validation.
