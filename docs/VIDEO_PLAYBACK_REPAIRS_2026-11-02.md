# Video controls, restore and smoothing repair evidence

Working-tree changes, 2 November 2026. This is not a new published release.

## Controls after leaving the player

The desktop shell previously drew its bottom chrome only in compact layouts. Leaving Watch on a wide desktop therefore left the engine playing without the bottom controls. The phone's video capsule also depended on Music's morph/hidden state and had no stop action.

`VideoMiniPlayer` now owns Video play/pause, reopen and close/stop controls. Both desktop widths and Android use it, with measured bottom content reservation and at least 48 dp transport buttons. Reopening navigates to the current player without starting a new load. The solid glass surface no longer requires Music's host/settings observer.

`VideoMiniPlayerInteractionTest` passes both real Compose interaction cases: Back → Pause → Reopen at the same 42-second position → Play → Close, and a phone-width player with Music's capsule hidden and no Music host installed. These are fake-engine UI fixtures, not phone hardware tests. Local render: `build/video-playback-review/video-mini-phone.png`.

## Repeated downloads on restore

The native probe uses the app's actual mpv renderer, frame exchange, EDL audio/video join and loopback proxy, with locally generated 24 FPS media and null audio output. It disables/re-enables the video track and removes/restores the render surface, reproducing the minimize/restore media path without user sessions.

Before repair, playback position advanced from 4003 to 5384 ms, but upstream range requests increased from 6 to 10. Three already-fetched video ranges (about 5 MB) were fetched twice. This run proved repeated reads, not a playback restart.

The proxy now retains only completed, validated byte spans in a 32 MiB session-wide LRU. Contained range reads reuse those bytes. Cache hits do not inflate network bandwidth stats. New loads/close invalidate cached data and cancel old session reads; new-session requests are protected from old-session cancellation. Audio-only requests are idempotent.

After repair, the native restore probe advanced from 4006 to 5293 ms, upstream requests remained **6 → 6**, repeated ranges were empty and decoder dropped frames were zero. The test now asserts timeline preservation and zero repeated exact upstream ranges. Cache size is bounded: seeking outside retained data still needs the network. This probe does not reproduce a complete physical desktop window or test Android lifecycle behaviour.

Fixtures cover cache ownership/size/subranges/reset, interrupted reads, bandwidth accounting and reset cancellation. Native artifacts live in ignored `build/video-playback-review`; normal CI may skip the optional native probes when local media/libmpv is absent.

## Smoothing

Two concrete defects were corrected on desktop and Android:

- Late motion estimation could keep showing the older frame. It now falls back to advancing blended intermediate pictures.
- The output FPS meter discarded long gaps and counted held redraws. The new output cadence meter includes gaps and tracks changing source-pair/phase submissions. It is separate from source-rate estimation.

Raster fixtures verify changed pixels during delayed analysis, changing 24 → 60 scheduled output, and an honest 48 FPS readout when every fifth presentation is deliberately omitted. These controlled-clock fixtures pass; they are not performance benchmarks.

The additional real wall-clock probe decodes a 640×360 24 FPS clip through mpv and the app's Smooth pipeline and reads native raster pixels for four seconds. Its first successful run produced **210 changing outputs from 211 submissions**, source readout **24 FPS**, rolling output readout **56 FPS**, draw+readback p50 **1.24 ms** / p95 **17.27 ms**, **39 gaps over 25 ms**, maximum gap **63.73 ms**, and zero decoder drops. This demonstrates actual changing intermediate frames and remaining scheduling gaps. It does **not** establish sustained 60 FPS, physical display scanout, high-resolution performance or S24 performance. The report now also records average submission FPS separately from the rolling readout.

Hardware/window lifecycle checks and broader native frame-pacing measurements remain necessary before a smooth-60 claim. Headphone/device-profile and discovery improvements are a separate [proposal for review](HEADPHONES_AND_DISCOVERY_PLAN_2026-11-02.md).
