package com.cosmotune.watch

import com.cosmotune.watch.model.*
import kotlin.test.*

class WatchSearchFilterTest {
    private val drama = TitleSummary(WatchKey.tmdbFilm(1), TitleType.FILM, "Ember Road", 2021, genres = listOf("Drama"))
    private val comedy = TitleSummary(WatchKey.tmdbFilm(2), TitleType.FILM, "Low Tide", 2019, genres = listOf("Comedy"))
    private val show = TitleSummary(WatchKey.tmdbSeries(3), TitleType.SERIES, "Harbour Town", 2021, genres = listOf("drama"))
    private val results = SearchResults("t", listOf(drama, comedy), listOf(show), emptyList())

    @Test fun typeGenreAndYearNarrowTheFetchedResults() {
        assertEquals(results, WatchSearchFilter().apply(results))
        assertEquals(listOf(drama), WatchSearchFilter(type = TitleType.FILM, genre = "drama").apply(results).films)
        assertTrue(WatchSearchFilter(type = TitleType.FILM).apply(results).series.isEmpty())
        val year = WatchSearchFilter(year = 2021).apply(results)
        assertEquals(listOf(drama), year.films)
        assertEquals(listOf(show), year.series)
        assertTrue(WatchSearchFilter(genre = "Horror").apply(results).isEmpty)
    }
}
