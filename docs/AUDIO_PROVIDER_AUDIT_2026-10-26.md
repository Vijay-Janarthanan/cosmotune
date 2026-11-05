# Audio details and provider parity audit

Reviewed 2026-10-26 against Cosmotune `7b58b0462bc4335b28ee2f785270365af3e87f7f` (v1.0.6). This is a source audit, not a playback or device certification. No app implementation was changed during this audit.

## Scope and upstream revisions

The earlier reviews used Mornye `fb4945be910b0a7a8cce25eab3d1b3d80b3541e4` and Lokal `3199796ef90743a5abbac6ffc96495beec855a4e`. Their current public HEADs were fetched into the existing audit copies without changing those checkouts:

- Mornye: `c90ec6478ad3e808ef22ba9464f8ad05054dde7b`.
- Lokal (now `lokal-music/lokal`): `dc43d85db7711279f58d969107883084965b2005`.
- Mornye-Extension: still `6b544663fb5b7a7dfbe1959f3c0edab017378f53`.

The findings below distinguish existing implementation, missing interaction, incomplete compatibility, and upstream changes since the previous review. They are not a claim of exhaustive current upstream parity.

## What is integrated

- HTTP JSON add-on search/stream, settings, priority, album/artist browsing and manifest refresh.
- Shared Android/desktop Mornye/SpotiFLAC package management: import/repository review, hashes, permissions, settings, disable/remove and update review.
- Package catalogue search, album/artist/playlist browsing, cached regional home shelves and source-qualified native playback.
- Direct stream relay and prepared file-only playback; private files, bounded transforms/segmented transfers, authentication/cookies and signed operator sessions.
- Decoder-input format display and native detailed audio-pipeline dialogs. Download/file inspection distinguishes delivered audio from a provider's lossless claim.
- Local preference learning, Last.fm candidate discovery, library quality analysis/upgrade preview, download recovery/pacing and library-management features. See the individual evidence and limitations in FEATURE_TRACKER.md.

This does not establish working Qobuz/Amazon accounts or full package compatibility. The previous live public provider proof was JioSaavn metadata plus AAC decoding. Controlled runtime/transport fixtures do not replace real authenticated-provider playback checks.

## Confirmed gaps and defects

| Area | Evidence and user-visible consequence | Required completion |
| --- | --- | --- |
| Tap audio stats | `sharedUi/.../nowplaying/AudioFormatLine.kt` renders a `Text` with no callback or clickable modifier; portrait, wide and dock call it without one. | Wire tap, keyboard and accessibility activation to the existing native audio details panel in every new player layout. |
| Detail discoverability | `CosmoNowPlaying.kt` only sets `flags.pipeline` from the output sheet; `AudioOutputSheet.kt` already has an Audio pipeline row. | Keep that route and add the direct stats interaction. The detailed panel exists; it is not wholly missing. |
| Inconsistent detail coverage | Android's panel exposes substantially more DSP/route information than desktop's. Desktop reports source/decoder/resampler/EQ/skip-silence/bit-exact verdict/output, but not Android's full loudness, transport and Bluetooth detail. | Use a common details inventory, while marking platform-unavailable measurements explicitly. |
| Metadata provenance | Android's pipeline source stage substitutes `claimed.bitDepth`/`claimed.sampleRateHz` when measurements are absent without labelling those rows as declarations. `desktopNerdSnapshot` does not populate its available `claimed` field. | Present offered, received and output facts separately, retain provider claims where available, and explain unknown values and mismatches. |
| Atmos identification | `NerdStats.isDolbyAtmosMime` accepts plain `audio/eac3`. Add-on/source heuristics also accept broad Dolby/EAC3/EC3 strings. | Require positive JOC/Atmos evidence for an Atmos claim; otherwise display Dolby Digital Plus/E-AC-3 or an explicitly unverified provider offer. |
| Atmos output | `DesktopCodecs.supportsDolbyAtmos` only tests `canDecode("eac3")`. The ordinary desktop engine requests stereo PCM and its decoder resamples into that layout. Android capability code permits the E-AC-3 core fallback and explicitly excludes route-based passthrough from that decision. | Separate stream identity, ability to play its base audio, active output route and verified immersive rendering. Decoding support alone must not imply end-to-end Atmos. |
| Provider compatibility | Manifest-aware HLS/DASH package relaying and SDK FFmpeg operations remain unavailable, as recorded in PROVIDER_PACKAGES.md. HTTP add-on manifest playback is a separate path. | Complete and validate those package APIs; test each real provider's account, expiry, quality, seeking and recovery. |
| Remote playback | Package relay leases listen on loopback; another Cast receiver/watch cannot consume those URLs as-is. | Explicit authenticated device-output handoff and lifecycle verification. |
| Runtime/device evidence | Desktop guest execution has no per-context heap quota; physical Android, both macOS architectures and Linux real-account/output verification remain incomplete. | Record runtime safeguards and actual platform/account evidence separately from compilation and fixture tests. |
| Tracking clarity | PROVIDER_PACKAGES.md still opens with “1.0.4 (in development)” even though later sections describe released follow-up work. Broad Implemented tracker rows contain outstanding account/device checks. | Refresh the documentation and distinguish implemented, interaction-verified, provider-verified and device-verified status. |

