package com.cosmotune.video.account

import com.cosmotune.video.chat.ChatRole
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The chat's first continuation on the watch page, and the live and replay answers of the chat endpoints. */
class InnerTubeLiveChatTest {
    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject

    private class Web(private val answer: JsonObject) : YoutubeWeb {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        override suspend fun post(endpoint: String, body: JsonObjectBuilder.() -> Unit): JsonObject {
            calls += endpoint to buildJsonObject(body)
            return answer
        }
    }

    private val watch = json(
        """{"contents":{"twoColumnWatchNextResults":{"conversationBar":{"liveChatRenderer":{
           "continuations":[{"reloadContinuationData":{"continuation":"FIRST"}}],"isReplay":true}}}}}""",
    )

    @Test fun theWatchPageNamesTheFirstContinuationAndWhetherItIsAReplay() {
        val start = assertNotNull(InnerTubeLiveChat.startOf(watch))
        assertEquals("FIRST", start.token)
        assertTrue(start.replay)
    }

    @Test fun aVideoWithoutAChatHasNoStart() {
        assertNull(InnerTubeLiveChat.startOf(json("""{"contents":{"twoColumnWatchNextResults":{}}}""")))
    }

    @Test fun liveMessagesSuperChatsBadgesAndThePinAreRead() {
        val batch = InnerTubeLiveChat.parse(
            json(
                """{"continuationContents":{"liveChatContinuation":{
                  "continuations":[{"invalidationContinuationData":{"continuation":"NEXT","timeoutMs":3000}}],
                  "actions":[
                   {"addChatItemAction":{"item":{"liveChatTextMessageRenderer":{"id":"a","authorName":{"simpleText":"Ana"},
                     "message":{"runs":[{"text":"hello "},{"emoji":{"emojiId":"😀","shortcuts":[":grin:"]}}]},
                     "authorBadges":[{"liveChatAuthorBadgeRenderer":{"icon":{"iconType":"MODERATOR"}}}],
                     "authorPhoto":{"thumbnails":[{"url":"//p/a.jpg","width":32}]}}}}},
                   {"addChatItemAction":{"item":{"liveChatPaidMessageRenderer":{"id":"b","authorName":{"simpleText":"Ben"},
                     "purchaseAmountText":{"simpleText":"$5.00"},"bodyBackgroundColor":4280150454,
                     "message":{"runs":[{"text":"thanks"}]}}}}},
                   {"addBannerToLiveChatCommand":{"bannerRenderer":{"liveChatBannerRenderer":{"contents":{
                     "liveChatTextMessageRenderer":{"id":"p","authorName":{"simpleText":"Host"},"message":{"runs":[{"text":"Rules"}]},
                     "authorBadges":[{"liveChatAuthorBadgeRenderer":{"icon":{"iconType":"OWNER"}}}]}}}}}},
                   {"markChatItemAsDeletedAction":{"targetItemId":"z"}}
                  ]}}}""",
            ),
        )
        assertEquals(listOf("a", "b"), batch.messages.map { it.id })
        assertEquals("hello 😀", batch.messages[0].text)
        assertEquals(ChatRole.MODERATOR, batch.messages[0].role)
        assertEquals("https://p/a.jpg", batch.messages[0].authorPhotoUrl)
        assertEquals("$5.00", batch.messages[1].paid?.amount)
        assertEquals(4280150454, batch.messages[1].paid?.colorArgb)
        assertEquals("Rules", batch.pinned?.text)
        assertEquals(ChatRole.OWNER, batch.pinned?.role)
        assertEquals(setOf("z"), batch.deleted)
        assertEquals("NEXT", batch.next)
        assertEquals(3000, batch.nextDelayMs)
    }

    @Test fun replayedMessagesCarryWhereInTheVideoTheyWereWritten() {
        val batch = InnerTubeLiveChat.parse(
            json(
                """{"continuationContents":{"liveChatContinuation":{
                  "continuations":[{"liveChatReplayContinuationData":{"continuation":"R2"}}],
                  "actions":[{"replayChatItemAction":{"videoOffsetTimeMsec":"61000","actions":[
                    {"addChatItemAction":{"item":{"liveChatTextMessageRenderer":{"id":"r","authorName":{"simpleText":"C"},
                      "message":{"runs":[{"text":"late"}]}}}}}]}}]}}}""",
            ),
        )
        assertEquals(61_000L, batch.messages.single().offsetMs)
        assertEquals("R2", batch.next)
    }

    @Test fun theReplayIsAskedFromThePlayersPosition() = runTest {
        val web = Web(buildJsonObject {})
        InnerTubeLiveChat(web).replay("TOKEN", 42_000)
        val (endpoint, body) = web.calls.single()
        assertEquals("live_chat/get_live_chat_replay", endpoint)
        assertEquals("TOKEN", body["continuation"]!!.jsonPrimitive.content)
        assertEquals("42000", body["currentPlayerState"]!!.jsonObject["playerOffsetMs"]!!.jsonPrimitive.content)
    }

    // ---- Real answers (trimmed and scrubbed: names, pictures, ids and words replaced) -------------------------------------------

    @Test fun aRealWatchPageNamesTheLiveChat() {
        val start = assertNotNull(InnerTubeLiveChat.startOf(AccountFixtures.json("live_chat_watch.json")))
        assertTrue(start.token.isNotBlank())
        assertEquals(false, start.replay)
        assertEquals(true, InnerTubeLiveChat.startOf(AccountFixtures.json("live_chat_watch_replay.json"))?.replay)
    }

    @Test fun aRealLivePageReadsItsMessagesEmojiAndRemovals() {
        val batch = InnerTubeLiveChat.parse(AccountFixtures.json("live_chat_live.json"))
        assertEquals(5, batch.messages.size, "three words, one standard emoji, one channel emoji; the welcome card is no message")
        assertTrue(batch.messages.all { it.author.startsWith("Viewer") && it.authorPhotoUrl != null })
        assertTrue(batch.messages.any { "\uD83D\uDE02" in it.text }, "a standard emoji is the character itself")
        assertTrue(batch.messages.any { it.text.contains(":face-pink-tears:") }, "a channel's own emoji is its shortcut")
        assertEquals(1, batch.deleted.size, "a message taken down by a moderator")
        assertNotNull(batch.next)
        assertEquals(10_000L, batch.nextDelayMs)
    }

    @Test fun aRealReplayPageCarriesOffsetsAndTheOwnersBadge() {
        val batch = InnerTubeLiveChat.parse(AccountFixtures.json("live_chat_replay.json"))
        assertTrue(batch.messages.isNotEmpty())
        assertTrue(batch.messages.all { it.offsetMs != null })
        assertEquals(batch.messages.map { it.offsetMs }, batch.messages.map { it.offsetMs }.sortedBy { it })
        assertTrue(batch.messages.any { it.role == ChatRole.OWNER })
        assertNotNull(batch.next, "the replay continuation, not the seek one")
    }
}
