package com.cosmotune.design

import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The meta line of an album, single, EP or playlist page and the rules around it, each one the old `DetailScreen.kt` rule kept:
 * `headerLines` (the subtitle taken apart), `playtime` (the sum of the durations), `looksLikeCredit`, `topRelease` and `releaseYear`.
 */
class ReleaseMetaTest {

    // ---- the subtitle (headerLines) ----

    @Test
    fun aBrowseSubtitleIsTakenApartIntoKindYearAndCredit() {
        val s = ReleaseMeta.parse("Album • Travis Scott • 2023")
        assertEquals("Album", s.kindWord)
        assertEquals("2023", s.year)
        assertEquals("Travis Scott", s.credit)
    }

    @Test
    fun theMiddleDotSeparatesToo() {
        val s = ReleaseMeta.parse("Single · Juno Saltmarsh · 2026")
        assertEquals("Single", s.kindWord)
        assertEquals("2026", s.year)
        assertEquals("Juno Saltmarsh", s.credit)
    }

    @Test
    fun aBareNameIsTheCreditAndNothingElse() {
        val s = ReleaseMeta.parse("Travis Scott")
        assertNull(s.kindWord)
        assertNull(s.year)
        assertEquals("Travis Scott", s.credit)
    }

    @Test
    fun severalCreditsAreJoinedWithACommaInTheirOrder() {
        val s = ReleaseMeta.parse("Album • Okra Moon • Ritu • 2024")
        assertEquals("Okra Moon, Ritu", s.credit)
    }

    @Test
    fun theLastFourDigitPartIsTheYearAndOnlyWholeFourDigitParts() {
        assertEquals("2019", ReleaseMeta.parse("Album • 1999 • Remastered • 2019").year)
        assertNull(ReleaseMeta.parse("Album • Blink 182").year)
        assertNull(ReleaseMeta.parse("Album • 20245").year)
        // a year that is also the name: it is the year, so it is not the credit
        assertEquals("", ReleaseMeta.parse("Album • 1989").credit)
    }

    @Test
    fun kindWordsAreMatchedWithoutCaseAndLeaveTheCredit() {
        assertEquals("EP", ReleaseMeta.parse("EP • Okra Moon • 2022").kindWord)
        assertEquals("podcast", ReleaseMeta.parse("podcast • Some Host").kindWord)
        assertEquals("Some Host", ReleaseMeta.parse("podcast • Some Host").credit)
    }

    @Test
    fun blankPartsAndAnEmptySubtitleGiveNothing() {
        assertEquals(ReleaseMeta.Subtitle(null, null, ""), ReleaseMeta.parse(""))
        assertEquals("Juno", ReleaseMeta.parse(" • Juno •  • ").credit)
    }

    // ---- which of the four words ----

    @Test
    fun theWordOfTheSubtitleDecidesTheKind() {
        assertEquals(ReleaseMeta.Kind.SINGLE, ReleaseMeta.kind("Single • Juno • 2024", BrowseType.ALBUM))
        assertEquals(ReleaseMeta.Kind.EP, ReleaseMeta.kind("ep • Juno", BrowseType.ALBUM))
        assertEquals(ReleaseMeta.Kind.PLAYLIST, ReleaseMeta.kind("Playlist • Vijay", BrowseType.PLAYLIST))
        assertEquals(ReleaseMeta.Kind.ALBUM, ReleaseMeta.kind("Album • Juno", BrowseType.OTHER))
    }

    @Test
    fun withoutAWordTheTypeOfThePageDecides() {
        assertEquals(ReleaseMeta.Kind.ALBUM, ReleaseMeta.kind("Juno • 2024", BrowseType.ALBUM))
        assertEquals(ReleaseMeta.Kind.PLAYLIST, ReleaseMeta.kind("Vijay", BrowseType.PLAYLIST))
        assertNull(ReleaseMeta.kind("Vijay", BrowseType.OTHER))
        assertNull(ReleaseMeta.kind("Vijay", BrowseType.ARTIST))
    }

    @Test
    fun aKindWordThatIsNotOneOfTheFourFallsBackToTheType() {
        assertEquals(ReleaseMeta.Kind.ALBUM, ReleaseMeta.kind("Podcast • Some Host", BrowseType.ALBUM))
        assertNull(ReleaseMeta.kind("Podcast • Some Host", BrowseType.OTHER))
    }

    // ---- the playtime (playtime) ----