## Comparison with the upstream detail interaction

Mornye's `lib/widgets/player_playback_controls.dart` attaches an `onTap` to `showAudioInfoSheet`. Its sheet includes source format/codec/rate/depth/channels/bitrate, available duration/file size/location, loudness tags and processing/output facts. Lokal's `src/components/PlayerBar.jsx` uses a button to open `AudioPipelineModal` with source, decoder, resampling, DSP and output stages.

Cosmotune already implements much of that pipeline information, but its new compact line omitted the interaction. An implementation check for a format label was insufficient: the acceptance check should have included tap/keyboard activation and correct live contents after a track/source change.

## Newly changed upstream work requiring a separate comparison

Lokal's fetched revision adds or expands:

- A Windows native headphone playback mode through WinRT MediaPlayer/Media Foundation alongside its separate mpv HDMI E-AC-3 passthrough mode. Its own documentation distinguishes confirmed compressed HDMI output from unverified object rendering on headphones.
- Positive FFprobe JOC-profile checks for local Atmos files and separate ordinary stereo playback quality selection.
- Progressive provider media/startup and buffering progress, provider-ID fast paths, broader catalogue/search/lyrics result handling, CoverFlow and home artwork changes, Deezer playlist synchronization and phone file naming/migration fixes.

These changes were not covered by the pinned earlier review. This audit inspected the delta and selected audio implementations; it does not certify parity or propose copying every new feature unchanged. Mornye's newer changes include preserving verification-required state after signed-session challenge/bootstrap failures and Windows runtime/FFmpeg launch packaging fixes.

## Recommended order

1. Restore direct audio-details interaction in all new player layouts and add one meaningful interaction check per layout family.
2. Make source offers, decoder measurements and actual output clear; correct E-AC-3 versus JOC identification before adding more quality badges.
3. Validate authenticated providers end to end, including package APIs they actually need and source fallback reporting.
4. Design platform-specific Atmos routes with truthful status and hardware checks; the stereo widening effect is not an Atmos renderer.
5. Finish the new upstream delta review and update the central tracker with evidence-based statuses.

## References

- [Cosmotune feature tracker](../FEATURE_TRACKER.md), [provider implementation/evidence](PROVIDER_PACKAGES.md), [remaining work](PENDING_WORK.md).
- [Mornye audio controls](https://github.com/mornyeapp/Mornye/blob/c90ec6478ad3e808ef22ba9464f8ad05054dde7b/lib/widgets/player_playback_controls.dart).
- [Lokal Windows Atmos design and limitations](https://github.com/lokal-music/lokal/blob/dc43d85db7711279f58d969107883084965b2005/docs/dolby-atmos-windows.md).
- [Dolby: JOC decoding versus the backward-compatible base audio](https://dolby.my.site.com/professionalsupport/s/article/What-is-Dolby-Digital-Plus-JOC-Joint-Object-Coding).
- [Android: spatialization depends on content and the current output route](https://developer.android.com/media/grow/spatial-audio).
