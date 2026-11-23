package com.cosmotune.watch

import com.cosmotune.watch.model.TitleSummary
import com.cosmotune.watch.model.TitleType
import com.cosmotune.watch.model.WatchKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Watch search's rules: year ranges, genre and language matching, who is asked and left out, what is kept, and the order. */
class WatchQueryTest {
    private fun t(id: Int, name: String, year: Int? = null, rating: Double? = null, genres: List<String> = emptyList(), lang: String? = null,
        type: TitleType = TitleType.FILM) =
        TitleSummary(if (type == TitleType.FILM) WatchKey.tmdbFilm(id) else WatchKey.tmdbSeries(id), type, name, year, rating = rating, genres = genres, originalLanguage = lang)

    @Test fun yearChipsAreRangesAgainstTheCurrentYear() {
        assertEquals(2026..2026, WatchYears.THIS_YEAR.range(2026))
        assertEquals(2025..2025, WatchYears.LAST_YEAR.range(2026))
        assertEquals(2010..2019, WatchYears.TEENS.range(2026))
        assertEquals(2000..2009, WatchYears.NOUGHTIES.range(2026))
        assertEquals(1990..1999, WatchYears.NINETIES.range(2026))
        assertTrue(1989 in WatchYears.OLDER.range(2026) && 1922 in WatchYears.OLDER.range(2026) && 1990 !in WatchYears.OLDER.range(2026))
        assertEquals("2026", WatchYears.THIS_YEAR.name(2026))
        assertEquals("1990s", WatchYears.NINETIES.name(2026))
    }

    @Test fun genresMatchHoweverASourceSpellsThem() {
        assertTrue(WatchGenre.SCI_FI.matches(listOf("Science Fiction")))
        assertTrue(WatchGenre.SCI_FI.matches(listOf("Sci-Fi & Fantasy")))
        assertTrue(WatchGenre.ACTION.matches(listOf("action")))
        assertFalse(WatchGenre.ACTION.matches(listOf("Drama", "Comedy")))
        assertEquals("Sci-Fi", WatchGenre.SCI_FI.optionIn(listOf("Action", "Sci-Fi", "Drama")), "the add-on's own spelling is sent")
        assertEquals("Science Fiction", WatchGenre.SCI_FI.optionIn(listOf("Science Fiction")))
        assertNull(WatchGenre.WESTERN.optionIn(listOf("Action", "Drama")))
        assertEquals(16, WatchGenre.entries.size, "the board's fixed list")
    }

    @Test fun languagesMatchTwoAndThreeLetterCodes() {
        assertTrue(WatchLanguage.TAMIL.matches("ta"))
        assertTrue(WatchLanguage.TAMIL.matches("tam"))
        assertFalse(WatchLanguage.TAMIL.matches("hi"))
        assertFalse(WatchLanguage.TAMIL.matches(null))
    }

    @Test fun sourcesThatCannotApplyAFilterAreLeftOutAndCounted() {
        val q = WatchQuery("batman", genre = WatchGenre.ACTION, language = WatchLanguage.ENGLISH)
        val abilities = linkedMapOf(
            "meta" to QueryAbility(true, genre = FilterSupport.CHECKED, language = FilterSupport.CHECKED),
            "st-a" to QueryAbility(true, genre = FilterSupport.SENT),
            "ia" to QueryAbility(true),
            "pluto" to QueryAbility(true, genre = FilterSupport.CHECKED),
            "iptv" to QueryAbility.SILENT,
        )
        val (asked, leftOut) = WatchSearchRules.plan(q, abilities)
        assertEquals(listOf("meta"), asked)
        assertEquals(mapOf(WatchFilter.GENRE to 1, WatchFilter.LANGUAGE to 2), leftOut, "each by the first filter it cannot apply; a silent source is not counted")
        val found = WatchFound(q, emptyList(), leftOut = leftOut)
        assertEquals("1 source can't filter by genre, 2 by language", found.leftOutNote)
        assertEquals("2 sources can't filter by genre", WatchFound(q, emptyList(), leftOut = mapOf(WatchFilter.GENRE to 2)).leftOutNote)
        assertNull(WatchFound(q, emptyList()).leftOutNote)
    }

    @Test fun theSourceChipNarrowsWhoIsAskedAndLiveTvAsksNoTitleSource() {
        val abilities = mapOf("meta" to QueryAbility(true), "jf" to QueryAbility(true))
        assertEquals(listOf("jf"), WatchSearchRules.plan(WatchQuery("x", sources = setOf("jf")), abilities).first)
        assertTrue(WatchSearchRules.plan(WatchQuery("x", kind = WatchKind.LIVE), abilities).first.isEmpty())
        assertEquals(listOf("meta", "jf"), WatchSearchRules.plan(WatchQuery("x"), abilities).first, "no filters: every source")
    }

