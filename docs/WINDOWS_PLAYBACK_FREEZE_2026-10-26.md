# Windows playback freeze investigation

On 2026-10-26, the installed **1.0.6** app stopped responding after Play. Two live JVM thread snapshots, about thirty seconds apart, showed the AWT event thread waiting in `DesktopWindowsAudio.nativeDeviceName` through `DesktopPlaybackEngine.pipeline`, while the playback thread remained in `nativeWrite`. Heap use was approximately 95 MiB of a 304 MiB committed heap; this was not an exhausted Java heap. The user restarted the app after the diagnostics were captured.

The native source had an unbounded recovery path: after `AUDCLNT_E_DEVICE_INVALIDATED`, rerouting could keep the same invalid client because the Windows endpoint ID had not changed. The write then retried while holding the audio mutex, which the UI's device-name query also needed. A paused/stalled endpoint could also leave write/drain waiting indefinitely. The exact driver action that invalidated the captured client was not independently injected or recorded.

The fix recreates an invalidated client even for the same endpoint, bounds consecutive invalidation retries and no-progress waits, and lets paused write/drain calls finish. The UI reads a cached device label refreshed by the playback thread. Both output pickers enumerate devices on an IO worker, and the Java Sound label is also captured when opening the output rather than queried by the UI.

Validation: the rebuilt Windows DLL passed a silent real-WASAPI check covering queued paused drain, a paused write, a resumed one-second write and an independent UI metadata read. The desktop suite passed **474 reported tests, eight skipped, no failures/errors**; UI interactions passed **61 tests**. This includes the opt-in WASAPI check and the existing decoder/speed/playback regressions. The live diagnostic data and isolated test profile remain under ignored `build/live-hang-investigation/` and contain no release assets.

Restarting 1.0.6 is temporary recovery; installing 1.0.7 is required for this source fix. Actual same-endpoint driver invalidation, unplug/replug and route changes remain useful physical-device follow-up checks.
