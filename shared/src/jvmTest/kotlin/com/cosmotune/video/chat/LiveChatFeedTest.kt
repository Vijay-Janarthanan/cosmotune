package com.cosmotune.video.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LiveChatFeedTest {
    private fun msg(id: String, offset: Long? = null) = ChatMessage(id, "a", null, "t$id", offsetMs = offset)

    private class Source(
        val start: ChatStart?,
        val live: ArrayDeque<ChatBatch> = ArrayDeque(),
        val replayAt: (String, Long) -> ChatBatch = { _, _ -> ChatBatch(emptyList(), null) },
    ) : LiveChatSource {
        val replayCalls = mutableListOf<Pair<String, Long>>()
        override suspend fun start(videoId: String) = start
        override suspend fun live(token: String) = live.removeFirstOrNull() ?: ChatBatch(emptyList(), null)
        override suspend fun replay(token: String, offsetMs: Long): ChatBatch {
            replayCalls += token to offsetMs
            return replayAt(token, offsetMs)
        }
    }

    @Test fun aVideoWithoutChatSaysSo() = runTest {
        val feed = LiveChatFeed(Source(null), "v", backgroundScope)
        feed.start()
        runCurrent()
        assertEquals(ChatStatus.NONE, feed.state.value.status)
    }

    @Test fun liveMessagesArriveInOrderWithoutRepeatsAndThePinStays() = runTest {
        val source = Source(
            ChatStart("T", replay = false),
            ArrayDeque(
                listOf(
                    ChatBatch(listOf(msg("1"), msg("2")), "T2", 2_000, pinned = msg("p")),
                    ChatBatch(listOf(msg("2"), msg("3")), "T3", 2_000, deleted = setOf("1")),
                ),
            ),
        )
        val feed = LiveChatFeed(source, "v", backgroundScope)
        feed.start()
        runCurrent()
        assertEquals(listOf("1", "2"), feed.state.value.messages.map { it.id })
        advanceTimeBy(2_001)
        assertEquals(listOf("2", "3"), feed.state.value.messages.map { it.id })
        assertEquals("p", feed.state.value.pinned?.id)
        assertEquals(ChatStatus.SHOWING, feed.state.value.status)
    }

    @Test fun onlyTheNewestMessagesAreKept() = runTest {
        val many = (1..(LiveChatFeed.KEEP + 20)).map { msg("$it") }
        val feed = LiveChatFeed(Source(ChatStart("T", false), ArrayDeque(listOf(ChatBatch(many, null)))), "v", backgroundScope)
        feed.start()
        runCurrent()
        assertEquals(LiveChatFeed.KEEP, feed.state.value.messages.size)
        assertEquals("${LiveChatFeed.KEEP + 20}", feed.state.value.messages.last().id)
    }

    @Test fun aReplayShowsMessagesAsPlaybackReachesThem() = runTest {
        var position = 0L
        val source = Source(ChatStart("FIRST", replay = true), replayAt = { token, _ ->
            if (token == "FIRST") ChatBatch(listOf(msg("a", 1_000), msg("b", 5_000), msg("c", 30_000)), "MORE") else ChatBatch(emptyList(), "MORE")
        })
        val feed = LiveChatFeed(source, "v", backgroundScope) { position }
        feed.start()
        runCurrent()
        assertTrue(feed.state.value.replay)
        assertTrue(feed.state.value.messages.isEmpty())
        position = 2_000
        advanceTimeBy(600)
        assertEquals(listOf("a"), feed.state.value.messages.map { it.id })
        position = 6_000
        advanceTimeBy(600)
        assertEquals(listOf("a", "b"), feed.state.value.messages.map { it.id })
    }

    @Test fun seekingBackStartsTheReplayAgainFromThere() = runTest {
        var position = 120_000L
        val source = Source(ChatStart("FIRST", replay = true), replayAt = { _, at -> ChatBatch(listOf(msg("m$at", at)), "MORE") })
        val feed = LiveChatFeed(source, "v", backgroundScope) { position }
        feed.start()
        runCurrent()
        assertEquals(listOf("m120000"), feed.state.value.messages.map { it.id })
        position = 10_000
        advanceTimeBy(600)
        assertEquals("FIRST" to 10_000L, source.replayCalls.last { it.first == "FIRST" })
        assertEquals(listOf("m10000"), feed.state.value.messages.map { it.id })
    }

    @Test fun aSparseReplayIsNotAskedEveryTick() = runTest {
        var position = 0L
        val source = Source(ChatStart("FIRST", replay = true), replayAt = { _, _ -> ChatBatch(emptyList(), "MORE") })
        val feed = LiveChatFeed(source, "v", backgroundScope) { position }
        feed.start()
        runCurrent()
        repeat(20) {
            position += 500
            advanceTimeBy(500)
        }
        assertTrue(source.replayCalls.size <= 4, "asked ${source.replayCalls.size} times in 10 s")
        assertNull(feed.state.value.pinned)
    }
}