    @Test fun resultsAreCheckedHereToo() {
        val q = WatchQuery("x", kind = WatchKind.FILMS, genre = WatchGenre.DRAMA, years = WatchYears.TEENS)
        val checked = QueryAbility(true, genre = FilterSupport.CHECKED)
        val sent = QueryAbility(true, genre = FilterSupport.SENT, year = FilterSupport.SENT)
        assertTrue(WatchSearchRules.keeps(t(1, "A", 2015, genres = listOf("Drama")), q, checked, 2026))
        assertFalse(WatchSearchRules.keeps(t(2, "B", 2015, genres = listOf("Comedy")), q, sent, 2026), "a genre it carries is always checked")
        assertFalse(WatchSearchRules.keeps(t(3, "C", 2015), q, checked, 2026), "no genre given and the source did not filter: out")
        assertTrue(WatchSearchRules.keeps(t(4, "D", null), q, sent, 2026), "the source filtered: kept")
        assertFalse(WatchSearchRules.keeps(t(5, "E", 2021, genres = listOf("Drama")), q, sent, 2026), "outside the years")
        assertFalse(WatchSearchRules.keeps(t(6, "F", 2015, genres = listOf("Drama"), type = TitleType.SERIES), q, checked, 2026), "a series under Films")
        val lang = WatchQuery("", language = WatchLanguage.TAMIL)
        assertTrue(WatchSearchRules.keeps(t(7, "G", lang = "ta"), lang, QueryAbility(true, language = FilterSupport.CHECKED), 2026))
        assertFalse(WatchSearchRules.keeps(t(8, "H", lang = "hi"), lang, QueryAbility(true, language = FilterSupport.SENT), 2026))
        val anime = WatchQuery("", kind = WatchKind.ANIME)
        assertTrue(WatchSearchRules.keeps(t(9, "I", genres = listOf("Animation"), lang = "ja"), anime, QueryAbility(true, anime = FilterSupport.CHECKED), 2026))
        assertFalse(WatchSearchRules.keeps(t(10, "J", genres = listOf("Animation"), lang = "en"), anime, QueryAbility(true, anime = FilterSupport.CHECKED), 2026), "an English cartoon is no anime")
        assertTrue(WatchSearchRules.keeps(t(11, "K", genres = listOf("Action")), anime, QueryAbility(true, anime = FilterSupport.SENT), 2026), "an anime add-on's list is trusted")
    }

    @Test fun sortOrders() {
        val items = listOf(t(1, "Batman Begins", 2005, 8.2), t(2, "The Lego Batman Movie", 2017, 7.3), t(3, "Batman", 1989, null), t(4, "Alpha", null, 9.0))
        assertEquals(listOf("Batman", "Batman Begins", "The Lego Batman Movie", "Alpha"), WatchSearchRules.sort(items, WatchSort.BEST, "batman").map { it.name })
        assertEquals(listOf("The Lego Batman Movie", "Batman Begins", "Batman", "Alpha"), WatchSearchRules.sort(items, WatchSort.NEWEST, "").map { it.name })
        assertEquals(listOf("Alpha", "Batman Begins", "The Lego Batman Movie", "Batman"), WatchSearchRules.sort(items, WatchSort.RATED, "").map { it.name })
        assertEquals(listOf("Alpha", "Batman", "Batman Begins", "The Lego Batman Movie"), WatchSearchRules.sort(items, WatchSort.NAME, "").map { it.name }, "an article is ignored")
        assertEquals(items, WatchSearchRules.sort(items, WatchSort.BEST, ""), "browsing keeps the sources' popularity order")
    }

    @Test fun answersInterleaveAndEachTitleComesOnce() {
        val a = listOf(t(1, "A1"), t(2, "A2"), t(3, "A3"))
        val b = listOf(t(2, "B dup"), t(4, "B2"))
        assertEquals(listOf("A1", "B dup", "B2", "A3"), WatchSearchRules.merge(listOf(a, b)).map { it.name })
    }

    @Test fun theCountSaysWhatWasFound() {
        val q = WatchQuery("x")
        assertEquals("2 films", WatchFound(q, listOf(t(1, "A"), t(2, "B"))).countLabel)
        assertEquals("1 film", WatchFound(q, listOf(t(1, "A"))).countLabel)
        assertEquals("1 series", WatchFound(q, listOf(t(1, "A", type = TitleType.SERIES))).countLabel)
        assertEquals("2 results", WatchFound(q, listOf(t(1, "A"), t(2, "B", type = TitleType.SERIES))).countLabel)
        assertTrue(WatchFound(q, emptyList(), failed = 2).error)
        assertFalse(WatchFound(q, emptyList(), failed = 1, answered = 1).error, "one source answered: just no results")
    }
}
