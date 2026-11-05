# Headphones, song lists and discovery: proposal

Research date: 2 November 2026. The listener subsequently approved implementation. Device profiles and Home discovery improvements are described below. Hardware verification remains outstanding.

Hardware confirmed by the listener: Samsung S24 Ultra, Sony **WH-1000XM5** over-ear headphones, Galaxy Buds4 Pro, and AUDIOCULAR CS-Pro CS46L41 USB DAC.

## What the code already does

- Android tracks audio routes and tries to read negotiated Bluetooth codec/rate/depth (`BluetoothAudioTracker`, `LosslessOutput`). Codec reads use an OEM-dependent system/reflection path; unavailable values correctly become “System Managed.” A headphone's advertised capabilities are not proof of its active transport.
- Android has a float DSP path, EQ, and an opt-in Android 14 USB bit-perfect mixer request (`PrecisionAudioSink`, `BitPerfectMixer`). Hardware/OS support still has to be checked on the actual S24/DAC.
- The app's current optional `SpatialAudioProcessor` is **stereo widening and delayed crossfeed**, not Android head-tracked spatial audio. It defaults off. Its width factor is 2.5 with makeup attenuation; if enabled, it can change centre-vocal balance relative to side content. This is a mechanism to investigate, not evidence that it caused a particular listening complaint.
- A small music model and online preferences already exist. The bundled model is 3,186,092 bytes compressed / 4,176,439 bytes unpacked. The manifest records 583,496 source records and 7,810,857 graph links, but these are **not 583,496 unique playable songs**. The bundled discovery vocabulary has 4,096 entries. Live provider candidate generation and playback resolution remain important.
- `LocalRecommendations.enhance` locally reorders provider radio and considers a bounded number of additional discoveries; it does not search the whole training catalogue. `OnlinePreferenceModel` learns hashed preferences locally. Playlist generation already has an artist-diversity limit, so the proposal is to improve coverage and evaluation rather than add another duplicate ranker.
- `DiscoveryFeed` has regional charts and a selected-language shelf. The latter currently searches the generic phrase “<language> songs” and takes 20 results. That is a weak basis for freshness and variety even if the controls work.

## Device-aware audio

| Device | Verified capability | Proposed Cosmotune behaviour |
| --- | --- | --- |
| Sony WH-1000XM5 | SBC, AAC and LDAC over A2DP | Identify the route; show actual codec when exposed; guide LDAC setup; remember a user-approved EQ/profile. Do not offer the WF earbuds' LC3 mode for this WH model. |
| Galaxy Buds4 Pro + S24 Ultra | Samsung lists SSC UHQ up to 24-bit/96 kHz on the S24 series with compatible One UI. UHQ is off by default. | Show a Samsung setup guide and link to system/vendor settings; display confirmed route information separately from advertised capability. |
| AUDIOCULAR CS-Pro | USB route needs actual format negotiation | Reuse and test the existing float/USB mixer path, show the accepted format and reasons for fallback. Do not infer DSD transport or hardware EQ support from a product title. |

