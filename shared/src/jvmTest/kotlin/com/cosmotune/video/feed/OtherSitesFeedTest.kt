package com.cosmotune.video.feed

import com.cosmotune.video.model.VideoItem
import kotlin.test.Test
import kotlin.test.assertEquals

class OtherSitesFeedTest {
    private fun v(id: String, at: Long?) = VideoItem(id, id, null, null, null, null, null, null, at, null)

    @Test fun otherSitesJoinByDateAndTheFeedKeepsItsOrder() {
        val feed = listOf(v("a", 300), v("b", null), v("c", 100))
        val merged = OtherSitesFeed.merge(feed, listOf(v("p1", 200), v("p2", 400), v("p3", 50)))
        assertEquals(listOf("p2", "a", "b", "p1", "c", "p3"), merged.map { it.id })
    }

    @Test fun nothingElseChangesWithoutOtherSites() {
        val feed = listOf(v("a", 1), v("b", 2))
        assertEquals(feed, OtherSitesFeed.merge(feed, emptyList()))
        assertEquals(listOf("a", "b"), OtherSitesFeed.merge(feed, listOf(v("a", 5))).map { it.id })
    }
}
