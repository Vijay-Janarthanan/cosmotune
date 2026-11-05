# Provider capabilities and automatic selection

Inventory checked on 2026-10-26. A catalogue integration, an advertised audio tier, successful resolution, actual decoder input and actual device output are different facts. A provider's presence in this list does not establish working account access in Cosmotune.

Published update, 1.0.10: artist matching accepts equivalent names with different word breaks, a provider-package row that cannot resolve is looked up by title/artist on other sources with the failed package excluded, and playback/page failures write their reasons to the log. The player adds a decoder-reported Hi-Res Lossless/Lossless/High quality/Dolby Atmos pill. This does not establish Atmos speaker output or successful audio from every advertised provider. [Release notes and live-test limits](releases/1.0.10.md). The 1.0.10 release build completed successfully and its installers are published.

## Providers we can target

| Service | Role exposed by the inspected integration | Advertised audio | Dependencies / limits |
| --- | --- | --- | --- |
| Qobuz | Catalogue and audio | Lossless, 24-bit and maximum 24-bit tiers | Signed operator session/grant and prepared context; real-account playback unverified. |
| Tidal | Catalogue and audio | Lossless, hi-res FLAC, lossy and Dolby Atmos tiers | Signed session/grant and segmented transfers; source identity does not verify immersive rendering. |
| Amazon Music | Catalogue, home feed and audio | FLAC, Opus, Dolby Digital Plus and an AC-4 Atmos tier | Signed session/grant and preparation; AC-4 requires an appropriate decoder/output route. |
| Deezer | Catalogue and audio | FLAC | Signed session/grant, preparation and patterned transforms. |
| Yandex Music | Catalogue/search and audio | Available lossless FLAC or lossy formats | Lokal's bundled package uses the listener's OAuth token; per-track availability varies. |
| SoundCloud | Catalogue and audio | Mornye declares AAC up to 160 kbps; Lokal also has a progressive MP3 path | Segmented preparation for some streams; full tracks versus previews depend on access. |
| JioSaavn | Catalogue, home feed and audio | AAC up to 320 kbps | Existing public metadata/AAC proof does not verify other providers. |
| YouTube Music | Catalogue, home feed, radio and audio | Best available lossy audio | Cosmotune's native implementation remains available; package access is a separate route. |
| Spotify Web | Catalogue metadata and discovery | No audio supplied by this metadata package | Package-only in the inspected registry; not a Spotify audio entitlement. |
| Apple Music | Catalogue, lyrics and home feed | No audio supplied by this package | Do not label this as Apple Music lossless playback. |
| Bugs Music | Korean catalogue, charts and home feed | Metadata only | Can enrich discovery; not an audio resolver in this package. |
| Melon Music | Korean catalogue, TOP100/HOT100, playlists and home feed | Metadata only | Same distinction between discovery and playback. |
| Genie Music | Korean catalogue, TOP200/genre charts and home feed | Metadata only | Same distinction between discovery and playback. |

Mornye's inspected registry contains twelve services. Lokal adds its bundled Yandex package and also implements its own YouTube Music/SoundCloud clients, HTTP JSON add-ons, and Mornye packages. Those are overlapping protocols/services, not thirteen independent ready-to-use audio sources. Soulseek is a separate file-sharing integration, not a streaming-provider equivalent.

## Current development behaviour

The following additions are in the 1.0.7 source; the 1.0.6 installers do not contain them:

- Enabled installed audio packages join Android and desktop automatic matching instead of being usable only from their own catalogue rows. Metadata-only packages do not enter audio selection.
- Source matching retains title/version, artist, explicit edition, album and duration checks. Package substitutions require close duration agreement. Original video uploads and explicit version/source policies retain their existing boundaries.
- Playback may begin on a fast usable stream while slower enabled sources are checked. Patient comparisons can select a better compatible rendition through the existing guarded upgrade path. Opening the details panel does not start additional requests.
- Lossless offers outrank ordinary lossy offers. Known bit depth and sample rate distinguish lossless offers. Different lossy codecs are not ranked by bitrate alone; source order breaks incomparable ties. Android's enabled Atmos mix preference is separate from an assertion about output rendering.
- Tapping the new player's format line opens Audio details in portrait, landscape, wide and dock layouts. The panel shows the playing source, known decoder input, the provider's declared offer, attempted-source results and installed-package tiers. Its output button opens the existing native decoder/DSP/device pipeline panel.
- Failure states distinguish no matching recording, access/verification requirements, unsupported provider features, quality limits, timeout and cancelled lookups. The diagnostic store is bounded and retains no raw exceptions, signed URLs, headers or tokens.
- Plain E-AC-3 no longer proves Atmos. A positive decoder JOC/Atmos profile can identify the received codec; a catalogue badge alone cannot. The ordinary desktop PCM output is not a verified Atmos renderer.

Providers still require installation/enabling and any account/operator verification their package requires. Cosmotune does not install every package or grant accounts implicitly. Sources → Provider packages already offers the Mornye repository, plus reviewed archive import for other compatible packages.

## Home discovery and personal playlists

