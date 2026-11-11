# Lyrics sources, November 2026

Many lyric sources had stopped answering, and the owner's current song (a Tamil
film duet) showed only untimed lines from YouTube Music. Every source was
measured live, and the timed-lyric services that open-source players use were
checked. This file records what was kept, what was removed, and why.

No lyric text was recorded anywhere in this work. The probe stores only counts,
timestamps and flags.

## How it was measured

`shared/src/jvmTest/.../lyrics/LyricsSourcesLiveProbe.kt` calls each source's
real code path (`LyricsRepository.fetch`) for a fixed test set. It is off by
default; run it with `COSMOTUNE_LYRICS_PROBE=1` and
`--tests '*LyricsSourcesLiveProbe*' --rerun`. It writes
`shared/build/lyrics-probe/report.tsv` (or the name in
`COSMOTUNE_LYRICS_PROBE_OUT`).

The test set has 15 catalogue tracks, resolved through YouTube Music search,
plus 3 official music-video uploads:

| Group | Songs |
|---|---|
| English | 3 very popular pop and rock songs, 1 older indie song |
| Tamil | 2 Ilaiyaraaja-era songs, the owner's current song, 1 Anirudh song, 1 A.R. Rahman song |
| Other Indian | 1 Hindi, 1 Telugu, 1 Malayalam |
| K-pop / J-pop | 1 each |
| Instrumental | 1 track with no sung lyrics |
| Video uploads | 3 official music videos (English, Tamil, Hindi) |

For each source and song the probe records the outcome (hit, miss or error),
the latency, the line count, the timing type (none, line or word), and whether
the last line starts inside the track.

Probes ran on 11 November 2026 from the owner's network in India.

## Decision table

Hits are out of the 15 catalogue tracks. "Timed" means line-synced or
word-synced. No kept source had a line starting after the end of the track.

| Source | Decision | Hits (word / line / plain) | Median ms | Reason |
|---|---|---|---|---|
| lrc.red | **Keep** | 11 (8 / 3 / 0) | 24 | Apple Music syllable timing. The most word-timed hits. Its own search finds the recording without an ISRC. |
| BiniLyrics | Remove | 11 (8 / 3 / 0) | 24 | Same catalogue as lrc.red: identical hits, timing and line counts on every song. |
| BetterLyrics | **Keep** | 12 (6 / 6 / 0) | 268 | Only source with timing for 2 songs (a Tamil and a Malayalam song) before the YouTube change. Parsing untouched. |
| BetterLyrics Portato | **Keep** | 9 (8 / 0 / 1) | 242 | Upgrades 2 Indian songs from line to word timing. Word timing for the J-pop song, which lrc.red misses without an ISRC. One answer had every line at 0 s and one was a single stray line; the new checks in `LyricsRepository.pick` reject both. |
| PaxSenix | Remove | 5 (4 / 1 / 0) | 2596 | Every hit is also on lrc.red with the same timing. Slowest source. Its public proxy returned 502. |
| PaxSenix: Spotify | Remove | 0 | — | Needs a personal API key. Never hit. |
| PaxSenix: Musixmatch | Remove | 0 | — | Needs a personal API key. Never hit. |
| LyricsPlus | Remove | 7 (5 / 2 / 0) | 373 | Every hit is also on lrc.red. 5 of its 6 mirrors are dead (530, DNS failure, 404, 402, 402). |
| SimpMusic | Remove | 0 | 50 | HTTP 403. Already hidden. |
| Unison | Remove | 1 (1 / 0 / 0) | 54 | Its one hit is also on lrc.red. |
| YouTube transcript | Remove | 0 (0 of 3 videos too) | 115 | Never hit, not even on music videos. |
| **YouTube Music** | **Keep, now timed** | before 14 (0 / 0 / 14), after 14 (0 / 14 / 0) | 968 | Now asks for the Lyrics tab as the YouTube Music Android app does, which returns timed lines (see below). It is the only timed source for the owner's song. |
| Megalobiz | Remove | 0 | 985 | HTTP 410 Gone. Already hidden. |
| KuGou | Remove | 11 (0 / 11 / 0) | 1506 | Every hit is also on LRCLIB, with line timing. Slow. It gave the instrumental a junk single line, which the old ranking took as timed lyrics. |
| LRCLIB | **Keep** | 12 (0 / 12 / 0), plus 2 of 3 videos | 347 | Matches by name, so it still answers for music-video uploads and local files, where YouTube Music cannot (2 of 3 videos). |
| Musixmatch | Remove | 0 | 712 | Never hit. |
| Genius | Remove | 12 (0 / 0 / 12) | 2250 | Plain text only. YouTube Music's plain-text fallback answers more songs (14), so Genius is no longer the only plain fallback. It gave 47 lines for the instrumental track, which has no lyrics. |
| YouTube caption track | Remove | 0 (0 of 3 videos too) | 143 | Never hit. It also got the player response by posing as a VR headset client. |

