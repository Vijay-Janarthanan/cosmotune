# AETHER and Euthopiar integration

> **Continuation handoff, 11 November 2026:** this progress list predates several later implementations. Read [CLAUDE_HANDOFF.md](CLAUDE_HANDOFF.md) for the current state, exact validation failure, newest untested audio changes and remaining work. This batch is incomplete and has not been committed, pushed or released.

Requested 11 November 2026. Based on the pinned revisions in [the source/release audit](UPSTREAM_REVIEW_AETHER_EUTHOPIAR_2026-11-11.md). No release version or public tag has been changed for this work.

## Scope and attribution

Keep Cosmotune's Orbit design and shared Android/desktop implementation. Constellations and Continuous Line are excluded at the user's request. Existing word/syllable timing, backing vocals, duet layout, held-note effects and lyric seeking remain the foundation of the lyric view.

Hangul pronunciation rules and the 39-model headphone catalogue are adapted from AETHER at `fd019594548e52be06f6339bb758ff5661f15f3d`, GPL-3.0. The catalogue retains each measurement author's name. See `licenses/AETHER-LICENSE.txt`. Euthopiar has no licence in the reviewed tree. Its public behaviors are being implemented independently against the service/protocol documentation.

## Implemented in this working tree

- Offline ICU romanisation on Android and desktop, Korean pronunciation, preservation of lyric/backing timings and existing network fallback for unsupported scripts. Mixed Japanese kana/kanji does not receive Mandarin readings.
- 39 measured headphone presets, plus previewed GraphicEQ and parametric text imports. The current DSP uses seven fixed bands, so these are labelled approximations with full headroom and measurement attribution.
- Watch progress, My list and selected preferences integrated with the existing opt-in account sync on Android and desktop. Source tokens and playback addresses are excluded.
- Household Watch profiles with separate progress/My list. The local viewer selection does not switch another device's viewer.
- Watch density choices and type/genre/year search filtering through the shared screens.
- Native Watch trailers through the existing YouTube/video player. Trailer progress is excluded from Continue Watching.
- Download free-space preflight and checks during transfer, with recoverable failures and retained partial files.
- Local crash-note viewer/copy/delete in phone and desktop settings. Export omits exception messages and media URLs.
- Separate executable Watch JavaScript adapter using the existing isolated QuickJS/Graal runtime, declared domain permissions, cancellation and output limits. CommonJS `getStreams` scripts are separate from HTTP manifest add-ons. Browser/Node-specific scripts need compatibility validation.
- Optional Trakt and Simkl public-client device authentication, refresh serialization, viewer-scoped credentials, saved-title reconciliation, remote resume points, and playback-reporting hooks. Sign-in and sync controls are reachable on the shared Watch Plugins page. Production account authentication has not been exercised.

## Still required before calling the requested integration complete

- Validate the latest working tree in both app entry points and all applicable runtime/UI tests. Mock-server tests do not establish live service compatibility.
- Complete and exercise Watch JS repository installation/update/cloud restoration and broader host API compatibility.
- Complete Trakt/Simkl watched-history import/export and event/refresh/offline reconciliation coverage. Verify with authorized accounts and registered public client IDs.
- Additional attributed ratings, regional Top 10 and anime/soap/web-series discovery grouping.
- AniSkip mapping/timestamps, SubDL subtitles, Watch casting and account-based debrid resolution.
- Download destination selection and finite torrent/HLS/DASH download support; DASH/header and multi-audio MKV regression fixtures.
- Household profile deletion/PIN controls and settings isolation.
- Acoustic alignment before better-stream handover and album-gapless policy checks.
- Remaining offline library/download/queue UI audit and a tested refinement of the current lyric presentation.
- Update user-facing feature documentation after final verification. Do not advertise unfinished integrations as shipped.

## Validation

The initial romanisation/sync regressions passed. A subsequent build passed Watch tests, the executable Watch-script test, shared desktop UI compilation, Android release Kotlin compilation and desktop Kotlin compilation. Further tracking and headphone changes were added afterward, so that build is not the final validation of this work.
