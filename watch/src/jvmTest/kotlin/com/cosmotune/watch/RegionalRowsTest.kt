package com.cosmotune.watch

import com.cosmotune.watch.meta.TmdbMetadata
import com.cosmotune.watch.model.TitleType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RegionalRowsTest {
    @Test fun eachHomeLanguageGetsFilmAndSeriesRows() {
        val titles = TmdbMetadata.rowDefs(null, listOf("ta", "te")).map { it.title }
        listOf("Top 10 · Tamil films", "Tamil films", "Top 10 · Tamil series", "Tamil web series", "Tamil daily soaps",
            "Top 10 · Telugu films", "Telugu daily soaps").forEach { assertTrue(it in titles, "missing $it in $titles") }
    }

    @Test fun filmsOnlyLeavesSeriesRowsOut() {
        val ids = TmdbMetadata.rowDefs(TitleType.FILM, listOf("ta")).map { it.id }
        assertTrue("tmdb:regional10:film:ta" in ids)
        assertFalse(ids.any { it.contains(":series") })
    }

    @Test fun noLanguagesMeansNoRegionalRows() {
        assertFalse(TmdbMetadata.rowDefs(null, emptyList()).any { it.id.contains(":regional10:") || it.id.contains(":lang:") })
    }

    @Test fun dailySoapsAndWebSeriesAreKeptApart() {
        val (soapPath, soap) = TmdbMetadata.request("tmdb:soap:series:ta", 1, "IN")!!
        assertEquals("/discover/tv", soapPath)
        assertEquals("10766", soap["with_genres"])
        assertEquals("ta", soap["with_original_language"])
        val (_, web) = TmdbMetadata.request("tmdb:web:series:ta", 1, "IN")!!
        assertEquals("10766", web["without_genres"])
        assertEquals("4|2", web["with_type"])
        assertEquals("ta", web["with_original_language"])
    }

    @Test fun scriptedSeriesAskForScriptedShowsNotDocumentaries() {
        val (_, scripted) = TmdbMetadata.request("tmdb:scripted:series", 1, "IN")!!
        assertEquals("4|2", scripted["with_type"])
    }

    @Test fun regionalTopTenIsByPopularityInThatLanguage() {
        val (path, top) = TmdbMetadata.request("tmdb:regional10:series:ta", 2, "IN")!!
        assertEquals("/discover/tv", path)
        assertEquals("2", top["page"])
        assertEquals("ta", top["with_original_language"])
        assertEquals("popularity.desc", top["sort_by"])
    }

    @Test fun unknownRowsAskForNothing() {
        assertEquals(null, TmdbMetadata.request("tmdb:nonsense:film", 1, "IN"))
        assertEquals(null, TmdbMetadata.request("cinemeta:movie/top", 1, "IN"))
    }

    @Test fun homeLanguagesDefaultToTamilAndHindi() {
        assertEquals(listOf("ta", "hi"), WatchPreferences().homeLanguages)
    }
}
