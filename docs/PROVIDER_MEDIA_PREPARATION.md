# Provider audio preparation

Version 1.0.7 source. Provider verification, file delivery, native preparation and actual decoding are separate steps.

## Why Lokal could play a file that Cosmotune refused

The Amazon package returns an encrypted MOV/MP4 audio file and a provider-supplied `ffmpeg.mov_key` descriptor. Lokal processes that descriptor with FFmpeg before handing the result to its player. Cosmotune previously rejected the descriptor as `native_processing_required`; feeding the unprocessed file to its decoder instead produced corrupt FLAC packets.

The host now decrypts and remuxes that file through its bundled native libraries, with audio stream copy. This preserves the encoded audio without resampling, volume processing or lossy re-encoding. Both the SDK file-only route and an encrypted direct URL can enter preparation. The native processor requires decoded samples before publishing an output; a recognizable container header alone is insufficient.

Desktop uses the JavaCPP FFmpeg libraries already included in the app, shared by Windows, macOS and Linux. Android and TV use the existing `libffmpegJNI.so`, extended with a small set of local demuxers/muxers and MOV decryption. All three shipped Android ABIs were rebuilt from the existing pinned librempeg source. No FFmpeg executable is required or launched by the provider host.

## SDK compatibility and boundaries

- `ffmpeg.getInfo` and `gobackend.getAudioQuality` inspect the actual local audio, including codec, duration, sample rate, depth, channels and bitrate.
- `ffmpeg.convert` supports stream copy into the allowed audio containers. Arbitrary commands, encoders and conversion options remain unavailable.
- The disposable playback workspace has no permanent download ISRC index; `checkISRCExists` correctly returns no existing library download. Optional embedded-lyrics lookup returns an empty result; the app's separate lyrics pipeline remains responsible for playback lyrics.
- `ffmpeg.mov_key` and its upstream aliases accept a provider-supplied 16-byte hex key. An empty output-extension field defaults to FLAC, as the real Amazon FLAC package requires. Unknown strategies, custom IVs and other output containers are rejected.
- Explicit FLAC container conversion uses stream copy. Other requested codec conversions require additional implementation.
- Paths stay inside the provider workspace. Output is staged privately, bounded by the remaining workspace quota and 512 MiB file limit, and published only after successful preparation. Cancellation, invalid input and failure delete staging output. Disabling a provider revokes the playback lease and deletes prepared media.
- A package `download` call has a bounded 120-second budget on both script runtimes. Metadata retains its 30-second script budget. Signed `POST .../dl/...` preparation requests may wait up to 60 seconds for a response; ordinary metadata keeps the existing 15-second read/20-second call limits. A guest cannot request arbitrary longer timeouts.

The complete-file route waits for delivery and preparation before playback. It is not progressive playback while downloading. HLS/DASH manifests and immersive output-device routing are separate work.

## Evidence

On 2026-10-26, the isolated, user-verified Amazon package resolved **Blinding Lights — The Weeknd** and passed bounded native playback decoding as **FLAC, 44,100 Hz, 24-bit**. The redacted local report is `build/operator-verification-amazon/result.json`. This establishes that recording and quality on Windows, not every Amazon quality or Atmos output.

`DesktopProviderMediaToolsTest` uses original one-second generated tones and their CENC-encrypted AAC/24-bit FLAC copies. It compares decoded PCM against the clear recordings and checks wrong keys, cancellation and quotas. `ProviderMediaProcessingTest` checks descriptor/path validation, default-extension compatibility, atomic publication, staging cleanup and the permission-scoped asynchronous SDK calls. `ProviderMediaToolsTest` exercises the shipped Android JNI with the same fixtures and compares prepared AAC/FLAC packets against the clear sources.

Final Android emulator instrumentation passed **both AAC and 24-bit FLAC tests**, including clear-packet equality, decoded-sample validation, cancellation, a wrong key and a quota refusal (`build/provider-media-android-test.log`, `OK (2 tests)`). The compact Android build explicitly uses its MP4 muxer for `.m4a`; it does not depend on the separate iPod muxer. The x86_64 build now includes its required assembly routines, and every ABI is linked with undefined-symbol rejection. Both new provider JNI methods are exported on all three ABIs, with no unresolved FFmpeg/librempeg symbols.

The affected broad regression passed with no failures: desktop (471 reported tests, five skipped, including the live diagnostic checks), Android unit tests (1,180 reported, one skipped), shared UI (662), and TV compilation in `build/provider-native-final-regression.log`. The final follow-up passed the provider suite (128 reported, three skipped) and all three current desktop native-media fixtures in `build/provider-native-final-package.log`; Android packaging passed again after the `.m4a` fix in `build/provider-native-fixed-package.log`. These are local checks, not publication. macOS/Linux native execution and physical Android/provider walkthroughs remain outstanding.

Qobuz's separate verified package initially timed out during signed download preparation before any response from `/v2/dl/qbz`; search, track lookup and ticket creation returned HTTP 200. With the corrected deadline, its download endpoint returned **HTTP 503 twice**, and its retries exhausted the bounded package deadline. [Independent upstream code checks](UPSTREAM_QOBUZ_DOWNLOAD_COMPARISON.md) also failed the same package/session/song: Lokal stalled three times for thirty seconds, and Mornye's published native engine timed out after 120 seconds. This remains unsuccessful package playback. The separately tested Qobuz HTTP add-on is a different route.

Sources: [Lokal's preparation pipeline](https://github.com/lokal-music/lokal/blob/dc43d85db7711279f58d969107883084965b2005/electron/mornye/packages.js), [Lokal's SDK media operations](https://github.com/lokal-music/lokal/blob/dc43d85db7711279f58d969107883084965b2005/electron/mornye/host.js), [FFmpeg MOV decryption documentation](https://ffmpeg.org/ffmpeg-formats.html#mov_002fmp4_002f3gp).
