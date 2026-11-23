package com.cosmotune.video.account

import com.cosmotune.video.source.ExtraChannelTab
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class InnerTubeChannelExtrasTest {
    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject

    private class Web(private val answer: JsonObject) : YoutubeWeb {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        override suspend fun post(endpoint: String, body: JsonObjectBuilder.() -> Unit): JsonObject {
            calls += endpoint to buildJsonObject(body)
            return answer
        }
    }

    @Test fun theExtraTabsAreNamedByTheirAddress() {
        val response = json(
            """{"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[
              {"tabRenderer":{"title":"Videos","endpoint":{"browseEndpoint":{"params":"V"},"commandMetadata":{"webCommandMetadata":{"url":"/@a/videos"}}}}},
              {"tabRenderer":{"title":"Podcasts","endpoint":{"browseEndpoint":{"params":"P"},"commandMetadata":{"webCommandMetadata":{"url":"/@a/podcasts"}}}}},
              {"tabRenderer":{"title":"Posts","endpoint":{"browseEndpoint":{"params":"C"},"commandMetadata":{"webCommandMetadata":{"url":"/@a/community"}}}}},
              {"expandableTabRenderer":{}}]}}}""",
        )
        assertEquals(mapOf(ExtraChannelTab.PODCASTS to "P", ExtraChannelTab.POSTS to "C"), InnerTubeChannelExtras.tabsOf(response))
    }

    @Test fun postsAreReadWithTheirPicturesAndVideo() {
        val response = json(
            """{"contents":{"x":[{"backstagePostThreadRenderer":{"post":{"backstagePostRenderer":{"postId":"p1",
              "contentText":{"runs":[{"text":"New merch "},{"text":"today"}]},"publishedTimeText":{"runs":[{"text":"2 days ago"}]},
              "voteCount":{"simpleText":"1.2K"},
              "backstageAttachment":{"postMultiImageRenderer":{"images":[
                {"backstageImageRenderer":{"image":{"thumbnails":[{"url":"https://i/1.jpg","width":100}]}}},
                {"backstageImageRenderer":{"image":{"thumbnails":[{"url":"https://i/2.jpg","width":100}]}}}]}}}}}},
              {"backstagePostThreadRenderer":{"post":{"backstagePostRenderer":{"postId":"p2","contentText":{"simpleText":"Watch"},
              "backstageAttachment":{"videoRenderer":{"videoId":"vid","title":{"runs":[{"text":"Clip"}]},"lengthText":{"simpleText":"3:10"}}}}}}}]}}""",
        )
        val posts = InnerTubeChannelExtras.parsePosts(response)
        assertEquals(listOf("p1", "p2"), posts.map { it.id })
        assertEquals("New merch today", posts[0].text)
        assertEquals(listOf("https://i/1.jpg", "https://i/2.jpg"), posts[0].imageUrls)
        assertEquals("1.2K", posts[0].likesText)
        assertEquals("vid", posts[1].video?.id)
        assertEquals(190L, posts[1].video?.durationSec)
    }

    @Test fun aChannelSearchAsksTheSearchTabWithTheWords() = runTest {
        val web = Web(buildJsonObject {})
        InnerTubeChannelExtras(web).search("UC1", "old song")
        val (endpoint, body) = web.calls.single()
        assertEquals("browse", endpoint)
        assertEquals("UC1", body["browseId"]!!.jsonPrimitive.content)
        assertEquals("old song", body["query"]!!.jsonPrimitive.content)
        assertEquals(InnerTubeChannelExtras.SEARCH_PARAMS, body["params"]!!.jsonPrimitive.content)
    }
}
