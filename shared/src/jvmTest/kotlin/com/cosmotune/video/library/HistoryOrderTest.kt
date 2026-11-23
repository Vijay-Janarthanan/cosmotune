package com.cosmotune.video.library

import com.cosmotune.video.model.VideoItem
import kotlin.test.Test
import kotlin.test.assertEquals

class HistoryOrderTest {
    private val day = 24 * 60 * 60 * 1000L
    private val today = 100 * day
    private fun entry(id: String, title: String, at: Long, count: Int) =
        HistoryEntry(VideoItem(id, title, null, null, null, null, null, null, null, null), at, 0, 0, count)

    private val entries = listOf(entry("a", "Beta", today + 10, 1), entry("b", "alpha", today - 3 * day, 5), entry("c", "Gamma", today - 20 * day, 2))

    @Test fun sortsAsAsked() {
        assertEquals(listOf("a", "b", "c"), HistoryOrder.apply(entries, HistorySort.NEWEST, HistoryRange.ANY, today).map { it.item.id })
        assertEquals(listOf("c", "b", "a"), HistoryOrder.apply(entries, HistorySort.OLDEST, HistoryRange.ANY, today).map { it.item.id })
        assertEquals(listOf("b", "a", "c"), HistoryOrder.apply(entries, HistorySort.NAME, HistoryRange.ANY, today).map { it.item.id })
        assertEquals(listOf("b", "c", "a"), HistoryOrder.apply(entries, HistorySort.MOST_PLAYED, HistoryRange.ANY, today).map { it.item.id })
        assertEquals(listOf("a", "c", "b"), HistoryOrder.apply(entries, HistorySort.LEAST_PLAYED, HistoryRange.ANY, today).map { it.item.id })
    }

    @Test fun keepsTheRange() {
        assertEquals(listOf("a"), HistoryOrder.apply(entries, HistorySort.NEWEST, HistoryRange.TODAY, today).map { it.item.id })
        assertEquals(listOf("a", "b"), HistoryOrder.apply(entries, HistorySort.NEWEST, HistoryRange.WEEK, today).map { it.item.id })
        assertEquals(3, HistoryOrder.apply(entries, HistorySort.NEWEST, HistoryRange.MONTH, today).size)
    }
}
