package com.cosmotune.watch

import com.cosmotune.watch.model.*
import kotlin.test.Test
import kotlin.test.assertEquals

class ContinueCutoffTest {
    private val day = 86_400_000L
    private val now = 400 * day
    private fun progress(id: Int, daysAgo: Long) = WatchProgress(WatchKey.tmdbFilm(id), 60_000, 6_000_000, now - daysAgo * day)

    @Test fun noLimitKeepsEverything() {
        val all = listOf(progress(1, 1), progress(2, 300))
        assertEquals(all, WatchContinueCutoff.keep(all, now, 0))
    }

    @Test fun titlesLeftLongerAgoThanTheLimitLeaveContinueWatching() {
        val kept = WatchContinueCutoff.keep(listOf(progress(1, 1), progress(2, 29), progress(3, 31), progress(4, 200)), now, 30)
        assertEquals(listOf(WatchKey.tmdbFilm(1), WatchKey.tmdbFilm(2)), kept.map { it.key })
    }

    @Test fun theChoicesAreWrittenPlainly() {
        assertEquals(listOf("Everything", "Last 30 days", "Last 3 months", "Last year"), WatchContinueCutoff.choices.map { WatchContinueCutoff.label(it) })
    }
}
