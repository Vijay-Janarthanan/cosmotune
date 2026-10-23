# OpenCanvas discovery and framing implementation

Implemented upstream first in [OpenCanvas revision 9bcb678c4a4d0724c95eaf572124ae4cd3747514](https://github.com/Vijay-Janarthanan/OpenCanvas/commit/9bcb678c4a4d0724c95eaf572124ae4cd3747514), then imported into Cosmotune. These features are newer than the published Cosmotune 1.0.1 installers. The player layout is unchanged.

## Reported songs

- Manasavacha, Godavari, audio `1R-gKCrq_HE`: film metadata finds `F6dRgKSHdmk`, measured confidence 0.668. Singer-only search had returned still artwork and unrelated tracks.
- Tippulu Tappulu, Godavari, audio `ZDAT8DZi0jo`: bounded title-order fallback finds the publisher's `1p1PwJrxEPA`, confidence 0.496. Personal-channel dance `RRIYowOPF6s` is excluded independently of its matching audio.

## Engine and host responsibilities

OpenCanvas owns film/title search, verified artist/publisher evidence, motion checks, measured recording sync and selection-cache invalidation. Publisher evidence is not a guarantee of ownership. It also owns YuNet face/YOLOX-Nano person inference, continuity, cut/seek resets, stable wide/group fallback, timestamped cached trajectories and viewport geometry. New models total about 3.9 MB, with checksums and MIT/Apache licences included.

Cosmotune passes album metadata in lookup and Android prefetch, retains headers/trajectory metadata, and submits uncropped decoded video frames at actual presentation timestamps. Android samples the complete texture with its display transform restored immediately; desktop supplies its BGRA buffer. Inference runs on a bounded background worker, coalesces pending frames and adapts sampling cost. Both hosts close workers on disposal/viewport change and initially display the full frame. Song audio remains independent from the silent decorative video.

## Validation

- OpenCanvas: 166 JVM tests, six optional manual tests skipped, no failures; Android core/Compose and JVM Compose compilation; generated browser-policy conformance passed.
- Live matrix: 16 entries aligned. Its Topic lookup can fall back to a video reference, so this is transport/alignment smoke coverage rather than 16 independent album-master checks.
- Both reported film songs passed against their original audio IDs. Real desktop decoding produced positive face/person detections across 16 sampled frames. The 60-second edge actor is recovered from outside the center crop; groups use full-frame fit. Local diagnostic images are generated under `desktopApp/build/reports/canvas-framing/`.
- App integration: shared, desktop, UI and Android unit suites passed during implementation; Android arm64, armv7, x86_64 and universal debug APKs built successfully, with all model/resource hashes verified inside each archive. Physical Android texture/GPU behavior, battery/thermal cost and physical Linux playback remain unverified in this environment.

[Upstream validation and limits](https://github.com/Vijay-Janarthanan/OpenCanvas/blob/main/docs/VALIDATION_2026-10-23.md) · [Public interactive policy comparison](https://vijay-janarthanan.github.io/OpenCanvas/#smart-framing).

## Re-importing

`OPENCANVAS_IMPORT.json` records all source/resource/licence paths, the exact upstream commit and SHA-256 checksums. Run `python tools/verify-opencanvas-import.py` after import and before publishing. Kotlin/text hashes normalize Git's LF/CRLF conversion; ONNX model hashes are byte-exact. Discovery/camera changes belong upstream, followed by a refreshed manifest and host regression checks. Android resources are explicitly included in `shared/build.gradle.kts`; CI checks the hashes in every built APK with `--apk-dir`. The legacy UltraFace helper and old stream reframer are deliberately omitted because this host uses the new worker.
