package com.cosmotune.video.player.controller

import com.cosmotune.video.library.HistoryEntry
import com.cosmotune.video.model.ChannelItem
import com.cosmotune.video.model.ListItem
import com.cosmotune.video.model.VideoItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/** The queue behind "Add to queue", "Play all", Next and Previous, and autoplay's pick (spec 6.1). */
class QueueStateTest {

    private fun video(id: String, short: Boolean = false, upcoming: Boolean = false, durationSec: Long? = 100) = VideoItem(
        id = id, title = id, channelId = null, channelName = null, channelAvatarUrl = null, thumbnailUrl = null,
        durationSec = durationSec, viewCount = null, uploadedAtMs = null, uploadedText = null, isShort = short, isUpcoming = upcoming,
    )

    private fun ids(items: List<VideoItem>) = items.map { it.id }

    private fun watched(id: String, positionSec: Long, durationSec: Long = 100) = HistoryEntry(
        item = video(id), lastWatchedMs = 0, positionMs = positionSec * 1000, durationMs = durationSec * 1000, watchCount = 1,
    )

    @Test
    fun addAppendsAndNeverTwice() {
        val q = QueueState().add(video("a")).add(video("b")).add(video("a"))
        assertEquals(listOf("a", "b"), ids(q.upNext))
        assertEquals(emptyList(), q.played)
    }

    @Test
    fun playAllFromTheFirstQueuesTheRest() {
        val q = QueueState.playAll(listOf(video("a"), video("b"), video("c")), startId = "a")
        assertEquals(emptyList(), ids(q.played))
        assertEquals(listOf("b", "c"), ids(q.upNext))
    }

    @Test
    fun playAllFromTheMiddleKeepsTheEarlierOnesForPrevious() {
        val q = QueueState.playAll(listOf(video("a"), video("b"), video("c"), video("d")), startId = "c")
        assertEquals(listOf("a", "b"), ids(q.played))
        assertEquals(listOf("d"), ids(q.upNext))
    }

    @Test
    fun playAllWithAnIdThatIsNotInTheListQueuesAllOfIt() {
        val q = QueueState.playAll(listOf(video("a"), video("b")), startId = "zzz")
        assertEquals(emptyList(), ids(q.played))
        assertEquals(listOf("a", "b"), ids(q.upNext))
    }

    @Test
    fun playAllDropsRepeatedIds() {
        val q = QueueState.playAll(listOf(video("a"), video("b"), video("a"), video("c")), startId = "a")
        assertEquals(listOf("b", "c"), ids(q.upNext))
    }

    @Test
    fun nextTakesTheFirstAndRemembersTheCurrentOne() {
        val q = QueueState(upNext = listOf(video("b"), video("c")))
        val step = assertNotNull(q.next(current = video("a")))
        assertEquals("b", step.item.id)
        assertEquals(listOf("a"), ids(step.queue.played))
        assertEquals(listOf("c"), ids(step.queue.upNext))
    }

    @Test
    fun nextWithNothingQueuedIsNull() {
        assertNull(QueueState().next(current = video("a")))
    }

    @Test
    fun previousTakesTheLastPlayedAndPutsTheCurrentOneBackInFront() {
        val q = QueueState(played = listOf(video("a"), video("b")), upNext = listOf(video("d")))
        val step = assertNotNull(q.previous(current = video("c")))
        assertEquals("b", step.item.id)
        assertEquals(listOf("a"), ids(step.queue.played))
        assertEquals(listOf("c", "d"), ids(step.queue.upNext))
        // Next then comes back to where we were.
        val forward = assertNotNull(step.queue.next(current = step.item))
        assertEquals("c", forward.item.id)
        assertEquals(listOf("a", "b"), ids(forward.queue.played))
    }

    @Test
    fun previousWithNothingPlayedIsNull() {
        assertNull(QueueState(upNext = listOf(video("b"))).previous(current = video("a")))
    }

    @Test
    fun theUntouchedStateIsReturnedWhenNothingChanges() {
        val q = QueueState(upNext = listOf(video("a")))
        assertSame(q, q.add(video("a")))
        assertSame(q, q.withPlayed(null))
    }

    @Test
    fun withPlayedKeepsTheLastFiftyAndNoRepeats() {
        var q = QueueState()
        for (n in 1..60) q = q.withPlayed(video("v$n"))
        assertEquals(QueueState.PLAYED_CAP, q.played.size)
        assertEquals("v60", q.played.last().id)
        assertEquals("v11", q.played.first().id)
        q = q.withPlayed(video("v30"))
        assertEquals(1, q.played.count { it.id == "v30" })
        assertEquals("v30", q.played.last().id)
    }

    // --- autoplay's pick: the first related video not watched and not the current one

    private fun related(vararg items: VideoItem): List<ListItem> = items.map { ListItem.Video(it) }

    @Test
    fun autoplayPicksTheFirstRelatedVideo() {
        val pick = QueueState().autoplayPick(video("cur"), related(video("a"), video("b")), emptyList())
        assertEquals("a", pick?.id)
    }

    @Test
    fun autoplayNeverPicksTheCurrentVideo() {
        val pick = QueueState().autoplayPick(video("cur"), related(video("cur"), video("b")), emptyList())
        assertEquals("b", pick?.id)
    }

    @Test
    fun autoplaySkipsWhatWasWatchedToTheEndButNotWhatWasHalfWatched() {
        val history = listOf(watched("a", positionSec = 95), watched("b", positionSec = 50))
        val pick = QueueState().autoplayPick(video("cur"), related(video("a"), video("b"), video("c")), history)
        assertEquals("b", pick?.id, "b is in the history but only half watched")
        val onlyWatched = QueueState().autoplayPick(video("cur"), related(video("a")), history)
        assertNull(onlyWatched)
    }

    @Test
    fun autoplayNeverGoesBackToAVideoPlayedEarlierInTheSession() {
        // History can be paused, so the session's own trail counts too.
        val q = QueueState(played = listOf(video("a")))
        val pick = q.autoplayPick(video("cur"), related(video("a"), video("b")), emptyList())
        assertEquals("b", pick?.id)
    }

    @Test
    fun autoplayLeavesOutShortsAndPremieresThatHaveNotStarted() {
        val items = related(video("s", short = true), video("p", upcoming = true), video("ok"))
        assertEquals("ok", QueueState().autoplayPick(video("cur"), items, emptyList())?.id)
    }

    @Test
    fun autoplayIgnoresChannelsAndPlaylistsAndHasNothingToPickFromAnEmptyList() {
        val channel = ListItem.Channel(ChannelItem("UC1", "n", null, null, null, null))
        assertNull(QueueState().autoplayPick(video("cur"), listOf(channel), emptyList()))
        assertNull(QueueState().autoplayPick(video("cur"), emptyList(), emptyList()))
    }

    @Test
    fun autoplayWorksWithoutACurrentVideo() {
        assertEquals("a", QueueState().autoplayPick(null, related(video("a")), emptyList())?.id)
    }
}
