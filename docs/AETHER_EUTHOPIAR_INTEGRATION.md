# AETHER and Euthopiar integration

Requested 11 November 2026, against the pinned revisions in [the source and release audit](UPSTREAM_REVIEW_AETHER_EUTHOPIAR_2026-11-11.md). No release version or public tag has been changed for this work, and nothing here has been released. This page replaces the progress list in [CLAUDE_HANDOFF.md](CLAUDE_HANDOFF.md), which records how the work was handed over.

## Scope and attribution

Keep Cosmotune's Orbit design and the shared Android/desktop implementation. Constellations and Continuous Line are excluded at the user's request. Existing word/syllable timing, backing vocals, duet layout, held-note effects and lyric seeking remain the foundation of the lyric view.

Hangul pronunciation rules and the 39-model headphone catalogue are adapted from AETHER at `fd019594548e52be06f6339bb758ff5661f15f3d`, GPL-3.0; the catalogue keeps each measurement author's name. The sound-alignment method follows AETHER's `SoundAlignment`, which AETHER credits as a port from PODIUM (GPL-3.0). See `licenses/AETHER-LICENSE.txt`, `NOTICE.md` and the desktop licence screen. Euthopiar has no licence in the reviewed tree, so its behaviours were implemented independently against the services' public documentation, with no Euthopiar source included. CryptoJS 4.2.0 (MIT) is bundled for the Watch script runtime.

## Status

"Tested" means automated tests that exercise the behaviour, with loopback servers standing in for remote services. None of these checks used a real account, receiver or upstream script. "Unverified" means the code is in place and compiles, but nothing has exercised it end to end.

### Done and tested

| Feature | Evidence |
| --- | --- |
| Offline romanisation (ICU on Android, ICU4J on desktop, Korean rules, Japanese kanji left alone) with timing preserved | `OfflineRomanizationTest` |
| Measured headphone catalogue and GraphicEQ/parametric import, as seven-band approximations with headroom and attribution | `HeadphoneEqTest` |
| Wrapped lyric rows reserve their height, so they do not collide | `LyricsMetricsTest`, lyric contract tests in `sharedUi` |
| Local crash note: exception types and frames only, never messages, addresses or tokens; kept across restarts; delete removes it | `CrashNotesTest` |
| Watch profiles, PINs, per-profile lists, playlists and preferences | `WatchProfilesTest`, `ProfilePinsTest`, `WatchPlaylistRepositoryTest` |
| Opt-in cloud sync of Watch progress, My list, playlists and preferences, with no tokens, PINs or playback addresses | `SyncWatchLibraryTest` |
| Type, genre and year filters on search results | `WatchSearchFilterTest` |
| Attributed OMDb ratings; SubDL individual subtitles only (no archives, no other episodes) | `WatchMetadataExtrasTest` |
| AniSkip intervals, checked against the episode's length | `AniSkipTest` |
| Download free-space checks before and during a transfer, approved destination folders, filename/root validation | `WatchDownloadsTest` (now runs; its last case had a non-void test body) |
| Watch JavaScript runtime: isolated CommonJS `getStreams`, bounded host APIs, network permissions | `WatchScriptRuntimeTest` (providers) |
| Watch script install, update and restore share one address rule: public HTTPS without credentials, query or fragment; install refuses before downloading | `WatchScriptUpdateTest` |
| Script updates: "Check for update" downloads and compares fingerprints without changing anything; Install applies exactly the checked bytes, once; the new pin syncs and moves on other devices | `WatchScriptUpdateTest`, `WatchScriptSyncTest` |
| Trakt/Simkl: renewal before expiry; one renewal and retry after a 401; a refused refresh signs that viewer out and keeps the local library; deletions on either side win; a watch lost to the network is exported once at the next sync and not re-sent after a remote deletion; Simkl device grant forms, per-item `to`, activities gate (and no repeated additions while activities are unchanged) | `WatchTrackingReconciliationTest`, `WatchTrackingServiceTest` |
| Debrid: Real-Debrid, AllDebrid and TorBox request sequences against fixtures; only the named file of a pack is used, a pack without a filename is refused, insecure addresses are refused, nothing deletes a transfer | `WatchDebridClientTest`, `WatchDebridServicesTest` |
| DLNA casting session lifecycle against a fake renderer: stop saves where the receiver got to and resumes there; any change of copy or title on the device (next episode, retry, source, channel) stops the receiver; a receiver that leaves the network ends the session; a late close from a left page never clears a newer cast's remote flag or stops the shared TV; header-bound copies are refused before any network | `WatchCastingTest` |
| Watch first-use guide on Home: built from the device's own state, optional steps say so, put away per device (not synced), absent from other tabs | `WatchSetupGuideTest`, `WatchVisualReviewTest.firstOpenShowsTheSetupGuideOnHomeUntilItIsHidden` (renders `build/watch-review/guide-*.png`) |
| Album gapless protection: only an album played through from its release page, unshuffled, track to following track, in the context section; never a playlist, search, manual queue, AutoPlay or repeat-all wrap. A plain crossfade leaves that join to the player's own gapless transition; Automix uses the planner's gapless plan. Android now passes album title and id to the planner | `AlbumPlayThroughTest`, `DesktopQueueTest` |
| Sound alignment algorithm: synthetic decoded copies at different rates, gains and chunk sizes with -310 to +430 ms of padding align to within 10 ms; different recordings, unfinished decodes, silence and periodic beats give no answer; seeks restart the meter; bounded memory; safe concurrent use | `AcousticAlignmentTest` |
| Desktop decoder PCM callback stamps each block with the song time of its first frame after seek trimming | `DesktopAudioDecoderTest` |