- Home has independent country/global and preferred music-language selectors, persisted independently of interface language. Country charts use YouTube Music's explicit country form; `ZZ` means Global. Available categories depend on the source response/account.
- Supported provider home feeds receive the selected discovery locale. A language-discovery shelf is labelled separately from charts; a language search is not a verified popularity ranking or a strict classifier of every returned song's language.
- Make my playlist builds a local preview from a chosen recording, the user's known library/history/home candidates, the existing small model and local listening preferences. Optional bounded catalogue lookups resolve model suggestions to real song IDs.
- The preview can be saved as an editable local playlist on Android and desktop. Source-qualified and local recording identities are retained. No hosted training backend or new large model is added; small libraries can produce fewer than the requested number of tracks.

## Still separate work

Manifest-aware package HLS/DASH relaying, provider SDK FFmpeg operations, remote-device relay access, isolated desktop guest heap limits, and real authenticated-provider walkthroughs remain outstanding. The comparison panel reports unsupported/unavailable results instead of presenting these paths as working. Windows HDMI/native headphone Atmos routing and equivalent device-specific macOS/Linux/Android certification are not implemented by a badge change.

## Live evidence from 2026-10-26

These checks used isolated profiles and bounded decoding; no user account or physical audio output was used.

| Route | Observed result |
| --- | --- |
| Mornye JioSaavn package | Both “Blinding Lights” and “Tippulu Tappulu” decoded as AAC, 44.1 kHz stereo. |
| Supplied Qobuz HTTP add-on | The same recordings decoded as FLAC: respectively 24-bit/44.1 kHz and 16-bit/44.1 kHz. This does not verify the separate Mornye Qobuz package. |
| Mornye Amazon package in Lokal's runtime | Search returned eleven results. FLAC, E-AC-3 and AC-4 audio requests each returned `verification_required`. |
| Same Amazon package in Cosmotune | The fetch-envelope correction restored search. The complete metadata-enrichment/matching path reached stream resolution and reported access/verification required. |
| Global / India charts | Global returned one shelf with forty navigable artist entries; India returned two shelves with forty-seven navigable language/artist entries. This response did not establish a top-song shelf or strict language classification. |

The remaining public-package probe did not establish playable audio: Mornye Qobuz required verification, Yandex required OAuth, Spotify's client-token request failed, Apple returned no usable response, Tidal preparation failed, YouTube package preparation failed, SoundCloud/Deezer returned no usable search rows, and the Korean catalogue rows did not establish a duration-compatible audio match. These are results from this clean-profile probe, not universal claims that each service is unavailable.

The subsequent [operator/window follow-up](PROVIDER_VERIFICATION_BROWSER.md) verified Amazon's session with a user-provided grant and Qobuz's session automatically through a native Chromium window. The shared ticket-header fix restored Amazon media delivery. [Native audio preparation](PROVIDER_MEDIA_PREPARATION.md) now decrypts/remuxes its file, and **Blinding Lights — The Weeknd** passed actual Windows decoding as **24-bit/44.1 kHz FLAC**. The earlier unsupported result described a missing host step. Mornye Qobuz search returned twenty results; its download-preparation request exposed our short response timeout, which is now corrected. Session verification and immersive output remain separate evidence.

## Automated validation

The earlier discovery/source-comparison checkpoint completed successfully on Windows with 4,740 reported tests across shared (2,317), providers (112), shared UI (662), desktop (466), Android app (1,180) and the three new Compose interactions. Fourteen were intentionally skipped, including opt-in live tests; there were no failures or errors. The later verification-window checkpoint passed 4,812 reported tests with eighteen skips and no failures/errors; see [current validation](PROVIDER_VERIFICATION_BROWSER.md). Both compiled the TV production-debug target. Live probes were run separately and their availability results are recorded above, rather than inferred from skipped tests.

The new Compose checks exercise the format-line tap and comparison/output entry, independent country/music-language changes, and playlist preview/local save retaining source-qualified and local identities. A separate Android instrumentation run passed the large fetch envelope through actual QuickJS with its existing 64 MiB guest bound. Unit tests and emulator execution do not establish macOS/Linux binaries, authenticated service playback or physical-device output.

Ignored local evidence: `build/audio-discovery-current-regression.log`, `build/provider-android-large-fetch.log`, `build/provider-live-probe-2026-10-26/`, `build/provider-amazon-refined-2026-10-26/`, `build/amazon-comparison/` and `build/discovery-charts-live.json`. The feature-tracker evidence checker also passes.

See [the original audit](AUDIO_PROVIDER_AUDIT_2026-10-26.md), [provider runtime evidence](PROVIDER_PACKAGES.md), [remaining work](PENDING_WORK.md) and [feature tracker](../FEATURE_TRACKER.md).

## Sources

- [Mornye registry](https://github.com/mornyeapp/Mornye-Extension/blob/6b544663fb5b7a7dfbe1959f3c0edab017378f53/registry.json) and [source manifests](https://github.com/mornyeapp/Mornye-Extension/tree/6b544663fb5b7a7dfbe1959f3c0edab017378f53/sources).
- [Lokal Yandex package](https://github.com/lokal-music/lokal/tree/dc43d85db7711279f58d969107883084965b2005/addons/yandex-music) and [native online sources](https://github.com/lokal-music/lokal/blob/dc43d85db7711279f58d969107883084965b2005/electron/online/sources.js).
- [ytmusicapi's current country-chart request](https://github.com/sigma67/ytmusicapi/blob/master/ytmusicapi/mixins/charts.py).
- [Dolby JOC and compatible base decoding](https://dolby.my.site.com/professionalsupport/s/article/What-is-Dolby-Digital-Plus-JOC-Joint-Object-Coding).
