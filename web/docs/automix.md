# Browser Automix

Enable **Settings → Playback → Crossfade → Automix**, or use the Up Next menu. It is off by default. The existing extension and playback permissions still apply. This feature belongs to the separate browser companion; it does not add a browser runtime or these downloads to Android, Windows, macOS or Linux installers.

The player analyses the playing and next songs in a worker, keeps their tempo, key, energy, beat grids and vocal estimates in IndexedDB, then plans a phrase-aligned blend. Compatible pairs use decoded instrumental loops, pitch-preserving time stretching and a gradual tempo ramp. Other pairs use a filter mix or crossfade. Pause, seek, Next and queue changes cancel the transition. The lyrics, video, scrubber and stats follow the active mix clock. A song that cannot be analysed in time continues playing normally and crossfades at its end.

Analysis fetches an additional compressed copy of each of the two songs using the same permitted browser resolver. Only a few recent compressed copies are kept in memory. This uses extra data and memory; analysis time depends on the device and recording. Failed or unavailable analysis must not prevent ordinary playback.

## Optional download and storage

First use downloads **32,193,022 bytes (about 32 MB)** of analysis weights. The settings explanation discloses this. Both files come from an immutable upstream revision, use credential-free requests, and must pass exact size and SHA-256 checks before inference. Verified bytes are reused in the browser's CacheStorage when storage is available. Clearing site data removes the cache; private browsing or insufficient quota can require another download. Failed integrity checks never install changed bytes.

The browser release archive does **not** contain these weights. Its lazy analysis worker is about **2.63 MB**, and the matching ONNX WASM runtime is about **14.24 MB** uncompressed; these load when analysis is used. Other lazy mix/audio JS totals roughly 0.3 MB. These figures describe this build's uncompressed output, not the initial page transfer. The separate on-device recommendation model remains approximately **3.19 MB**; it is unrelated to audio transition analysis.

Exact model hashes, byte counts and licences are in [the model manifest](../../licenses/automix-web/models.json). Source changes and original hashes are in [the delta import manifest](../UPSTREAM_DELTAS.json); its source records resolve in root [FORK.md](../../FORK.md). Original notices are preserved under [licenses/automix-web](../../licenses/automix-web/). The Settings explanation links the modified engine's public source and licence.

## Validation and limits

On 2026-10-28, both TypeScript configurations, a frozen workspace install, 166 browser regression tests and the production build passed. An isolated Chrome test generated its own 124 BPM loop: the analysis worker reported **123.982 BPM / A minor**, the real stretch AudioWorklet emitted PCM and advanced its playhead, and the optional beat/vocal models ran successfully. The built production worker also performed both inferences and returned **124.119 BPM**, a beat grid and a vocal curve. No account sessions or music downloads were used for these checks. Failed model sessions can retry after a cooldown instead of disabling analysis until page reload.

The synthetic checks establish worker/model/worklet operation. They do not establish the musical quality of every transition, signed-in provider playback, device-specific output latency or continuous video alignment through every loop. Those require listening and device testing. Upstream's subjective or timing measurements are not presented as Cosmotune measurements.

Pinned exact dependency versions: essentia.js 0.1.3, idb 8.0.4, onnxruntime-web 1.30.0 and signalsmith-stretch 1.3.2. The optional protobufjs postinstall script is disabled explicitly; frozen installation succeeds without running it.
