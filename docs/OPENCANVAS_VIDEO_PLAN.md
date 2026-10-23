# OpenCanvas video discovery and framing

Investigation: 23 October 2026. These are OpenCanvas engine changes to implement in its own repository, then import into Cosmotune. No video discovery or detector experiments ship in Cosmotune 1.0.1.

## Confirmed problems

- Manasavacha, Godavari, audio ID `1R-gKCrq_HE`: singer-based search returned a still-picture upload and unrelated songs. Every candidate failed still-image or audio-sync checks. An album-query experiment found a measured match (`CPQTuwq-FP8`, confidence 0.66); this proves the query needs film metadata, not that publisher identity is verified.
- Tippulu Tappulu, Godavari, audio ID `ZDAT8DZi0jo`: the engine selected `RRIYowOPF6s` from personal channel "ch geetha", confidence 0.75. A fan dancing over the original recording correlates with the song. Audio correlation alone cannot establish the official video.
- `CanvasSession` currently emits `trajectory = null`. The engine has `DetectionBox`, `CropTrajectory`, `CropKinematics`, `SceneCutDetector` and `OneEuroFilter` types, but no working actor-detection pipeline producing the trajectory. Cosmotune also drops trajectory metadata, and `DesktopCanvasVideo` draws at a constant horizontal center. Both engine generation and host rendering need integration.

## Ownership and sequence

1. OpenCanvas: support album/film metadata, multiple bounded search queries and independent publisher/video identity evidence. Do not trust a title merely saying "official". Verified ownership is evidence to combine with recording/film metadata, not proof by itself. Keep still-image, alternate-version and measured sync checks. Invalidate previous unsuitable candidate selections when the discovery policy changes.
2. OpenCanvas: detect faces/people, maintain subject continuity within each shot, detect cuts, and produce a versioned crop trajectory in video presentation time. Preserve audio/video mapping separately.
3. Cosmotune: import the tested upstream revision, carry its trajectory through `CanvasArtwork` and the desktop adapter, and apply it to Android textures and desktop drawing at actual video PTS. Recompute viewport bounds for the actual container aspect ratio. Do not redesign the player.

## Proposed detector prototype

- Benchmark [YuNet](https://github.com/opencv/opencv_zoo/tree/main/models/face_detection_yunet) for faces and [YOLOX-Nano](https://github.com/Megvii-BaseDetection/YOLOX) for people. Use [ONNX Runtime](https://onnxruntime.ai/docs/tutorials/mobile/) for Android and desktop deployment. These are candidates, not a performance or quality guarantee; pin model versions/checksums and retain model licences after testing.
- Begin with a proposed 3–5 inference samples per second on downscaled frames, with immediate analysis after scene cuts/seeks. Track between detections and adapt the inference rate to measured device cost.
- Prefer persistent, confident faces/people with useful screen area; use hysteresis so small score changes do not switch subjects. Retain a selected subject briefly through occlusion. Camera position should remain stable during static shots, move smoothly within shots, and reset at actual cuts.
- Include headroom and, for dance/wide shots, the person's body. Keep a group visible when possible. When a portrait crop cannot contain the important subjects or confidence is low, fit the whole frame with a restrained background rather than cropping actors out.
- Cache trajectories by video ID, model/policy revision and relevant framing settings. Pause/seek/cut resets must not introduce drift or pan across unrelated shots. Analysis runs locally and must not block audio.

## Acceptance checks

Use both reported Godavari songs plus official artist videos, unverified fan dances, lyric/static uploads, fast film cuts, two actors at opposite edges, faces disappearing, dance full-body shots and landscape/no-person shots. Measure actor visibility, false video selection, crop jitter, seek correctness, time to first video, CPU, memory, battery and dropped frames. Verify real Android, Windows and Linux playback. Export short before/after comparisons for visual review before integrating the models.

The host-side investigation and live test output were preserved locally in `build/opencanvas-investigation/`; they are not release code.
