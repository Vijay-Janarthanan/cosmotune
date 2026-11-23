package com.cosmotune.ui.watch

import com.cosmotune.watch.FakeWatchRepository
import com.cosmotune.watch.model.WatchKey
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchRandomEpisodeTest {
    @Test fun aRandomEpisodeComesFromANumberedSeasonOfTheSeries() = runBlocking<Unit> {
        val repository = FakeWatchRepository()
        val page = assertNotNull(repository.title(WatchKey.tmdbSeries(101)))
        val picks = (1..12).mapNotNull { randomEpisode(page, repository, Random(it)) }
        assertEquals(12, picks.size)
        assertTrue(picks.all { it.seriesKey == page.key && it.season > 0 })
        assertTrue(picks.map { it.key }.toSet().size > 1, "the picks differ")
    }

    @Test fun aSeriesWithNoSeasonsHasNoRandomEpisode() = runBlocking<Unit> {
        val repository = FakeWatchRepository()
        val page = assertNotNull(repository.title(WatchKey.tmdbSeries(101)))
        assertNull(randomEpisode(page.copy(seasons = emptyList()), repository))
    }
}
