package com.cosmotune.video.feed

import com.cosmotune.video.model.VideoItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UploadAlertsTest {
    private fun item(id: String, channel: String, at: Long, short: Boolean = false) =
        VideoItem(id, "t$id", channel, "C$channel", null, null, null, null, at, null, isShort = short)

    @Test fun theFirstCheckOnlySetsTheMark() {
        val due = UploadAlerts.due(listOf(item("a", "UC1", 500)), setOf("UC1"), seenUntilMs = 0, nowMs = 1_000)
        assertTrue(due.items.isEmpty())
        assertEquals(1_000, due.seenUntilMs)
    }

    @Test fun onlyNewUploadsOfBelledChannelsAreTold() {
        val feed = listOf(item("old", "UC1", 900), item("new", "UC1", 1_500), item("other", "UC2", 1_600), item("s", "UC1", 1_700, short = true))
        val due = UploadAlerts.due(feed, setOf("UC1"), seenUntilMs = 1_000, nowMs = 2_000)
        assertEquals(listOf("new"), due.items.map { it.id })
        assertEquals(1_500, due.seenUntilMs)
        assertTrue(UploadAlerts.due(feed, setOf("UC1"), due.seenUntilMs, 3_000).items.isEmpty(), "told once")
    }

    @Test fun theTextNamesOneVideoOrCountsSeveral() {
        assertEquals("CUC1" to "ta", UploadAlerts.text(listOf(item("a", "UC1", 1))))
        assertEquals("2 new videos" to "CUC1, CUC2", UploadAlerts.text(listOf(item("a", "UC1", 1), item("b", "UC2", 2))))
    }
}
