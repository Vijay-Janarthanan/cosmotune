# Cosmotune 1.0.3

- Add a 3.19 MB on-device recommendation ranker for AutoPlay/radio across Android and desktop. The 4.18 MB mapped model incorporates the expanded ListenBrainz, Music4All-Onion and Yambda work with catalogue/acoustic context; the complete training catalogue stays outside the app. Provider radio/search supplies current playable songs. [Design, measurements and limitations](LOCAL_RECOMMENDATIONS.md).
- Remove foreign-platform ONNX/SQLite binaries and macOS debug symbols from desktop runtime jars. Windows dependency payload drops by about 58.9 MiB before installer compression. [Size audit](PACKAGE_SIZE.md).
- Prefer CoreAudio with fallback for macOS Video-mode sound instead of forcing AVFoundation. Add a portable native stereo-decoding/output-fallback regression. Physical macOS/Linux audio-device checks remain pending.
- Restore beat-model compatibility with Intel Mac's ONNX Runtime 1.23.2 by rebasing four convolution weight tensors to unsigned bytes with the corresponding zero-point shift. Centered integer weights are unchanged. Native smoke checks now run inference and validate both output heads, and Intel Mac model-load failures are no longer waived.
- Prevent automatic lossy mid-song upgrades based solely on comparing bitrates across different codecs. Saved effect settings were checked during the reported quality investigation; a perceptual source/mastering difference remains to be confirmed by listening comparison.
- Fix ambiguous title-track video discovery through the published OpenCanvas update, add consistent settings insets and wrapping resolution choices, and use compact settings slider controls.
- Remove the visible upstream branding footer while retaining legal notices and dataset attribution.

Local release regression suite: 3,862 tests, zero failures/errors, 10 skipped. Real target-native smoke checks load the bundled beat model through ONNX and execute SQLite SQL. The Windows local ranking benchmark measured 1.80 ms median / 2.35 ms p95 for discovery plus 50 scores; physical mobile latency remains unmeasured.

The monthly model-update schedule is deferred. No full dataset, training histories, Python runtime or additional inference engine is bundled for recommendations.
