# Upstream ideas taken, 21 November 2026

Checked the 24 repositories of the [20 November review](UPSTREAM_REVIEW_2026-11-20.md) against its [snapshot](UPSTREAM_SNAPSHOT_2026-11-20.json) heads. Ten had moved: AETHER (one commit, its 3.0.0 release), LastWave Native (60 commits), Lokal Desktop (16), Lokal Mobile (10), Kotatsu parsers (4, plus merges), Mihon (4), Mornye Extension (3), Tsuki (2), BitChord (2) and Mochy (1).

Most of AETHER 3.0 was already in Cosmotune: Automix, Google Cast, playlist import, the sleep timer that ends with the song, community looping covers, explicit marks and the self-filling queue. What was taken is below, written for Cosmotune's own code. The lyrics credit rule adapts AETHER's (GPL-3.0, credited in `NOTICE.md` with AETHER's earlier work); the rest follows the ideas only.

| What | Learned from | Where |
| --- | --- | --- |
| A band whose own name has a comma or an ampersand ("Earth, Wind & Fire") is one artist in scrobbles, the desktop song matcher and the artist list under a song, as it already was in stats. More bands on the protected list. | LastWave Native `b432677b` | `ArtistIdentity.split` / `protectedRanges`; `PrimaryArtist.kt`, `DesktopScrobbleSettings.kt`, `DesktopTrackMatcher.kt`, `sharedUi/.../ArtistCredits.kt`, `shared/.../ArtistCredits.kt` |
| Writers' credits at the head of a lyrics file ("Andrew Watt/Ali Tamposi", "作词：…", "Lyrics by: …") are not shown as sung lines, from every source, as the title header already was for BetterLyrics. | AETHER 3.0.0 `LyricsFormatter.withoutHead` | `LyricTitleHeader.isCredit`; `LyricsRepository.result` |
| An album YouTube Music files by its music videos plays as its songs: each video row is swapped for its song by the same strict match as the manual audio switch, with a short wait only for such albums. | AETHER 3.0.0 `SongVersions` (itself after BitChord) | `YtMusicRepository.withSongsForVideos` |
| Pause on mute plays the song on when the volume comes back up, at 10 % at least, unless the listener played or paused in between. | LastWave Native `ZeroVolumePlaybackObserver` (`2b3fb1dc`, `abba78d8`) | `MuteResume`; `PlaybackService` volume observer; `DesktopAudioEngine.setVolume` |
| The phone's notification carries Like and Repeat (off, all, one); Shuffle stands in when Like can't be used. | LastWave Native, "stateful Repeat in the notification" | `NotificationButtons`; `PlaybackService.notificationButtons` |
| Your own cover for a playlist you made: one of its song covers or a photo, kept on the device and in the Cosmotune account; "Use the automatic cover" undoes it. | Lokal, "Edit playlist cover" | `data/covers`, `SyncPlaylistCovers`, `ui/covers` |
| In word-timed lyrics the sung word's letters rise one after another (the last starts at 65 % of its rise); joined scripts lift whole. | LastWave Native, "letter-level lift" | `LyricLine.letterLift`, `LetterLift.cuts`, `WordLifter.liftEach` |
| Manga sources: Kotatsu parsers `a90648aebd` → `9f1b46a9e0` (Shinigami's new site API, KomikTap fix, SoulScans and AinzScans migrated, Naver Webtoon and LoneSeal added). Adult content types and the source-name filter still apply. | Kotatsu-Redo | `manga/build.gradle.kts`, `parser-bundles.yml`, `NOTICE.md` |

Not taken: AETHER's KuGou matching (Cosmotune no longer asks KuGou), its Cast receiver page, transfer screens and motion work; LastWave's beat-synced background, menu and stats redesigns, backup changes; Lokal's recap artwork; Mihon and Tsuki maintenance; Mornye Extension's provider fixes (add-ons Cosmotune does not use). Bold non-Latin lyric lines were not needed: Cosmotune's Tamil lines already come out bold.

The signed Manga parser bundle for the new revision is published separately, by running the "Signed Manga parser bundles" workflow; until then installed apps keep the parsers they were built with.
