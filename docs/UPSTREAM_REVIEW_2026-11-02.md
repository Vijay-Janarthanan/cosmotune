# Upstream review — 2 November 2026

Current Cosmotune baseline: published **1.0.13**, at tag commit `1d9b55a0`. This review compares all 20 previously tracked repositories against their exact 28 October heads. Ten changed and ten did not. Default-branch work and latest published releases are recorded separately in the [machine-readable snapshot](UPSTREAM_SNAPSHOT_2026-11-02.json).

All compare commit pages were retrieved and indexed. Source-level inspection focused on the matching, playback, provider, parser and regression areas listed below. Full recursive Git trees were compared for truncated 300-file results; no reviewed tree was truncated. Commit counts include merges, translations, build work and repeated fixes, not distinct features. This is a source review and selected integration, not execution of every upstream app or a claim of complete parity.

The separately imported [OpenCanvas library](https://github.com/Vijay-Janarthanan/OpenCanvas/tree/1d5c7b0cb6d5a16b1e8d2904256aa6eef56862e2) was also checked: its current head exactly matches our pinned import, with no newer commits. Its 42-file import verification passes; this pass does not change framing or video synchronization.

## Repository inventory

| Repository / pinned source | Current head | Published release | Delta since last review |
| --- | --- | --- | --- |
| [akhilrex/podgrab](https://github.com/akhilrex/podgrab/tree/032248091294dbf5b6a439a5afd93788a7cc647f) | `0322480912` | No published release | Unchanged |
| [AntennaPod/AntennaPod](https://github.com/AntennaPod/AntennaPod/tree/e10f5f6f340a8221778e62adaffde809de2f735b) | `e10f5f6f34` | 3.12.3 | 6 commits / 25 paths |
| [AsHfIEXE/Dotify](https://github.com/AsHfIEXE/Dotify/tree/ba64f4d7853910fd60b0f1644f2df30ed45982a5) | `ba64f4d785` | v3.0.6 | Unchanged |
| [audaro/audimo](https://github.com/audaro/audimo/tree/076d7d778af8f9039e7a30def136399199a478a3) | `076d7d778a` | v0.4.1-rc8 | Unchanged |
| [Clash-Projects/LastWave-Desktop](https://github.com/Clash-Projects/LastWave-Desktop/tree/5c58c9d7c05784c21ee21966b98719a7daf4b2f1) | `5c58c9d7c0` | v1.5.0 | 46 commits / 121 paths |
| [Clash-Projects/LastWave-Native](https://github.com/Clash-Projects/LastWave-Native/tree/4c11bc2b349877605e54423fe92e3410f6c1b1f7) | `4c11bc2b34` | 5.0.0 | 159 commits / 313 paths |
| [Doctorstra/p-fm-bot](https://github.com/Doctorstra/p-fm-bot/tree/b8cd828c4684c9a071ebda6241f70c044b5f37a3) | `b8cd828c46` | No published release | Unchanged |
| [glomatico/votify](https://github.com/glomatico/votify/tree/db14e4d1aa01456e0ec3b55d6f7268787ba2e2c4) | `db14e4d1aa` | 1.9.9 | Unchanged |
| [gpodder/gpodder](https://github.com/gpodder/gpodder/tree/825593f7cabd9922c424775149d3851724b1d4d1) | `825593f7ca` | 3.11.5 | 5 commits / 3 paths |
| [Kotatsu-Redo/kotatsu-parsers-redo](https://github.com/Kotatsu-Redo/kotatsu-parsers-redo/tree/68f3fed3952f5c9cf1ad8cc00bdb1ff047688dd8) | `68f3fed395` | No published release | 52 commits / 44 paths |
| [kushagrasinghx/BitChord](https://github.com/kushagrasinghx/BitChord/tree/b690dc146009d4815825fa4a20815ef2e4ee8898) | `b690dc1460` | v1.9 | 182 commits / 358 paths |
| [lokal-music/lokal](https://github.com/lokal-music/lokal/tree/0cfaab06626cf6e00dbc345e870075e3a7f40b08) | `0cfaab0662` | v12.7.2 | 573 commits / 169 paths |
| [lokal-music/lokal-mobile](https://github.com/lokal-music/lokal-mobile/tree/e615b11581881bbc8fe3521ac0a0bc5e7b69ca16) | `e615b11581` | v5.1.3 | 1153 commits / 320 paths |
| [mihonapp/mihon](https://github.com/mihonapp/mihon/tree/e588e04359fe1058206a2cbac1b01018ad787359) | `e588e04359` | v0.21.3 | Unchanged |
| [mornyeapp/Mornye](https://github.com/mornyeapp/Mornye/tree/7db0fc76f6cfeaef903327ebc73e13d9bdc3d936) | `7db0fc76f6` | v5.3.0 | 458 commits / 1722 paths |
| [mornyeapp/Mornye-Extension](https://github.com/mornyeapp/Mornye-Extension/tree/a1c99f8b33bb12c913efda985f577e264be54dda) | `a1c99f8b33` | No published release | 20 commits / 52 paths |
| [thurstonsand/anypod](https://github.com/thurstonsand/anypod/tree/370a0de0667bf931c3464f8b91201388274a4e25) | `370a0de066` | No published release | Unchanged |
| [timdorr/spotifeed](https://github.com/timdorr/spotifeed/tree/7ad607bc41369af7981023de03f606f96f95cecc) | `7ad607bc41` | No published release | Unchanged |
| [TsukiAndroid/Tsuki](https://github.com/TsukiAndroid/Tsuki/tree/549ef6e13d47ad4d7fcdd66ad06cee51a037c34c) | `549ef6e13d` | stable-latest | Unchanged |
| [xiaoleiy/podpull](https://github.com/xiaoleiy/podpull/tree/7ba067437df29f5e6cfdd59afcff8b4e6816f753) | `7ba067437d` | No published release | Unchanged |

## Changes integrated in this pass

- **Recording matching:** native and browser matching accept whole bracketed CJK/Latin translations in either direction, including a one-character CJK title. The alias also becomes a bounded search query, so a Latin-filed recording can actually be discovered. Film labels, same-script asides, partial translations, different performers and incompatible editions do not acquire a translation exemption. Native video-to-audio switching and JioSaavn use the same relation. Existing native Indic vowel signs and protected artist identities are preserved; browser normalization now preserves combining marks too.
- **YouTube locale routing:** Simplified Chinese retains its language while using the supported Singapore music region rather than an unsupported mainland-China region value. Traditional Chinese and other existing mappings are retained.
- **Guest credits and type labels:** Portuguese `part.` / `part esp.` credits are treated as guest packaging, while titles such as Part of Me and Part. 2 survive. Generic catalogue labels such as Música are not treated as performer names.
- **Private upload ownership:** explicit privately-owned catalogue markers provide session-local InnerTubeX hints. Ownership and remembered stream URLs clear on account changes; stale resolves cannot publish into a newer session. The media probe remains required. The upstream option that plays a refused upload URL without validation was not adopted. Fixture coverage is not a live private-library playback claim.
- **Per-track audio modes:** provider metadata understands scalar/array `audio_modes`, `audioModes` and track-scoped attribute/metadata shapes used by the updated Amazon package. Album-wide modes, neighbouring tracks, AC-4 alone, nested arbitrary arrays and negative labels do not create Atmos availability. Availability is not measured Atmos decoding or immersive output.
- **Manga parser refresh:** the compatible parser artifact is pinned to `68f3fed3952f5c9cf1ad8cc00bdb1ff047688dd8`. It adds sources and updates domains/parsers; the adapter retains explicit source selection and its existing verification/JavaScript limits. Broken-source filtering stays active. A pinned parser update does not establish that every live site works.
- **Windows upgrades:** found locally during baseline reconciliation: a fixed installer counter left versions 1.0.12/1.0.13 with installer version 1.0.11. Installer metadata now follows the public numeric version so subsequent MSI upgrades advance.

These source changes require a new app build. No published release tag, installed provider package, listener account, model or scheduled dataset job was changed by this pass.

## Features and fixes reviewed by project

| Project | Relevant new work | Current Cosmotune decision |
| --- | --- | --- |
| BitChord | Cross-script/contributor matching, authenticated upload hints, ALAC/24-bit routing, direct USB/hardware volume and recovery, downloaded-playlist synchronization, richer home picks/motion covers, podcasts and Automix changes. | Adapted matching and ownership hints with stricter recording/session guards. Native desktop, OpenCanvas, the tiny ranker and existing protected credits are retained. Direct USB, automatic playlist refresh and additional presentation features remain separate integrations. |
| LastWave Native | Playback validation/cipher recovery, extraction coordination, original-language/dub selection, first-hit addon resolution, hybrid CENC preparation, USB recovery and expanded moods/genres. | Existing single-flight resolution already uses putIfAbsent and completion-owned removal. Larger cipher/transport, language-aware variant selection and true userspace USB work need dedicated compatibility checks; do not replace the current backend or treat all main changes as shipped in 5.0.0. |
| LastWave Desktop | SABR-only playback, addon duet/solo matching, incremental artist sections, settings search, accessibility, mouse history buttons, queue locking, sleep controls, download layouts and backup tools. | No licence was supplied in current repository metadata or the earlier tree review. No source code copied. Concepts are tracked separately; several already have independent Cosmotune implementations. SABR transport and further UI features remain candidates. |
| Lokal Desktop | Journal/tombstone reconciliation for playlist create/delete/reorder, paged device catalogues/file-size queries, provider enrichment fixes, metadata/lyrics repairs, convolver/EQ response, automatic headroom and complete dual-deck DSP racks. | Existing LAN remote control is not file synchronization. Journal reconciliation belongs with the still-unimplemented sync protocol. DSP/headroom, metadata and enrichment changes need checks against our own engines before adoption. |
| Lokal Mobile | USB focus/route isolation, per-recording DAC fallback/restart, persistent artist portraits/logos, artist release browsing, cross-device library/EQ features and lyric handling. | Preserve the current artist fallback. Durable visual caching and shared sync are follow-up candidates; a route label is not proof of exclusive or DSD output. |
| Mornye | Background playback recovery, WebDAV/NAS transport changes, richer nearby-device sync, artwork/statistics exchange, cross-provider download identity, quality caps and UI/performance work. | Flutter/Rust internals are not direct Kotlin patches. Keep recording identity, storage/network boundaries and explicit pairing. Nearby-device/file sync remains separate; no unrestricted network permission was adopted. |
| Mornye extensions | Amazon per-track Atmos/AC-4 failure semantics, contributor matching, on-demand Qobuz previews, Spotify release dates and large-playlist memory use, collection suggestions and canonical registry URLs. | Amazon registry is now **2.3.16**, compared with **2.3.14** in the prior audit. Host metadata compatibility is adapted. Updates retain Sources → Review update and package/hash/permission review; no silent replacement or authenticated gateway test was performed. Provider/operator availability remains separate. |
| Kotatsu parser fork | 52 commits / 44 paths: new parsers and fixtures, domains and reader fixes, WebView-specific paths, and broken-source flags. | Updated the available pinned artifact; adapter/catalogue and platform compilation checks apply. Unsupported WebView paths remain explicit rather than pretending every parser works on desktop. |
| Tsuki / Mihon | No change since the exact previous heads. | Preserve the existing cache, source-removal and extension-replacement fixes; no speculative import. |
| AntennaPod | Playback service interface cleanup and sleep-timer expiry when shake detection is unavailable. | Cosmotune timers already use owned deadlines and do not depend on shake detection. Android service internals are not copied into desktop/browser timers. |
| gPodder | App-indicator “check for new episodes” and documentation/translation updates. | Manual Podio refresh exists; Python desktop tray code is not an Android/Kotlin integration. |
| Other eight podcast projects | No source delta since the last review. | No newly verified paid-only/account-free catalogue adapter. Preserve public RSS/YouTube podcast boundaries. |

## Larger unfinished work

The three previously deferred implementations remain **Watch Podio**, **bidirectional library/file synchronization**, and **native DSD/DoP**. This audit also identifies optional new work: automatic downloaded-playlist additions, SABR-native transport, userspace USB/hardware-volume recovery, language-aware stream variants, durable artist visuals, richer home moods, settings/accessibility improvements and additional DSP tools. These are not silently labelled implemented.

Physical-device/account checks, unavailable provider recordings, unsupported manifest layouts and undeployed services remain validation/service limits rather than completed features. The monthly dataset-refresh schedule remains deferred, and the recommendation ranker remains unchanged.

## Validation

The combined Gradle gate passed in 6m 56s: shared, provider, Manga, desktop, Android unit, shared UI, Video and UI interaction suites; Android app, TV and watch Kotlin compilation. Its XML reports contain **6,057 cases: 6,017 passed, 40 skipped, zero failures/errors**. Per-suite totals (including skips): shared 2,565; providers 143; Manga 75; desktop 526; Android 1,209; shared UI 669; Video 790; UI interactions 80. Existing platform/account-dependent skips remain skips, not successful device checks.

Browser TypeScript checks and all **174 browser tests** passed. The pinned Amazon metadata fixtures passed **7/7**. OpenCanvas's 42-file import check, companion source attribution (43 TV / 193 browser imports and 57 browser delta records), tracker evidence (67 implemented rows) and whitespace checks passed.

Private ownership uses synthetic catalogue fixtures only; no existing provider credentials or private library were used for live requests. These Windows-host checks do not establish actual macOS/Linux playback, USB/DAC output, private-upload playback or every Manga site's availability. No installers were packaged. Raw comparisons, fetched source snapshots and validation logs stay in ignored `build/upstream-audit-2026-11-02/` (`final-validation.log`, `web-complete-validation.log`, `amazon-metadata-upstream-tests.log`).
