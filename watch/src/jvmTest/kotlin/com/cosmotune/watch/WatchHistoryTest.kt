package com.cosmotune.watch

import com.cosmotune.watch.model.WatchKey
import com.cosmotune.watch.model.WatchProgress
import kotlin.test.Test
import kotlin.test.assertEquals

class WatchHistoryTest {
    private val day = 86_400_000L
    private val film = WatchProgress(WatchKey.tmdbFilm(603), 100_000, 8_000_000, 10 * day + 5)
    private val finished = WatchProgress(WatchKey("e:tmdb:1399:1:1"), 3_500_000, 3_600_000, 9 * day + 7)
    private val older = WatchProgress(WatchKey("e:tmdb:1399:1:2"), 60_000, 3_600_000, 3 * day)

    @Test fun filtersAndGroupsByDayNewestFirst() {
        val all = listOf(older, film, finished)
        assertEquals(listOf(film, finished, older), WatchHistory.filter(all, HistoryFilter.ALL))
        assertEquals(listOf(film), WatchHistory.filter(all, HistoryFilter.FILMS))
        assertEquals(listOf(finished, older), WatchHistory.filter(all, HistoryFilter.EPISODES))
        assertEquals(listOf(finished), WatchHistory.filter(all, HistoryFilter.FINISHED))
        assertEquals(listOf(film, older), WatchHistory.filter(all, HistoryFilter.UNFINISHED))
        val days = WatchHistory.byDay(WatchHistory.filter(all, HistoryFilter.ALL), 10, { it / day }) { "Day $it" }
        assertEquals(listOf("Today", "Yesterday", "Day 3"), days.map { it.first })
    }
}
