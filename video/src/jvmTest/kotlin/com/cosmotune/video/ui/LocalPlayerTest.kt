package com.cosmotune.video.ui

import com.cosmotune.video.local.LocalVideo
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.nav.VideoBackStack
import com.cosmotune.video.nav.VideoRoute
import com.cosmotune.video.player.FakeVideoPlayback
import com.cosmotune.video.player.PlaybackUiState
import com.cosmotune.video.ui.local.LocalPlayerSession
import com.cosmotune.video.ui.local.LocalQueuePlayback
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The local player's queue and its playback (the pieces that need no window). */
class LocalPlayerTest {

    private fun item(name: String) = VideoItem(
        id = LocalVideo.idFor("/v/$name.mp4"), title = name, channelId = null, channelName = null, channelAvatarUrl = null,
        thumbnailUrl = null, durationSec = null, viewCount = null, uploadedAtMs = null, uploadedText = null,
    )

    @BeforeTest
    fun clear() {
        LocalPlayerSession.queue = emptyList()
    }

    @AfterTest
    fun clearAgain() = clear()

    @Test
    fun theQueueKnowsWhatComesAfterAndBeforeAFile() {
        LocalPlayerSession.queue = listOf("a", "b", "c")
        assertEquals("b", LocalPlayerSession.nextAfter("a"))
        assertNull(LocalPlayerSession.nextAfter("c"))
        assertEquals("b", LocalPlayerSession.previousBefore("c"))
        assertNull(LocalPlayerSession.previousBefore("a"))
        assertEquals(listOf("b", "c"), LocalPlayerSession.upNext("a"))
        assertTrue(LocalPlayerSession.upNext("c").isEmpty())
    }

    @Test
    fun aFileThatIsNotInTheQueueHasNoNeighbours() {
        LocalPlayerSession.queue = listOf("a", "b")
        assertNull(LocalPlayerSession.nextAfter("z"))
        assertNull(LocalPlayerSession.previousBefore("z"))
        assertTrue(LocalPlayerSession.upNext("z").isEmpty())
    }

    @Test
    fun nextAndPreviousMoveThroughTheLocalQueueAndTheStateShowsWhatIsNext() {
        val base = FakeVideoPlayback(initial = PlaybackUiState.Idle.copy(item = item("one")))
        var next = 0
        var previous = 0
        val queued = listOf(item("two"), item("three"))
        val playback = LocalQueuePlayback(base, queued, onNext = { next++ }, onPrevious = { previous++ }, onReplay = {})

        assertEquals(queued.map { it.id }, playback.state.value.queue.map { it.id })
        assertEquals(item("one").id, playback.state.value.item?.id, "the rest of the state is the playback's own")

        playback.playNext()
        playback.playPrevious()
        assertEquals(1, next)
        assertEquals(1, previous)
        assertTrue(base.calls.none { it.startsWith("playNext") || it.startsWith("playPrevious") }, "the controller's queue is not touched")
    }

    @Test
    fun playingALocalIdAgainRestartsTheFileAndOtherIdsGoToThePlayback() {
        val base = FakeVideoPlayback()
        val replays = ArrayList<Long?>()
        val playback = LocalQueuePlayback(base, emptyList(), onNext = {}, onPrevious = {}, onReplay = { replays += it })

        playback.play(item("one").id, startMs = 0L)
        playback.play(item("one"), startMs = null)
        assertEquals(listOf<Long?>(0L, null), replays)
        assertTrue(base.calls.isEmpty())

        playback.play("fakevideo01")
        assertEquals(listOf("play(fakevideo01)"), base.calls)
    }

    @Test
    fun otherCallsGoStraightToThePlayback() {
        val base = FakeVideoPlayback()
        val playback = LocalQueuePlayback(base, emptyList(), onNext = {}, onPrevious = {}, onReplay = {})
        playback.setSpeed(1.5f)
        playback.seekTo(5_000)
        assertEquals(5_000L, playback.positionMs())
        assertTrue(base.calls.any { it.startsWith("setSpeed") })
    }

    @Test
    fun aSecondFileTakesThePlaceOfTheLocalPlayerOnTheBackStack() {
        val stack = VideoBackStack()
        stack.push(VideoRoute.LocalFiles)
        stack.push(VideoRoute.LocalPlayer("local:1"))
        stack.push(VideoRoute.LocalPlayer("local:2"))
        assertEquals(VideoRoute.LocalPlayer("local:2"), stack.top)
        stack.pop()
        assertEquals(VideoRoute.LocalFiles, stack.top, "one Back leaves the player")
    }

    @Test
    fun theLocalRoutesRoundTripThroughTheSavedText() {
        for (route in listOf(VideoRoute.LocalFiles, VideoRoute.LocalPlayer("local:00ab12cd34ef56a7"), VideoRoute.LocalPlayer("local:a%b"))) {
            assertEquals(route, VideoRoute.decode(route.encode()))
        }
    }
}
