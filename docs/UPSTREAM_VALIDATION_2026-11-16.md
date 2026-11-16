# 1.0.24 integration validation

The implementation owners finished before their focused regression gates. The coordinating gate runs one Gradle invocation at a time. No model or stream-quality claim is inferred from a source comparison or compilation.

## Completed checks

- Native/browser version identities agree on 1.0.24; OpenCanvas and TV/browser source-import provenance verifiers pass.
- Tracker checker fixtures pass; 67 Implemented rows resolve to existing test evidence, with zero missing references. Two removed historical fixture names were corrected.
- Signing publication: four local fixture tests pass, including bounded/contiguous multidex, host-contract rejection, normalized archive hashes and preserving a previous publication after failure. Ephemeral fixture keys remain local; the app's real private key is held only in the approved encrypted Actions secret.
- Shared focused gate: 106 tests, zero failures/skips, covering audio/rendition policy, matching, local identity, playlist persistence, player memory, publisher metadata and actual loopback DLNA races.
- Providers focused gate: 22 tests, zero failures/skips.
- Watch focused gate: 29 tests, zero failures/skips.
- Manga focused gate: 19 tests, zero failures/skips, including actual JVM fixture compilation/loading/restart and signature/replay/rollback rejection.
- Shared UI focused gate: 86 tests, zero failures, including artist identity, lyrics/duets/RTL, Podio first-follow freshness and rendered player options.
- Desktop focused gate: 57 tests, zero failures, one macOS-only native smoke skipped on Windows. Provider quality/import/source-library Compose interactions: five tests, zero failures/skips.
- Player-option portrait and short-landscape renders were inspected in both palette families. The fixture now provides the real Material color bridge; the Bluetooth label was shortened to avoid truncation. The dialog keeps Done reachable while its body scrolls.
- Windows native production SMTC backend compiles with the actual MinGW/JDK headers. Its real WinRT local-artwork smoke passes with a Unicode/space-containing local file and rejects network-share misuse.

## Release gate in progress

Android's focused gate passed 198 tests. The full Windows-host gate executed 7,564 tests across eleven modules: shared 3,145; shared UI 902; Video 868; Watch 114; Manga 127; providers 178; remote 18; desktop 685; UI interactions 215; Android phone 1,282; Android TV 30. Platform/live opt-ins account for 52 skips.

Four initial failures were isolated and repaired: the parser publisher now follows the repository's manual-only workflow policy; release highlights follow the five-item/short-sentence contract; the artwork fixture expects the last decoded portrait and additionally checks identity changes; the Video preview harness isolates scene generations and completes cancellation/finally work before retirement. Release guards and artwork rechecks pass. Two new preview-lifetime regressions pass; all five callers of that harness are being re-rendered together. Image/nonblank assertions and playback assertions were retained.

Signed parser publication and the multi-platform Release workflow must finish before this record claims a published release. A failed gate does not justify changing the release version.

Local logs are under ignored `build/upstream-reviews/2026-11-16/`. Per-area commands and scope are linked from [the review](UPSTREAM_REVIEW_2026-11-16.md).

## Physical/device limits

Automated fixtures do not certify audible quality or transitions, actual USB/Bluetooth/DAC formats, receiver silence, Chromecast/DLNA hardware, Android Auto rendering or downloaded DEX execution on API 26/34+ devices. macOS compilation/export checks run on the macOS CI targets; actual Control Center/headset behavior needs a Mac session. Publisher/provider availability remains an external condition. Watch batches support direct-file downloads and process-reopened queues, without promising OS background execution or adaptive/torrent downloads.
