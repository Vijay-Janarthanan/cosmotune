package com.cosmotune.video.account

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The related videos from the site's `next` answer ([InnerTubeRelated], the second way to "Up next"), on the answer saved from a real
 * anonymous call in 2025 (`next_related_2025.json`: the column is made of `lockupViewModel`s).
 */
class InnerTubeRelatedTest {

    private class FakeWeb(private val answer: () -> JsonObject) : YoutubeWeb {
        val calls = mutableListOf<Pair<String, JsonObject>>()

        override suspend fun post(endpoint: String, body: JsonObjectBuilder.() -> Unit): JsonObject {
            calls += endpoint to buildJsonObject(body)
            return answer()
        }
    }

    private fun fixture() = AccountFixtures.json("next_related_2025.json")

    // ---- The parser on the real answer --------------------------------------------------------------------------------

    @Test fun theSecondaryColumnIsReadAndTheRestOfThePageIsNot() {
        val feed = YoutubeFeedParser.parseRelated(fixture())
        assertEquals(listOf("4fndeDfaWCg", "djV11Xbc914", "ci6ZtPAN0PM", "oEDzhaMs0Iw", "XGxIE1hr0w4"), feed.videos.map { it.id })
        assertFalse("OWNCOLUMN01" in feed.videos.map { it.id }, "a lockup in the page's own column is not a related video")
        assertEquals(0, feed.unreadable)
        assertEquals("NEXT_RELATED_TOKEN_1", feed.continuation)
    }

    @Test fun eachRelatedVideoCarriesItsChannelAvatarDurationViewsAndAge() {
        val first = YoutubeFeedParser.parseRelated(fixture()).videos[0]
        assertEquals("Backstreet Boys - I Want It That Way (Official HD Video)", first.title)
        assertEquals("Backstreet Boys", first.channelName)
        assertEquals("UC1OR2YNQLZJYFdQjFrPWvVw", first.channelId)
        assertTrue(first.channelAvatarUrl!!.startsWith("https://yt3.ggpht.com/"), "the channel picture")
        assertTrue(first.thumbnailUrl!!.startsWith("https://i.ytimg.com/vi/4fndeDfaWCg/"))
        assertEquals(3 * 60L + 40, first.durationSec)
        assertEquals(1_800_000_000L, first.viewCount)
        assertEquals("17y ago", first.uploadedText)
        assertFalse(first.isShort || first.isLive || first.isUpcoming)
        val fourth = YoutubeFeedParser.parseRelated(fixture()).videos[3]
        assertEquals(18 * 60L + 28, fourth.durationSec)
        assertEquals(2_800_000L, fourth.viewCount)
        assertEquals("1mo ago", fourth.uploadedText)
    }

    @Test fun theCurrentVideoIsLeftOut() {
        val feed = YoutubeFeedParser.parseRelated(fixture(), currentId = "ci6ZtPAN0PM")
        assertEquals(listOf("4fndeDfaWCg", "djV11Xbc914", "oEDzhaMs0Iw", "XGxIE1hr0w4"), feed.videos.map { it.id })
    }

    @Test fun anAnswerWithoutTheColumnReadsAsNothing() {
        val feed = YoutubeFeedParser.parseRelated(buildJsonObject { put("contents", buildJsonObject { }) })
        assertTrue(feed.isEmpty)
        assertFalse(feed.looksChanged)
    }

    @Test fun theOlderCompactVideoRendererIsStillRead() {
        val json = """{"contents":{"twoColumnWatchNextResults":{"secondaryResults":{"secondaryResults":{"results":[
            {"compactVideoRenderer":{"videoId":"abcdefghijk","title":{"simpleText":"Old shape"},
              "longBylineText":{"runs":[{"text":"A channel","navigationEndpoint":{"browseEndpoint":{"browseId":"UCaaaaaaaaaaaaaaaaaaaaaa"}}}]},
              "lengthText":{"simpleText":"4:05"},"viewCountText":{"simpleText":"1,234 views"},"publishedTimeText":{"simpleText":"2 years ago"},
              "thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/vi/abcdefghijk/hqdefault.jpg","width":168,"height":94}]}}}]}}}}}"""
        val feed = YoutubeFeedParser.parseRelated(kotlinx.serialization.json.Json.parseToJsonElement(json) as JsonObject)
        val video = feed.videos.single()
        assertEquals("abcdefghijk", video.id)
        assertEquals("A channel", video.channelName)
        assertEquals(4 * 60L + 5, video.durationSec)
        assertEquals(1234L, video.viewCount)
    }

    // ---- The request and its failures ---------------------------------------------------------------------------------

    @Test fun oneNextRequestForTheVideoWithTheSitesTwoCheckFlags() = runTest {
        val web = FakeWeb { fixture() }
        val videos = InnerTubeRelated(web).fetch("dQw4w9WgXcQ")
        assertEquals(5, videos.size)
        assertEquals(1, web.calls.size)
        val (endpoint, body) = web.calls.single()
        assertEquals("next", endpoint)
        assertEquals(JsonPrimitive("dQw4w9WgXcQ"), body["videoId"])
        assertEquals(JsonPrimitive(true), body["contentCheckOk"])
        assertEquals(JsonPrimitive(true), body["racyCheckOk"])
    }

    @Test fun theVideoItselfIsNotItsOwnSuggestion() = runTest {
        val videos = InnerTubeRelated(FakeWeb { fixture() }).fetch("djV11Xbc914")
        assertEquals(listOf("4fndeDfaWCg", "ci6ZtPAN0PM", "oEDzhaMs0Iw", "XGxIE1hr0w4"), videos.map { it.id })
    }

    @Test fun anAnswerWithNoSuggestionsIsAnEmptyList() = runTest {
        val none = buildJsonObject { put("contents", buildJsonObject { }) }
        assertEquals(emptyList(), InnerTubeRelated(FakeWeb { none }).fetch("dQw4w9WgXcQ"))
    }

    @Test fun aColumnOfItemsNothingCanReadIsTheSiteChanging() = runTest {
        val json = """{"contents":{"twoColumnWatchNextResults":{"secondaryResults":{"secondaryResults":{"results":[
            {"lockupViewModel":{"contentId":"abcdefghijk","contentType":"LOCKUP_CONTENT_TYPE_VIDEO"}}]}}}}}"""
        val changed = kotlinx.serialization.json.Json.parseToJsonElement(json) as JsonObject
        val failure = assertFailsWith<AccountFailure.Changed> { InnerTubeRelated(FakeWeb { changed }).fetch("dQw4w9WgXcQ") }
        assertNotNull(failure.message)
    }

    @Test fun anErrorBodyIsItsFailureAndANetworkErrorPassesThrough() = runTest {
        val refused = buildJsonObject { put("error", buildJsonObject { put("code", 429); put("status", "RESOURCE_EXHAUSTED") }) }
        assertIs<AccountFailure.RateLimited>(assertFailsWith<AccountFailure> { InnerTubeRelated(FakeWeb { refused }).fetch("dQw4w9WgXcQ") })
        val down = IOException("no route")
        val thrown = assertFailsWith<IOException> { InnerTubeRelated(FakeWeb { throw down }).fetch("dQw4w9WgXcQ") }
        assertEquals(down, thrown)
        assertIs<AccountFailure.Network>(failureOf(thrown), "the source maps it to a network failure")
    }
}