    @Test
    fun theDurationsAreSummedAndFlooredToWholeMinutes() {
        // 11 songs of 3:45 = 41 min 15 s
        val durations = List(11) { "3:45" }
        assertEquals(ReleaseMeta.Playtime(hours = 0, minutes = 41), ReleaseMeta.playtime(durations))
    }

    @Test
    fun theSumIsFlooredOnceNotPerSong() {
        // three songs of 0:30 are 1 min 30 s, not 0
        assertEquals(ReleaseMeta.Playtime(0, 1), ReleaseMeta.playtime(listOf("0:30", "0:30", "0:30")))
    }

    @Test
    fun anHourOrMoreGivesHoursAndMinutes() {
        val durations = List(38) { "3:58" } // 38 x 238 s = 9044 s = 2 h 30 min 44 s
        assertEquals(ReleaseMeta.Playtime(2, 30), ReleaseMeta.playtime(durations))
        assertEquals(ReleaseMeta.Playtime(2, 31), ReleaseMeta.playtime(listOf("1:10:00", "1:21:30")))
        assertEquals(ReleaseMeta.Playtime(1, 0), ReleaseMeta.playtime(listOf("1:00:00")))
    }

    @Test
    fun hmmssIsRead() {
        assertEquals(ReleaseMeta.Playtime(1, 5), ReleaseMeta.playtime(listOf("1:05:09")))
    }

    @Test
    fun songsWithNoDurationOrAnUnreadableOneAddNothing() {
        assertEquals(ReleaseMeta.Playtime(0, 7), ReleaseMeta.playtime(listOf("3:30", null, "", "oops", "3:30", "1:2:3:4")))
    }

    @Test
    fun underAMinuteOrNothingKnownIsNoPlaytime() {
        assertNull(ReleaseMeta.playtime(emptyList()))
        assertNull(ReleaseMeta.playtime(listOf(null, "")))
        assertNull(ReleaseMeta.playtime(listOf("0:45")))
    }

    // ---- what the page has ----

    @Test
    fun thePartsComeFromTheSubtitleAndTheSongs() {
        val parts = ReleaseMeta.parts("Album • Juno Saltmarsh • 2026", BrowseType.ALBUM, List(10) { "3:48" })
        assertEquals(ReleaseMeta.Kind.ALBUM, parts.kind)
        assertEquals("2026", parts.year)
        assertEquals(10, parts.songCount)
        assertEquals(ReleaseMeta.Playtime(0, 38), parts.playtime)
        assertEquals("Juno Saltmarsh", parts.credit)
    }

    @Test
    fun aPageStillLoadingHasASubtitleOnlyAndMissingPartsAreAbsent() {
        val parts = ReleaseMeta.parts("Playlist • Vijay", BrowseType.PLAYLIST, emptyList())
        assertEquals(ReleaseMeta.Kind.PLAYLIST, parts.kind)
        assertNull(parts.year)
        assertEquals(0, parts.songCount)
        assertNull(parts.playtime)
    }

    // ---- the line ----

    @Test
    fun theLineIsTypeYearSongsAndDurationAroundMiddleDots() {
        assertEquals("Album · 2026 · 10 songs · 38 min", ReleaseMeta.line("Album", "2026", "10 songs", "38 min"))
    }

    @Test
    fun missingPartsAreDroppedAndNoDotIsLeftOver() {
        assertEquals("2026 · 38 min", ReleaseMeta.line(null, "2026", null, "38 min"))
        assertEquals("Playlist · 64 songs", ReleaseMeta.line("Playlist", null, "64 songs", null))
        assertEquals("Album", ReleaseMeta.line("Album", null, null, null))
        assertEquals("", ReleaseMeta.line(null, null, null, null))
    }

    @Test
    fun blankPartsCountAsMissing() {
        assertEquals("2026 · 10 songs", ReleaseMeta.line("", "2026", "10 songs", "  "))
    }

    // ---- a name that is several artists (looksLikeCredit) ----

    @Test
    fun aTitleThatNamesSeveralArtistsLooksLikeACredit() {
        assertTrue(ReleaseMeta.looksLikeCredit("Okra Moon, Ritu"))
        assertTrue(ReleaseMeta.looksLikeCredit("Okra Moon & Ritu"))
        assertTrue(ReleaseMeta.looksLikeCredit("Okra Moon feat. Ritu"))
        assertTrue(ReleaseMeta.looksLikeCredit("Okra Moon FT Ritu"))
        assertTrue(ReleaseMeta.looksLikeCredit("Okra Moon x Ritu"))
        assertTrue(ReleaseMeta.looksLikeCredit("Okra Moon with Ritu"))
        assertTrue(ReleaseMeta.looksLikeCredit("Okra Moon and Ritu"))
    }

