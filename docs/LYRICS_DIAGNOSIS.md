# Aadiyile Sedhi: live reproduction and fix

Checked 22 October 2026 through Cosmotune's own compiled Innertube client, parsers and lyrics repository. No account credentials were loaded into the diagnostic process.

## Exact recording recovered locally

The installed player showed **Aadiyile Sedhi**, credited to **Deva**, from **Tamil Retro Essentials**, about 4:55 long. The app's existing playback log and OpenCanvas map identify the selected video as [`0-MbukW3ih4`](https://music.youtube.com/watch?v=0-MbukW3ih4). It is a video upload. The app window independently showed Telugu lyrics attributed to YouTube Music.

A catalogue search returns the same song under the spelling **Adiyile Sethi Solli**, credited to **Chitra**, 4:59, from **En Aasai Machan**: [`AbAhag-m4yA`](https://music.youtube.com/watch?v=AbAhag-m4yA). Composer and singer credits differ, so choosing only by artist/title similarity would not establish the recording's identity.

## Provider responses

| Request | Lyric endpoint | Lines | Letter script counts | Timing |
| --- | --- | ---: | --- | --- |
| Exact playing upload, before fix | `MPLYt_Ovkt7hyOOBK` | 45 | Telugu: 636 | All timestamps zero |
| Catalogue recording | `MPLYt_Bs2qyi8Wg4J-1` | 55 | Tamil: 785 | All timestamps zero |
| Exact playing upload, after fix, through the full provider pipeline | Verified catalogue endpoint | 55 | Tamil: 785 | All timestamps zero |

The upload has a genuine selectable Lyrics tab. The wrong-script result is therefore **not** caused by the old arbitrary-tab fallback, a cached desktop result, or an app translation in this reproduction. A fresh anonymous request to that actual endpoint returned the Telugu document. YouTube's video-to-lyrics association is incorrect for this upload.

## How the fallback establishes identity

The correct album's own primary track shelf explicitly links `0-MbukW3ih4` under the catalogue title, singer and 4:59 duration. This exact-ID relationship verifies that the differently labelled video and catalogue track correspond.

The shared YouTube Music lyrics provider now uses that relationship for uploads lacking a trusted catalogue context:

1. Search the Songs catalogue using the playing track's metadata.
2. Inspect at most eight candidates across at most three distinct album pages, within a six-second lookup budget.
3. Require the release's primary track shelf to link the exact playing video ID under the candidate's matching title, artist/version and duration. Recommendation carousels are excluded.
4. Fetch the verified catalogue track's plain lyrics. If identity cannot be established, let other enabled providers handle the lookup rather than display the upload's unverified lyric association.

No song IDs are hardcoded into production logic. No script is rejected or rewritten. Only YouTube Music is contacted by this fallback, and it runs only when that provider is enabled. Playback audio and the selected video are unchanged; album words remain plain text rather than being assigned invented timing.

Eight deterministic tests cover the exact recording metadata, false matches, wrong versions/durations, recommendation exclusion, bounded/deduplicated album requests, missing albums, network failures and cancellation. The successful live before/after provider probes are ignored local artifacts in `build/task-live-providers*.log`; the raw provider lyric text is not committed.

Final validation: **4,663 tests passed, 8 existing cases skipped, zero failures/errors**. Android debug APKs and the Windows app image were rebuilt successfully with this shared fix. The packaged shared JAR contains the new catalogue-verification helper. The installed older app was inspected but not replaced.

## Synchronization scope

This particular YouTube Music document supplies no line or word timing, so it cannot establish synchronized highlighting. Timed providers' ignored LRC offsets and repeated timestamps were repaired separately. Different masterings and video intros can still need a verified alignment or the existing manual timing control; this lookup does not guess an audio offset.

Computer Use was stopped during inspection; no further app-window actions were taken. The final before/after verification used direct code/provider calls.