Sources: [Sony WH codec guide](https://helpguide.sony.net/mdr/wh1000xm5/v1/en/contents/TP1000534600.html), [Samsung Buds4 Pro compatibility and UHQ setup](https://www.samsung.com/us/audio-sound/galaxy-buds4-pro/), [Android USB preferred mixer attributes](https://source.android.com/docs/core/audio/preferred-mixer-attr).

Recommended interface: one **Listening device** sheet reached from the output picker and audio stats. It should show Source → App processing → Android output → Bluetooth/USB, with measured, advertised and unavailable values clearly distinguished. Include an optional profile per confirmed device, a flat bypass, EQ/headroom, channel balance, and a route-specific setup guide. Do not silently change tuning on first connection or force every song to 96 kHz.

For optional headphone correction, investigate [AutoEq](https://github.com/jaakkopasanen/AutoEq), which provides measurement data and EQ optimisation. Import only a matching model/measurement with provenance and a preview; do not invent a Buds4 Pro curve if none is available. Keep profiles small, download on demand, and retain an audible bypass and level-matched comparison. Check each measurement's source conditions before redistribution. A headphone preset is subjective tuning, not a universal sound-quality upgrade.

### Controls the app cannot promise

Bluetooth negotiation is managed by Android and the headset. AOSP marks `getCodecStatus` and codec preference controls as system APIs with permission/association restrictions; reflection is not a portable guarantee. Device identification alone cannot establish the negotiated codec, current bitrate, internal DAC format or ANC mode. See [AOSP BluetoothA2dp](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/framework/java/android/bluetooth/BluetoothA2dp.java).

Sony's DSEE Extreme settings are documented through its companion app; Sony also notes configuration-dependent interaction with LDAC. Prefer vendor setup links over unsupported in-app switches. [Sony DSEE guide](https://helpguide.sony.net/mdr/wh1000xm5/v1/en/contents/TP1000539063.html).

For genuine platform spatial audio, query Android's `Spatializer` support, route availability, enabled state, content eligibility and head-tracker availability. Preserve eligible multichannel content and avoid stacking our stereo widening over system spatial processing. Standard stereo files do not become genuine Atmos merely because a headset supports spatial audio. Compatibility is checked at runtime, not asserted from model names. [Android spatial audio integration](https://developer.android.com/media/grow/spatial-audio).

## Baseline findings: why YouTube Home felt sparse

Findings from the prior implementation, not a live account diagnosis. The implementation below addresses these paths:

1. `AccountHomeFeed` turns both initial and continuation failures into nullable results without a visible account-feed error/retry state. A failure can look like a small, successful feed.
2. `HomePlanner.stream` uses account Home exclusively whenever any account items exist. If that page is small or its continuation fails, subscriptions and public/local suggestions do not fill the main stream.
3. The local “Up next for you” shelf uses related lists from only **three recent watches**, capped at 12 items. That cap is for the shelf, **not the whole Home feed**. The main feed already supports pagination; simply increasing a constant would not repair candidate coverage or failed continuations.
4. Non-account Home depends on the kiosks offered by its extractor, subscriptions and those related lists. Provider changes and a thin local history reduce diversity.

Proposed fix sequence:

- First make initial/next-page errors, exhausted feeds and retries explicit; guard duplicate continuation tokens, preserve scroll/loaded cards, and retry without erasing useful content.
- Gather independent, bounded candidate pools: subscriptions, recent interests across more than three distinct watches, selected topics/languages, fresh uploads and regional popular content. Fill thin/failed pools from the others. Keep refreshes cancellable and cache useful pages.
- Re-rank locally with watched/not-interested/channel feedback, freshness, topic affinity and creator diversity. Give discovery/exploration a controlled share; avoid many near-identical uploads from one channel. Add “Why this?” and “Show less” controls.
- Keep account Home optional and identifiable. The official YouTube Data API offers `videos.list` with `chart=mostPopular`, `regionCode` and `videoCategoryId` for regional/category popularity; this endpoint is not a replacement for a person's YouTube Home. Its use requires a configured API project/quota plan. [Official videos.list API](https://developers.google.com/youtube/data/reference/rest/v3/videos/list).

## Better song lists and recommendations

The best next step is **broader verified candidates plus better ranking**, while retaining the model under 5 MB. More raw records alone will not fix a narrow feed.

- Combine current provider radio, release/artist catalogues, regional charts, actual language-labelled releases and local history/library. Where metadata is unknown, label it unknown rather than guessing the language from the singer's name.
- Canonicalise recordings across providers using identifiers where available, then title/album/version/duration and structured credits. Keep live, cover, remix, instrumental and original recordings distinct. Show each recording once with available sources in its details.
- Extend the existing local learner with explicit likes, “less like this,” repeated listens, meaningful skips and session intent. Treat pause, network failure and end-of-session differently from dislike. Separate music and video interests.
- Generate Daily Mix, Rediscover, New releases from followed artists, regional discovery and seed-based playlists from the same candidate service. Save editable playlists and explain their seed/preferences.
- Improve list usability on phone/tablet/desktop: stable row hierarchy (title, artist, album), consistent trailing menu/queue actions, genuine sort/filter controls, retained scroll, pagination, multi-select and accessible touch targets. Keep detailed format/provider comparisons in the stats sheet rather than crowding every row.

## Proposed delivery order and proof

1. **YouTube Home reliability and music candidate coverage.** Reproduce failed/short continuation pages, language/region changes, repeated pages, empty history and account expiry with deterministic fixtures. Check first useful content, usable feed length, freshness, duplicate rate and recovery. Share desktop and Android feed logic.
2. **Listening device profiles and truthful audio stats.** Real S24 walkthroughs with WH-1000XM5, Buds4 Pro and the USB DAC: reconnect, route swap, pause/resume, EQ bypass, stereo balance and accepted output formats. Confirm codec only if exposed; otherwise show the setup path. Test internal PCM fidelity/clipping separately from subjective listening.
3. **Richer local recommendation and playlist controls.** Compare against today's baseline on later-time held-out listening data. Measure coverage, artist/language diversity, duplicate/version errors and user feedback, not only a single accuracy number. Keep bundled model below 5 MB; target sub-50 ms local ranking for a realistic batch on S24-class hardware, measured separately from network fetching. This is a target, not an achieved benchmark.

No new recommendation backend is needed for the initial approach. Live discovery still needs provider network requests. Optional measurement/catalogue/profile updates can be fetched separately from app releases; scheduling remains deferred as previously requested.

## Implemented device-profile scope

- Shared **Listening device** sheet in output selection and audio stats, with settings entry points on Android and desktop. It separates reported app-output values from model-advertised specifications and unavailable downstream information. Remote Cast/watch playback does not display local phone hardware as the remote receiver.
- Small, device-local profile store: at most 12 profiles / 8,000 JSON characters. The user explicitly confirms the model and saves the current EQ, balance and headroom. Reconnect restoration is off by default. Flat EQ bypass retains the saved tuning and disables its processing; Apply saved tuning restores it. Profiles never enable stereo widening or change codec, volume, source rate or ANC.
- Android maps a Bluetooth hardware address when the OS exposes it, otherwise a route kind/name. Runtime numeric AudioDevice IDs are never persisted. Name-based identities can match identically named devices; the sheet explains this limitation. Desktop requires an explicit output selection; system default can move devices and cannot own a profile. Reused ALSA indexes / mixer names need user confirmation.
- Android 12L+ public Spatializer APIs report support, enabled/available state and current-format eligibility; Android 13+ reports head-tracker availability. Unsupported APIs or failed queries are labelled unavailable. Eligible (or not-yet-known-eligible) active system spatialization suppresses app stereo widening, as do Atmos streams. Eligibility is not claimed as proof of actual Atmos rendering. Desktop platform spatialization is unavailable through the current backend. The existing effect is labelled **Stereo widening**.
- Optional user-pasted AutoEq **GraphicEQ** import: exact selected headphone model, matching AutoEq results source URL and measurement/rig text are required. Input is bounded, finite, ordered and gain-checked. The app previews a log-frequency **seven-band approximation**, records the user-provided provenance and requires explicit Save and apply. Arbitrary parametric frequency/Q filters are rejected. Pasted values are not cryptographically verified against the URL; no particular WH-1000XM5 or Buds4 Pro measurement is bundled or invented, and no network request occurs on connection. The preview includes headroom; automatic loudness matching is not implemented.
- Sony LDAC, Samsung SSC UHQ and DAC setup use official/seller guidance plus system settings links. Codec control, ANC control, direct Android USB transport and hardware DSD are not inferred or fabricated.

Focused fixtures cover opt-in reconnect/duplicate events, flat bypass preservation, persistence errors, invalid profiles, exact-model AutoEq provenance/format/range validation, and system-spatial double-processing guards. Build results are recorded by the parent validation batch; physical S24 Ultra + WH-1000XM5 / Buds4 Pro / CS-Pro reconnect, codec visibility, subjective tuning and head-tracker walkthroughs remain unverified.

## Implemented YouTube Home reliability and discovery scope

- Signed-in account Home has explicit initial, continuation and session-expired states with retry / renew-sign-in actions. Loaded cards survive failures. Account changes/disposal cancel work, generation checks reject late answers, and repeated continuations / consecutive no-progress pages stop paging. The account pool is capped at 600 cards / 128 continuation tokens.
- Account recommendations are optional in Video settings. All blends available account, subscription, local watch-interest and source-topic candidates, with an account lead rather than account exclusivity. Account and topic continuations page independently, so one failed/exhausted pool does not block the other. Source labels and Why this video explain the contributing pool. Neither source-topic lists nor local reranking are labelled a global popularity API.
- Local interest generation selects up to eight seeds from 32 recent watches, preferring distinct creators, fetches at most three related lists concurrently, and contributes up to 48 candidates. Topic pages cap at 600 cards; eight topic views and 64 bounded related lists are cached. Refresh preserves useful cards and failed continuations require explicit retry instead of repeated scroll requests.
- Home performs a bounded, stable local look-ahead that considers completed watches, known recent upload dates and adjacent creator repetition. Missing language/genre metadata is not guessed. This is heuristic reranking, not a newly trained YouTube model or an achieved ranking benchmark.
- Not interested in this video / Show less from this channel persist on this device and filter mixed Home, topic lists, Continue watching and Shorts. Reset Home feedback clears those choices without deleting history, library or remote account data. Persistence bounds feedback to 1,000 video IDs / 300 channel IDs.
- Region and content-language preferences are readable from Home and lead to existing settings; the source locale is configured before region-specific requests and cache keys reset on changes. Refresh explicitly retries the source pools. These preferences influence the source, and do not establish that every recommended video is in the selected language.

New deterministic fixtures cover account initial/session/continuation failures, explicit retry, cancellation/disposal, repeated token stopping, kiosk refresh preservation, broader interests, cross-pool filling, feedback persistence/bounds and stable reranking. Existing structure/codec contracts were updated for the new behavior. Parent build/test results are reported separately; no live personal-account or physical-device walkthrough is claimed here.

## Approved discovery and list implementation

Music discovery implementation and its bounded source/metadata behaviour are recorded in [Music discovery and feedback](MUSIC_DISCOVERY_AND_FEEDBACK.md). Native Home now supplies actual history, liked IDs and the explicit artist watch list. Source hearts feed local preference learning only after confirmed actions; local Less like this is available without requiring a source account.

The shared song-list controls are wired into the actual new-look Android release page and desktop collections, with legacy Android detail support. They provide stable ordering, downloaded filtering, occurrence-based selection, Select shown, clear, Play selected and bulk actions. Unknown addition dates are not labelled as real chronology; reverse list order is separate. Header/row actions use the visible queue. Filtered/sorted/selected Android queues no longer expand back to the entire provider page; canonical unfiltered pages retain bounded continuation. Desktop continuation preserves playlist occurrences, retains network-failure tokens for explicit retry and stops repeated tokens visibly.

Books remains a [separate plan for review](READING_BOOKS_PLAN_2026-11-02.md). The immediate shared Manga cleanup and requested source-name exclusion are implemented there; renaming and a book reader have not been added prematurely. Verification results will be recorded after the parent batch. Physical headphone/DAC proof and publication are separate.
