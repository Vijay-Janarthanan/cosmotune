package com.cosmotune.video.importers

import com.cosmotune.video.importers.ImportFixtures.C0
import com.cosmotune.video.importers.ImportFixtures.C1
import com.cosmotune.video.importers.ImportFixtures.C2
import com.cosmotune.video.importers.ImportFixtures.C3
import com.cosmotune.video.importers.ImportFixtures.NOW
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpmlImporterTest {
    @Test
    fun readsEveryOutlineWithAFeedAtAnyDepth() {
        val preview = OpmlImporter.parse(ImportFixtures.text("freetube-subscriptions.opml"), NOW)
        assertEquals(listOf(C0, C1, C2, C3), preview.subscriptions.map { it.channelId }, "the commented-out entry is not read, the nested one is")
        assertEquals(
            listOf("Northern Lights Workshop", "Paper, Scissors & Stone", "Quotes \"and\" 中文", "Deep one"),
            preview.subscriptions.map { it.name },
            "title, else text; entities decoded; attribute names in any case; either quote",
        )
        assertEquals(2, preview.skipped, "the playlist feed and the repeated channel")
        assertTrue(preview.subscriptions.all { it.subscribedAtMs == NOW })
        assertNull(preview.reason)
    }

    @Test
    fun theIdIsTheFirstChannelIdOfTheFeedAddress() {
        fun idOf(xmlUrl: String): List<String> =
            OpmlImporter.parse("<opml><body><outline text=\"x\" xmlUrl=\"$xmlUrl\"/></body></opml>", NOW).subscriptions.map { it.channelId }
        assertEquals(listOf(C0), idOf("https://www.youtube.com/feeds/videos.xml?channel_id=$C0"))
        assertEquals(listOf(C0), idOf("https://www.youtube.com/feeds/videos.xml?x=1&amp;channel_id=$C0&amp;y=2"))
        assertEquals(listOf(C0), idOf("https://piped.example/feed/unauthenticated/rss?channels=$C0"))
        assertEquals(emptyList(), idOf("https://www.youtube.com/feeds/videos.xml?channel_id=UCtooshort"))
        assertEquals(emptyList(), idOf("https://www.youtube.com/feeds/videos.xml?channel_id=${C0}extra"), "a longer run of id characters is not a channel id")
    }

    @Test
    fun theNameFallsBackFromTitleToTextToTheId() {
        fun nameOf(attributes: String): String =
            OpmlImporter.parse("<opml><body><outline $attributes xmlUrl=\"?channel_id=$C0\"/></body></opml>", NOW).subscriptions.single().name
        assertEquals("T", nameOf("title=\"T\" text=\"X\""))
        assertEquals("X", nameOf("text=\"X\""))
        assertEquals("X", nameOf("title=\" \" text=\"X\""), "a blank title is no title")
        assertEquals(C0, nameOf(""))
    }

    @Test
    fun anOutlineWithoutAFeedIsAFolderAndNotAnEntry() {
        val preview = OpmlImporter.parse("<opml><body><outline text=\"Folder\"><outline text=\"Sub\"/></outline></body></opml>", NOW)
        assertTrue(preview.isEmpty)
        assertEquals(0, preview.skipped)
        assertEquals(ImportReason.NOTHING_FOUND, preview.reason)
    }

    @Test
    fun aTagThatOnlyStartsLikeOutlineIsNotOne() {
        val preview = OpmlImporter.parse("<opml><body><outlines xmlUrl=\"?channel_id=$C0\"/><outline xmlUrl=\"?channel_id=$C1\"/></body></opml>", NOW)
        assertEquals(listOf(C1), preview.subscriptions.map { it.channelId })
    }

    @Test
    fun aGreaterThanSignInsideAQuotedValueDoesNotEndTheTag() {
        val preview = OpmlImporter.parse("<opml><body><outline text=\"a > b\" xmlUrl=\"?channel_id=$C0\"/></body></opml>", NOW)
        assertEquals("a > b", preview.subscriptions.single().name)
    }

    @Test
    fun textThatIsNotOpmlIsRefused() {
        assertEquals(ImportReason.UNREADABLE, OpmlImporter.parse("hello", NOW).reason)
        assertEquals(ImportReason.UNREADABLE, OpmlImporter.parse("<html><outline xmlUrl=\"?channel_id=$C0\"/></html>", NOW).reason)
        assertEquals(ImportReason.UNREADABLE, OpmlImporter.parse("", NOW).reason)
    }

    @Test
    fun numericAndNamedEntitiesDecode() {
        assertEquals("&<>\"' 中文 🎵", ImportSupport.decodeXmlEntities("&amp;&lt;&gt;&quot;&apos; &#x4E2D;&#25991; &#x1F3B5;"))
        assertEquals("&unknown; &#; &#xZZ; &", ImportSupport.decodeXmlEntities("&unknown; &#; &#xZZ; &"), "what is not an entity stays")
    }
}
