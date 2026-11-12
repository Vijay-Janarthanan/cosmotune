package com.cosmotune.video.account

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A mix on Home (the site's "Mix - ..." card): it stays in the feed, where the site put it, and says what it is. */
class HomeMixCardsTest {
    private fun video(id: String, title: String, length: String) = """
        {"richItemRenderer":{"content":{"lockupViewModel":{"contentId":"$id","contentType":"LOCKUP_CONTENT_TYPE_VIDEO",
          "contentImage":{"thumbnailViewModel":{"image":{"sources":[{"url":"https://i.ytimg.com/vi/$id/hq.jpg","width":480,"height":270}]},
            "overlays":[{"thumbnailBottomOverlayViewModel":{"badges":[{"thumbnailBadgeViewModel":{"text":"$length"}}]}}]}},
          "metadata":{"lockupMetadataViewModel":{"title":{"content":"$title"},"metadata":{"contentMetadataViewModel":{"metadataRows":[
            {"metadataParts":[{"text":{"content":"Desk Lab"}}]},{"metadataParts":[{"text":{"content":"10K views"}},{"text":{"content":"2 days ago"}}]}]}}}}}}}}
    """.trimIndent()

    private fun list(id: String, title: String, first: String, badge: String, byline: String) = """
        {"richItemRenderer":{"content":{"lockupViewModel":{"contentId":"$id","contentType":"LOCKUP_CONTENT_TYPE_PLAYLIST",
          "contentImage":{"collectionThumbnailViewModel":{"primaryThumbnail":{"thumbnailViewModel":{"image":{"sources":[{"url":"https://i.ytimg.com/vi/$first/hq.jpg","width":480,"height":270}]},
            "overlays":[{"thumbnailOverlayBadgeViewModel":{"thumbnailBadges":[{"thumbnailBadgeViewModel":{"text":"$badge"}}]}}]}}}},
          "metadata":{"lockupMetadataViewModel":{"title":{"content":"$title"},"metadata":{"contentMetadataViewModel":{"metadataRows":[
            {"metadataParts":[{"text":{"content":"$byline"}},{"text":{"content":"Playlist"}}]}]}}}},
          "rendererContext":{"commandContext":{"onTap":{"innertubeCommand":{"watchEndpoint":{"videoId":"$first","playlistId":"$id"}}}}}}}}}
    """.trimIndent()

    private fun home(vararg cards: String): JsonObject = Json.parseToJsonElement(
        """{"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"richGridRenderer":{"contents":[${cards.joinToString(",")},
          {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"NEXT_1"}}}}]}}}}]}}}""",
    ) as JsonObject

    @Test fun aMixKeepsItsPlaceBetweenTheVideosAroundIt() {
        val feed = YoutubeFeedParser.parse(
            home(
                video("aaaaaaaaaaa", "First pick", "12:04"),
                list("RDbbbbbbbbbbb", "Mix - River Songs | Lantern Hour", "bbbbbbbbbbb", "Mix", "Maple Choir, The Rowers and more"),
                video("ccccccccccc", "Third pick", "1:02:33"),
            ),
        )
        assertEquals(listOf("aaaaaaaaaaa", "list:RDbbbbbbbbbbb", "ccccccccccc"), feed.homeItems.map { it.id })
        assertEquals(listOf("aaaaaaaaaaa", "ccccccccccc"), feed.videos.map { it.id }, "the video lists of other pages hold only videos")
        assertEquals("NEXT_1", feed.continuation)
    }

    @Test fun aMixCardCarriesWhatItNeedsToBeDrawnAndPlayed() {
        val card = YoutubeFeedParser.parse(
            home(list("RDbbbbbbbbbbb", "Mix - River Songs | Lantern Hour", "bbbbbbbbbbb", "Mix", "Maple Choir, The Rowers and more")),
        ).homeItems.single()
        assertEquals("Mix - River Songs | Lantern Hour", card.title)
        assertEquals("Maple Choir, The Rowers and more · Playlist", card.channelName)
        assertEquals("https://i.ytimg.com/vi/bbbbbbbbbbb/hq.jpg", card.thumbnailUrl)
        assertNull(card.durationSec)
        val list = card.playlist!!
        assertEquals("RDbbbbbbbbbbb", list.id)
        assertEquals("Mix", list.label)
        assertEquals("bbbbbbbbbbb", list.firstVideoId)
        assertTrue(list.isMix)
    }

    @Test fun anOrdinaryPlaylistIsLabelledPlaylistAndIsNotAMix() {
        val card = YoutubeFeedParser.parse(
            home(list("PLabc123", "Slow mornings", "ddddddddddd", "24 videos", "Asha Raman")),
        ).homeItems.single()
        assertEquals("Playlist", card.playlist!!.label)
        assertEquals(false, card.playlist!!.isMix)
    }

    @Test fun otherPagesStillGetTheirPlaylistsApart() {
        val feed = YoutubeFeedParser.parse(home(list("RDbbbbbbbbbbb", "Mix - River", "bbbbbbbbbbb", "Mix", "Maple Choir and more")))
        assertEquals(listOf("RDbbbbbbbbbbb"), feed.playlists.map { it.id })
    }
}