### Done but unverified

| Feature | What is missing |
| --- | --- |
| Better-stream sound alignment on Android and desktop | **Switched off** (`AcousticAlignment.applyToUpgrades = false`). With it off, the phone decodes nothing and the desktop neither meters, pre-rolls nor waits, so upgrades behave as before. No real pair of renditions has been measured. Before switching it on: measure real Opus/AAC/FLAC pairs; decide which clock lyrics and progress follow after a shifted seek (the new copy's position differs from the old by the offset); account for resampler latency on the desktop (about a millisecond). |
| Trakt and Simkl with real accounts | Public client IDs must be registered by the maintainer; no live sign-in, sync or scrobble has run. |
| AllDebrid and TorBox with real accounts | Fixture-tested only. |
| DLNA Watch casting on real TVs | No physical receiver was used. Google Cast is not implemented for Watch, so casting is DLNA only. |
| Nuvio scripts from real repositories | Only fixtures and a public manifest were inspected; browser/Node/WASM-specific scripts are only partly compatible. |
| Native trailers, Watch density, regional Top 10, anime/soap/scripted rows | In place on both apps through the shared screens; no dedicated tests beyond the existing visual reviews. Regional rows still default to Tamil and Hindi rather than the viewer's languages. |
| Album gapless on hardware | Guard and planner wiring are tested; the audible join on Android Media3 and the desktop engine has not been listened to. |
| Android and desktop end to end | Both compile; the new first-use guide and script-update dialog were checked in render tests, not on an emulator or a desktop session. |

### Not done

- Google Cast for Watch.
- Relative-DASH, header-retention and sequential multi-audio MKV repairs. Assessed: Android keeps a load's headers until the next load, and Media3 and libmpv resolve relative DASH base URLs themselves, so Euthopiar's workarounds do not transplant. Without regression samples nothing was changed, and the MKV case remains unassessed.
- HLS, DASH or torrent downloads (only direct MP4/MKV/WebM files).
- A dedicated offline library/queue/artwork audit, and the lyric presentation refinement beyond the row-height fix.
- README and user documentation for this batch.

## Validation

The commands, logs and results of the final run are listed in the commit history of this branch and in the hand-back report. The build uses `-I build/video-playback-review/limited-tests.init.gradle` from the main checkout to bound test memory.
