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

## 1.0.4 Manga/provider packaging checkpoint

This is a development checkpoint, before the final feature inventory and later M3U8 work. With compatible R8 metadata handling and unsupported 32-bit x86 phone libraries removed, APK sizes are ARM64 78,547,071 bytes, ARMv7 68,568,201, x86_64 85,234,486 and universal 145,906,186. The universal APK is 38,683,128 bytes smaller than the immediately preceding 184,589,314-byte checkpoint; the three supported phone ABIs remain complete. The independent watch APK still supports its own ABIs and is 4,486,712 bytes.

The recommendation model is unchanged at 3,186,092 packed bytes / 4,176,439 mapped bytes; adding the 65,568-byte private learner totals 4,242,007 bytes. Android's new Brotli/Zstandard APIs serve extension HTTP compatibility, separately from the recommendation model. Per-ABI Zstandard native files are about 0.57–0.86 MB uncompressed, and notices add 13,271 bytes before compression. Source APKs, comics and raw training data are not bundled. Final package measurements must be refreshed after all tracker features and release checks are complete.

The later library/persistence checkpoint (before remote integration) assembled and passed package/certificate/model/companion/notices checks at ARM64 78,563,455 bytes, ARMv7 68,584,585, x86_64 85,250,870 and universal 145,922,570. All four grew by 16,384 bytes against the preceding checkpoint; model and watch bytes are unchanged. The x86_64 minified app also passed a real emulator source/reader/mode-switch walkthrough. These remain development artifacts pending the entire tracker and final release gates.

## 1.0.4 Full/Lite decision (MN-11)

Measured 2026-10-24 in worktree cx-d1, with the grouping of `tools/inspect-release-size.py` applied to local files (no download). Android: the entries of the locally assembled `app-dev-arm64-v8a-debug.apk` (113,793,721 bytes; debug, so its DEX is unminified and not representative; native libraries and assets are packed exactly as in release). Desktop: the resolved Windows `:desktopApp` runtime classpath (jar bytes, before installer compression and without the Java runtime).

**Android ARM64, compressed bytes in the APK, against the 78.5 MB minified ARM64 checkpoint above:**

| Optional component | Bytes | Share of 78.5 MB |
| --- | ---: | ---: |
| ONNX analysis: `libonnxruntime.so` 28,637,280 + JNI 83,224, beat model 1,953,754, vocal model 3,527,032, canvas framing models (YOLOX 3,386,880, YuNet 202,127) | 37,790,297 | 48% |
| Wear companion APK in `assets/wear` (4,486,904 unpacked) | 2,461,257 | 3% |
| Providers/source-module runtime: `libquickjs.so` (also used by Manga JSON/JS sources) | 787,936 | 1% |
| Manga: Kotatsu parsers 9,396,722 + Rhino 1,271,206 + module 839,586 JVM bytes before R8 | not separable in DEX; under 11.5 MB of class bytes | — |
| TV companion | 0 (separate package) | — |
| Browser companion (`web/`) | 0 (separate package) | — |

Not optional: FFmpeg decoder 2,106,008 (formats the phone cannot decode), DSD/DST/analysis natives 852,424, the recommendation model 3,186,092 (no runtime of its own).

**Desktop (Windows), runtime jar bytes, total about 301 MiB:**

| Optional component | Bytes |
| --- | ---: |
| GraalJS (js-language, ICU4J, Truffle, regex, polyglot and SDK jars): source modules, providers, Manga JS sources | about 63.4 MB |
| JavaFX WebKit (web, graphics, controls, media, base): browser sign-in/token flows | about 45.9 MB |
| libmpv runtime 22,127,953 + `video` module 2,911,305: Video mode | about 25.0 MB |
| Manga: Kotatsu parsers, Rhino, module | about 11.5 MB |
| ONNX runtime (target-only) 6,135,108 + framing models in `shared` about 3.6 MB | about 9.7 MB |

Not optional: FFmpeg 26.3 MB, Compose/Skia, the Material icon jar (37.8 MB; a shrinking candidate, not a feature).

**Decision: no Full/Lite flavour for 1.0.4.** The only large optional piece on Android is the ONNX analysis set (about 37.8 MB, nearly half the ARM64 APK); everything else optional together is under 4 MB of natives/assets plus Manga's share of the DEX. A Lite flavour would double the phone matrix (four ABI packages become eight, each through signing, package/companion/model verification, release aliases and the in-app update asset choice), roughly double the Android release job time, and need runtime gating plus tests for beat-matched transitions, vocal analysis and canvas framing being absent. On desktop a "Lite" without GraalJS, JavaFX, libmpv and Manga would save on the order of 145 MB of jars but remove sign-in, providers, Video and Manga, which makes it a different product, and would add another 16 installers. The cheaper reductions help every user and need no new variant: a reduced-operator ONNX runtime built for the shipped models (the Android natives are 28.6 MB of the 37.8), removing unused Material icons on desktop, and the existing local-build switch `-Pcosmotune.bundleWear=false` for anyone building without the watch. Re-measure the minified release packages at the final 1.0.4 gate.

