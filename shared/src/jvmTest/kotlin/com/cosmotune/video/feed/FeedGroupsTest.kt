package com.cosmotune.video.feed

import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.settings.VideoSettings
import com.cosmotune.video.library.LibraryCodec
import kotlin.test.Test
import kotlin.test.assertEquals

class FeedGroupsTest {
    private fun item(id: String, channel: String?) = VideoItem(id, id, channel, null, null, null, null, null, null, null)

    @Test fun aGroupKeepsOnlyItsChannelsVideos() {
        val items = listOf(item("a", "UC1"), item("b", "UC2"), item("c", null))
        val group = FeedChannelGroup("g1", "News", setOf("UC2"))
        assertEquals(listOf("b"), FeedGroups.itemsOf(items, group).map { it.id })
        assertEquals(items, FeedGroups.itemsOf(items, null))
    }

    @Test fun savingReplacesByIdAndSkipsBlankNames() {
        val first = FeedGroups.save(emptyList(), FeedChannelGroup("g1", "  News  ", setOf("UC1")))
        assertEquals("News", first.single().name)
        val renamed = FeedGroups.save(first, FeedChannelGroup("g1", "World", setOf("UC1", "UC2")))
        assertEquals(listOf("World"), renamed.map { it.name })
        assertEquals(renamed, FeedGroups.save(renamed, FeedChannelGroup("g2", " ", emptySet())))
        assertEquals(emptyList(), FeedGroups.delete(renamed, "g1"))
    }

    @Test fun newIdsDoNotRepeat() {
        val groups = listOf(FeedChannelGroup("g5", "a"))
        assertEquals("g6", FeedGroups.newId(groups, 5))
    }

    @Test fun groupsSurviveTheSettingsFile() {
        val settings = VideoSettings(feedGroups = listOf(FeedChannelGroup("g1", "Cooking", setOf("UCx"))))
        val back = LibraryCodec.decodeSettings(LibraryCodec.encodeSettings(settings))
        assertEquals(settings.feedGroups, back.feedGroups)
    }
}
