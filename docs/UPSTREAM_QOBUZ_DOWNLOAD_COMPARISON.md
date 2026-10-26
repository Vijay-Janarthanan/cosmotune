# Qobuz download comparison — 2026-10-26

The question was whether the same recording downloads through Lokal/Mornye code when Cosmotune cannot obtain it. These are bounded backend comparisons, not claims about every account, recording or provider.

All attempts used the reviewed **qobuz-web 1.2.23** package, **Blinding Lights — The Weeknd**, **HI_RES_LOSSLESS**, and the same existing user-verified operator session. No challenge was solved, skipped or bypassed by the diagnostic harness. Session material crossed private process pipes and the isolated app-private test profile; it is not included in reports or source control.

| Host | Code executed | Result |
| --- | --- | --- |
| Cosmotune | Production package host and signed-session path | Search/track/ticket requests returned HTTP 200. With the longer bounded response deadline, `/v2/dl/qbz` returned HTTP 503 twice; retries exhausted the package budget. No playable stream was established. |
| Lokal | Unmodified runtime, QuickJS worker, host, network, signing session, bootstrap and crypto at `dc43d85db7711279f58d969107883084965b2005` | Authenticated session; 20 search results. The download endpoint stalled for 30 seconds on each of three requests, then the package returned `runtime_error`/download failure. No successful audio download. |
| Mornye | Published **5.2.6 ARM64 Android native Rust/QuickJS extension engine**, called through its own `ExtensionManager` bindings in an isolated emulator | Package installation/initialization and search succeeded with 20 results. Its native download call returned `timeout` after **120,059 ms**, with no successful download returned. That test did not capture an HTTP status, so it does not prove Mornye received the same 503 response. |

Lokal's copied source was compared against the exact Git objects above, normalizing checkout line endings; all seven executed implementation files matched. Its host used the normal SDK argument contract and existing FFmpeg/ffprobe tools, not Cosmotune's SDK implementation.

Mornye's official APK SHA256 matched its published checksum: `e42094f409ba688d10bbb112523aed1ee929d1edc2f5e8eabc068dfa87fe770b`. The lab APK changed manifest debug/native-extraction flags and was signed with a disposable test key so instrumentation could call the backend in its private profile. **All twenty native/Dart libraries were byte-identical to the official APK.** The test executed the native package manager, not a reimplementation of Mornye's network or signing code. It did not exercise the complete Flutter playback UI or certify physical Android playback.

These results are consistent with failure on this Qobuz extension/operator route during the test window. They do not establish a universal Qobuz outage or that another extension, credential, region or time produces the same result. The separately tested Qobuz HTTP add-on is a different integration. Amazon's verified FLAC decoding result is also a separate route.

Redacted local evidence is under ignored `build/provider-upstream-comparison/`: `source-check.json`, `qobuz-web-result.json`, `mornye-binary-check.json` and `mornye-download.txt`. The diagnostic app-private session files and test apps were removed after the comparison.

Sources: [Lokal runtime and host](https://github.com/lokal-music/lokal/tree/dc43d85db7711279f58d969107883084965b2005/electron/mornye), [Mornye 5.2.6 release](https://github.com/mornyeapp/Mornye/releases/tag/v5.2.6), [provider preparation and playback evidence](PROVIDER_MEDIA_PREPARATION.md).
