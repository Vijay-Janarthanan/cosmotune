package com.cosmotune.listening

import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadHistoryTest {
    private val day = 86_400_000L
    private val now = 1_763_900_000_000L // 23 Nov 2025, UTC

    @Test fun newestFirstOneRowPerSongAndKeptAcrossRestarts() {
        var history = emptyList<DownloadHistory.Entry>()
        history = DownloadHistory.add(history, DownloadHistory.Entry("a", "First", "Singer", now - 2 * day, failed = "No copy found"))
        history = DownloadHistory.add(history, DownloadHistory.Entry("b", "Second", "Singer", now - day))
        history = DownloadHistory.add(history, DownloadHistory.Entry("a", "First", "Singer", now))
        assertEquals(listOf("a", "b"), history.map { it.trackId })
        assertEquals(null, history.first().failed) // the retry that worked replaced the failure
        assertEquals(history, DownloadHistory.decode(DownloadHistory.encode(history)))
        assertEquals(emptyList(), DownloadHistory.decode("not json"))
        repeat(150) { history = DownloadHistory.add(history, DownloadHistory.Entry("x$it", "T", "", now)) }
        assertEquals(DownloadHistory.MAX, history.size)
    }

    @Test fun daysAndBatchLinesReadPlainly() {
        val utc = ZoneOffset.UTC
        assertEquals("Today", DownloadHistory.dayLabel(now, now, utc))
        assertEquals("Yesterday", DownloadHistory.dayLabel(now - day, now, utc))
        assertEquals("20 Nov", DownloadHistory.dayLabel(now - 3 * day, now, utc))
        assertEquals("23 Nov 2024", DownloadHistory.dayLabel(now - 365 * day, now, utc))
        val grouped = DownloadHistory.byDay(listOf(DownloadHistory.Entry("a", "A", "", now), DownloadHistory.Entry("b", "B", "", now - day)), now, utc)
        assertEquals(listOf("Today", "Yesterday"), grouped.map { it.first })
        assertEquals("12 songs downloaded", DownloadHistory.batchSummary(12, 0))
        assertEquals("1 song downloaded, 1 failed", DownloadHistory.batchSummary(1, 1))
        assertEquals("2 songs could not be downloaded", DownloadHistory.batchSummary(0, 2))
        assertEquals(null, DownloadHistory.batchSummary(0, 0))
    }
}
