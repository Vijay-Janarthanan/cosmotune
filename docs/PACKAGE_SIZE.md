# Package size audit — 1.0.3

Published 1.0.2 downloads measured from GitHub release metadata:

| Package | Download size |
| --- | ---: |
| Android ARM64 | 62.2 MiB |
| Android ARMv7 | 53.6 MiB |
| Android x86-64 | 68.1 MiB |
| Android universal | 155.3 MiB |
| Windows installer / portable | 356–360 MiB |
| Linux deb / AppImage / rpm | 350–386 MiB |
| macOS ARM64 | 359–366 MiB |
| macOS Intel | 388–396 MiB |

The universal APK contains three native CPU architectures. Most phones need ARM64; an ARM64 APK already avoids the other two copies. The release already enables R8 optimisation/shrinking and publishes separate ABI APKs.

The ARM64 APK's ZIP directory was inspected with only 90 KiB of range requests. Compressed components total approximately: native libraries 30.9 MiB, DEX code 10.3 MiB, fonts/resources 8.1 MiB, assets 5.3 MiB, canvas models 3.4 MiB, resource table 2.6 MiB, and remaining metadata/resources. Its largest file is **libonnxruntime.so: 27.3 MiB compressed**. FFmpeg is 2.0 MiB. The recommendation model is new in 1.0.3 and was not responsible for 1.0.2's size.

Desktop bundles include a Java runtime, Compose/Skia, JavaFX WebKit for browser-token flows, GraalJS for source modules, FFmpeg for music decoding, libmpv for Video mode, Tor, SQLite and ONNX for existing analysis/framing models. FFmpeg and libmpv serve different current engines; removing one without replacing its callers breaks functionality.

## Implemented reduction

The desktop ONNX and SQLite jars previously carried many foreign-platform native libraries. Packaging now keeps Java APIs, service metadata and licences, while retaining only the package's target native folders. macOS debug symbol bundles are omitted.

Measured Windows jars:

| Library | Previous jar | Target-only jar |
| --- | ---: | ---: |
| ONNX Runtime | 51.88 MiB | 5.85 MiB |
| SQLite JDBC | 13.60 MiB | 0.76 MiB |

This removes about **58.9 MiB** from the Windows dependency payload before installer compression. Final download measurements are below. The same filtering applies to Linux and both Mac architectures, with their own native folder checks. Tests initialise ONNX with the bundled beat model, run both prediction heads and execute SQLite SQL against the filtered jars.

The new recommendation ranker is **3.19 MB compressed / 4.18 MB mapped**, uses no additional inference runtime, and has a hard 5 MB limit.

## Published download measurements

Measured from [1.0.3 release metadata](https://github.com/Vijay-Janarthanan/cosmotune/releases/tag/v1.0.3), compared with 1.0.2. Values use MiB (1,048,576 bytes), rounded to one decimal. Changes are calculated from unrounded byte counts.

| Package | 1.0.2 | 1.0.3 | Change |
| --- | ---: | ---: | ---: |
| Android ARM64 | 62.2 | 65.2 | +3.0 |
| Android ARMv7 | 53.6 | 56.7 | +3.0 |
| Android x86-64 | 68.1 | 71.2 | +3.0 |
| Android universal | 155.3 | 158.3 | +3.0 |
| Windows setup | 359.6 | 304.1 | −55.5 |
| Windows MSI | 359.0 | 303.4 | −55.5 |
| Windows portable | 356.2 | 300.7 | −55.5 |
| Linux deb | 349.9 | 299.6 | −50.2 |
| Linux AppImage | 379.5 | 329.1 | −50.4 |
| Linux rpm | 385.6 | 335.3 | −50.3 |
| macOS ARM64 DMG | 366.1 | 315.3 | −50.9 |
| macOS ARM64 PKG | 358.6 | 307.9 | −50.7 |
| macOS ARM64 ZIP | 358.8 | 308.1 | −50.7 |
| macOS Intel DMG | 396.4 | 324.4 | −72.0 |
| macOS Intel PKG | 388.1 | 316.8 | −71.4 |
| macOS Intel ZIP | 388.1 | 316.7 | −71.4 |

Android grows by about 3 MiB for the bundled recommendation model. Desktop downloads shrink by about 50–72 MiB while including that model. All 32 release assets were verified uploaded; each stable alias matches its versioned file's size and GitHub SHA-256 digest. [All platform builds and checks passed](https://github.com/Vijay-Janarthanan/cosmotune/actions/runs/39966017660), including both macOS startup checks. Physical audio-device listening checks remain separate from build and decoding validation.

## Further opportunities

- The full Material extended icon jar is 36 MiB on desktop. Removing unused icons could reduce it, but requires reliable reachability filtering or validated desktop shrinking; it is not removed blindly.
- Android's full ONNX runtime dominates native size. A custom reduced-operator runtime and validated conversion of every shipped ONNX model could reduce it. Simply replacing the dependency with the mobile runtime is incompatible with the current model-loading path.
- Five SF Pro font weights contribute roughly 7.5 MiB compressed in the ARM64 APK. A validated replacement/subset can reduce this, but must retain every supported script and text weight.
- GraalJS and JavaFX WebKit add substantial desktop weight, but currently serve different source-module/token flows. Replacing either requires functional migration and tests.

Installer formats (.exe/.msi/.zip, .dmg/.pkg/.zip, .deb/.rpm/.AppImage) are alternatives. Users download one appropriate format; the release page offering several does not mean each installation contains all formats. Local old installers can be removed after their release availability is verified.
