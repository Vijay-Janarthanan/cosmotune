package com.cosmotune.video.account

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class MixPanelTest {
    private fun entry(id: String, title: String, length: String) =
        """{"playlistPanelVideoRenderer":{"videoId":"$id","title":{"simpleText":"$title"},"lengthText":{"simpleText":"$length"},
          "longBylineText":{"runs":[{"text":"Maple Choir","navigationEndpoint":{"browseEndpoint":{"browseId":"UCmaple"}}}]},
          "thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/vi/$id/default.jpg","width":120}]}}}"""

    @Test fun theMixPanelReadsInOrderOnceEach() {
        val json = Json.parseToJsonElement(
            """{"contents":{"twoColumnWatchNextResults":{"playlist":{"playlist":{"contents":[
              ${entry("aaaaaaaaaaa", "Evening one", "4:05")},{"playlistPanelVideoWrapperRenderer":{"primaryRenderer":${entry("bbbbbbbbbbb", "Evening two", "1:02:03")}}},
              ${entry("aaaaaaaaaaa", "Evening one", "4:05")}]}}}}}""",
        ) as JsonObject
        val videos = YoutubeFeedParser.parseMix(json)
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb"), videos.map { it.id })
        assertEquals(listOf(245L, 3723L), videos.map { it.durationSec })
        assertEquals("Maple Choir", videos[0].channelName)
        assertEquals("UCmaple", videos[0].channelId)
    }
}