    @Test
    fun aSingleNameDoesNotLookLikeACredit() {
        assertEquals(false, ReleaseMeta.looksLikeCredit("Okra Moon"))
        assertEquals(false, ReleaseMeta.looksLikeCredit("AC/DC"))
        assertEquals(false, ReleaseMeta.looksLikeCredit("Anderson"))      // "and" inside a word
        assertEquals(false, ReleaseMeta.looksLikeCredit("Xavier"))        // an x with no spaces round it
        assertEquals(false, ReleaseMeta.looksLikeCredit(""))
    }

    // ---- the newest release of an artist (topRelease, releaseYear) ----

    private fun item(title: String, subtitle: String, browseId: String?) = ShelfItem(title, subtitle, null, videoId = null, browseId = browseId)

    @Test
    fun theYearIsTheFourDigitYearOfTheCardSubtitle() {
        assertEquals(2024, ReleaseMeta.releaseYear(item("a", "Album • 2024", "MPREb_1")))
        assertEquals(1999, ReleaseMeta.releaseYear(item("a", "Re-release 1999", "MPREb_1")))
        assertNull(ReleaseMeta.releaseYear(item("a", "Single", "MPREb_1")))
        assertNull(ReleaseMeta.releaseYear(item("a", "Album • 20245", "MPREb_1")))
        assertNull(ReleaseMeta.releaseYear(item("a", "Album • 1899", "MPREb_1")))
    }

    @Test
    fun theNewestReleaseIsTheOneWithTheLatestYear() {
        val sections = listOf(
            HomeShelf("Albums", listOf(item("old", "Album • 2019", "MPREb_old"), item("new", "Album • 2024", "MPREb_new"))),
            HomeShelf("Singles", listOf(item("mid", "Single • 2022", "MPREb_mid"))),
        )
        assertEquals("new", ReleaseMeta.topRelease(sections)?.title)
    }

    @Test
    fun aSingleNewerThanTheAlbumsWins() {
        val sections = listOf(
            HomeShelf("Albums", listOf(item("album", "Album • 2021", "MPREb_a"))),
            HomeShelf("Singles", listOf(item("single", "Single • 2025", "MPREb_s"))),
        )
        assertEquals("single", ReleaseMeta.topRelease(sections)?.title)
    }

    @Test
    fun aTieKeepsYouTubesOwnOrderWhichListsAlbumsFirst() {
        val sections = listOf(
            HomeShelf("Albums", listOf(item("first", "Album • 2024", "MPREb_1"))),
            HomeShelf("Singles", listOf(item("second", "Single • 2024", "MPREb_2"))),
        )
        assertEquals("first", ReleaseMeta.topRelease(sections)?.title)
    }

    @Test
    fun aReleaseWithAYearBeatsOneWithout() {
        val sections = listOf(HomeShelf("Albums", listOf(item("none", "Album", "MPREb_1"), item("dated", "Album • 2001", "MPREb_2"))))
        assertEquals("dated", ReleaseMeta.topRelease(sections)?.title)
    }

    @Test
    fun releasesWithNoYearKeepTheFirst() {
        val sections = listOf(HomeShelf("Albums", listOf(item("one", "Album", "MPREb_1"), item("two", "Album", "MPREb_2"))))
        assertEquals("one", ReleaseMeta.topRelease(sections)?.title)
    }

    @Test
    fun onlyAnMPREBrowseIdIsARelease() {
        val sections = listOf(
            HomeShelf(
                "Fans also like",
                listOf(
                    item("artist", "Artist • 2030", "UCabc"),
                    item("playlist", "Playlist • 2031", "VLPLabc"),
                    item("video", "Video • 2032", null),
                    item("album", "Album • 2020", "MPREb_x"),
                ),
            ),
        )
        assertEquals("album", ReleaseMeta.topRelease(sections)?.title)
    }

    @Test
    fun noReleaseIsNoTopRelease() {
        assertNull(ReleaseMeta.topRelease(emptyList()))
        assertNull(ReleaseMeta.topRelease(listOf(HomeShelf("Artists", listOf(item("a", "Artist", "UC1"))))))
    }
}
