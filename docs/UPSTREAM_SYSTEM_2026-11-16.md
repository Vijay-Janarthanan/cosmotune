# System media, casting and Podio — 16 November 2026

## macOS system media

The original Cosmotune Objective-C bridge in `desktopApp/native/macos/media.m` uses Apple's public MediaPlayer APIs. It publishes title/artist/album, duration, elapsed position, playback rate and artwork to Now Playing, and handles play, pause, toggle, next, previous, seek and Stop (mapped to pause). Kotlin dispatches native commands to the desktop event queue and retains its JNA callback for the native handler lifetime.

Artwork work runs separately from metadata updates. Session epochs and artwork generations reject stale callbacks/images; late artwork preserves the current advancing playhead. Empty playback clears Now Playing and disables commands. Stop removes native handlers and clears system metadata. The macOS build compiles and packages `libcosmotune-media.dylib` with Foundation/AppKit/MediaPlayer, deployment minimum macOS 11; other platforms do not load it. Existing desktop playback ownership selects the system playback state, including Podio chapter artwork.

This is independent public-API implementation, not copied LastWave Desktop source. `DesktopMacMediaTest` checks command routing/invalid seeks everywhere and native resource exports on macOS. It does not establish that Control Center, headset/media keys, app switching or all Apple Silicon/Intel packaging variants work on physical Macs.

## Chromecast and DLNA handover

`CastHandover` restores only a known local queue occurrence, keeps the local position for unknown receiver media and permits autoplay only when the listener chose this device, the receiver was playing and Stop was acknowledged. A sender session ending is not proof that remote playback stopped. `CastPlayback.prepareDisconnect` snapshots receiver state, freezes outstanding resolver/load/reconciliation work, then awaits bounded receiver Stop acknowledgement. Unconfirmed Stop returns locally paused with an explicit controller error.

Controller callbacks retain session/owner identity, invalidate pending disconnects when routes or owners change and verify the SDK's actual current session before ending it. Resolver/load completions verify client and generation. Queue reconciliation rechecks client, generation, current queue index and upcoming identities after suspension, so late work cannot amend another session or an obsolete queue window. Cancellation propagates through stream resolution; late/duplicate request callbacks cannot resume completed handovers.

Shared DLNA ownership serializes connection/load/disconnection. Switching receivers requires the old renderer to acknowledge Stop; failed/cancelled candidate connections release their polling. A queue follower captures renderer identity and request epoch before awaiting the platform's next-song callback, and checks ownership/cancellation again before loading. Sleep-timer pause requests similarly retain ownership. Cancelled loads attempt receiver Stop, keeping local playback paused when remote Stop cannot be confirmed. Renderer transport epochs discard stale poll answers across load/Stop/close; explicit Stop clears natural-finish detection so it cannot advance the queue.

Existing Watch casting lifecycle is retained. Cast acknowledgement proves the receiver answered the command; it does not measure acoustic silence, DAC timing, receiver codec quality or gapless behavior. Qualify receiver-to-receiver switching, repeated taps, disconnect while resolving, Wi-Fi loss, failed Stop, app/service restart and duplicate queue occurrences with physical Chromecast and multiple DLNA implementations. A receiver that is unreachable may continue playing independently.

## Podio publisher extras and chapter artwork

Podcast feed parsing retains publisher chapter JSON and supported SRT/WebVTT transcript links without fetching them during feed parsing. Episode details expose chapter/transcript sheets with retry, speaker labels and seek actions. Transcript parsing preserves additional colons and URLs in spoken text, supports WebVTT voice tags and omits speaker-only blank cues. Chapter timestamps are validated, ordered and deduplicated; unsafe image schemes are refused.

`PodioChapterMedia` is owned by the application/service and follows existing position events. It caches chapter metadata and bounded image results, switches artwork when the chapter changes, reuses it on seek and rejects late images from a previous episode. Missing/failed chapter images restore the episode cover. Song/episode metadata retains chapter URLs through playback and offline item conversion. Android checks image dimensions before exposing chapter artwork/title through session metadata (notifications/Android Auto); desktop feeds the selected system artwork into its native media integrations. Network pictures and metadata are bounded; no audio-source replacement or new polling loop is introduced by the chapter owner.

Windows chapter artwork uses a local stream in both native backends. `RandomAccessStreamReference.CreateFromUri` supports the shell's remote/app URI schemes but does not open `file:` URLs; local Podio artwork now passes through Windows' file-URL decoder and read-only `CreateRandomAccessStreamOnFile`, then `CreateFromStream`. HTTP/HTTPS retains the existing URI path. The shared native helper loads documented system DLL APIs from System32 so MinGW does not require additional SDK headers/import libraries; it rejects UNC/device/relative paths and malformed local URLs. Missing/unreadable artwork clears the thumbnail. See Microsoft's [file-stream API](https://learn.microsoft.com/en-us/windows/win32/api/shcore/nf-shcore-createrandomaccessstreamonfile) and [stream reference API](https://learn.microsoft.com/en-us/uwp/api/windows.storage.streams.randomaccessstreamreference.createfromstream).

The optional Windows native `cosmotune_smtc_artwork_test` smoke target creates a local fixture with spaces and a Unicode filename, decodes its file URI, checks the real WinRT stream size and creates a stream reference; it also rejects remote-share/file misuse. Enable it with `-DCOSMOTUNE_SMTC_ARTWORK_TEST=ON` when configuring the existing desktop native CMake build, then build that target and run its executable on Windows. This does not itself prove that the Windows flyout/lock screen has rendered a chapter image.

## Focused validation

The coordinating owner runs validation only after implementation owners freeze, one Gradle invocation at a time. The final Cast static audit added a loopback fake-renderer regression for a cancellation-ignoring old next-song callback and a request-callback regression for late/duplicate acknowledgements. The audit agent did not execute tests or builds.

```powershell
.\gradlew.bat :shared:jvmTest --tests "com.cosmotune.music.data.cast.dlna.DlnaTest" --tests "com.cosmotune.podio.PodcastExtrasTest" --tests "com.cosmotune.podio.PodioChapterMediaTest" --tests "com.cosmotune.podio.PodioCatalogueTest"
.\gradlew.bat :app:testProdDebugUnitTest --tests "com.cosmotune.music.playback.cast.CastHandoverTest" --tests "com.cosmotune.music.playback.cast.CastAwaitResultTest"
.\gradlew.bat :desktopApp:test --tests "com.cosmotune.music.desktop.DesktopMacMediaTest"
```

The macOS native smoke branch runs only on macOS. Existing Android device, physical receiver, Android Auto/notification and desktop media surface checks remain required. These commands are intended checks, not recorded passing results; the coordinating release record must contain actual outcomes.