Kept: lrc.red, BetterLyrics, BetterLyrics Portato, YouTube Music, LRCLIB.
Removed: 13 sources. Added: none.

## YouTube Music timed lyrics

The web client (`WEB_REMIX`) gets a Lyrics tab of plain text. The YouTube
Music Android client (`ANDROID_MUSIC`) gets a `timedLyricsModel` for the same
browse id. Its `lyricsData.timedLyricsData` entries each hold one line, with a
`cueRange` giving `startTimeMilliseconds` and `endTimeMilliseconds`. The
lyrics come from LyricFind, YouTube's licensed partner.

`YouTubeMusicLyrics` keeps its catalogue identity check on the web client. Then
it asks for the lyrics page as the Android client, anonymously. It falls back
to the web page's plain text when there is no timing.

Measured: 14 of 15 catalogue tracks timed, and every last line inside the
track. The instrumental track has no lyrics, as expected. This includes the
owner's song: 60 timed lines, ending at 294 s of a 303 s track.

## Ranking

`LyricsRepository.pick` now keeps plain text as a fallback only. A timed
answer from any source beats an untimed one, whatever the order and whichever
answers first. Before this, with "prioritize syllable lyrics" on, a plain
answer higher in the order blocked a line-timed one below it.

An answer counts as timed only if it has at least two lines with a real
timestamp and no line starting more than 5 s past the end of the track. An
answer with fewer than two lines of words is skipped.

## Candidates checked and not added

| Candidate | Result |
|---|---|
| Deezer (anonymous token plus GraphQL lyrics) | The lyrics query works by track id, but search is blocked from India: the public search is empty and GraphQL search fails with "Track media not found". Not reliable for the owner. |
| NetEase and QQ Music | TLS connection refused from this network. |
| lrc.cx (public LrcApi instance) | 13 of 15 timed, but it is backed by KuGou, and its own README warns it is not fully accurate. It added no song that BetterLyrics, LRCLIB and YouTube Music did not already time. |
| lyrics.ovh | Plain text only. |
| JioSaavn | Has lyrics for the owner's song, but plain text only. |
| Apple Music TTML proxies | lrc.red and BetterLyrics are kept. BiniLyrics, PaxSenix and LyricsPlus serve the same Apple documents, so they are removed. |
| Musixmatch richsync (desktop user-token flow) | The removed Musixmatch source already used the token-and-signature flow and never hit. Going further would mean getting past its bot checks, which we don't do. |

## Before and after

What the player shows, with the default order: the song counts as "timed"
when the winning answer is line-synced or word-synced and inside the track.

| Group | Before | After |
|---|---|---|
| English | 4 / 4 timed | 4 / 4 timed (all word-synced) |
| Tamil | 4 / 5 timed (the owner's song plain only) | 5 / 5 timed |
| Other Indian | 3 / 3 timed | 3 / 3 timed |
| K-pop / J-pop | 2 / 2 timed | 2 / 2 timed |
| Instrumental | 1 junk "timed" single line | no lyrics (correct) |
| Video uploads | not measured | 2 / 3 timed |

YouTube Music on its own went from 0 of 15 timed to 14 of 15 timed.

## Saved settings

Saved choices are read by name, so a removed source drops out quietly. If a
saved choice named only removed sources, every source is switched back on
(`LyricsSource.enabled`, and the same rule in `DesktopPersistence`), because
the last source can never be unticked by hand. A stored PaxSenix API key is
wiped on start, and the key button is gone from the Lyrics sources dialog.
