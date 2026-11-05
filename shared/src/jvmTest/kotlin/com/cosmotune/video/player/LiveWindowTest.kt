package com.cosmotune.video.player

import com.cosmotune.video.source.FakeVideoSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The live window (plan task Y-28; spec 5.13, 6.2): the engines report a live stream's duration as the end of its seekable window
 * and play it 10 s behind that end; the live edge is where they play, "Go to live" shows when the playhead is more than 10 s behind
 * it, and seeks stay inside the window, never past the edge.
 */
class LiveWindowTest {

    private val hour = 3_600_000L

    @Test fun theNumbersAreTheSpecs() {
        assertEquals(10_000L, LiveWindow.EDGE_OFFSET_MS, "spec 6.2: live plays 10 s behind the end of the window")
        assertEquals(10_000L, LiveWindow.GO_LIVE_AFTER_MS, "spec 5.13: Go to live when more than 10 s behind")
        assertEquals(0L, LiveWindow.WINDOW_START_MS)
    }

    @Test fun theLiveEdgeIsTheEndOfTheWindowLessTheOffset() {
        assertEquals(hour - 10_000, LiveWindow.liveEdgeMs(hour))
        assertEquals(0L, LiveWindow.liveEdgeMs(6_000), "a window shorter than the offset has no room to seek")
        assertEquals(0L, LiveWindow.liveEdgeMs(0), "no window known")
        assertEquals(0L, LiveWindow.liveEdgeMs(-5))
        assertEquals(30_000L, LiveWindow.liveEdgeMs(35_000, windowStartMs = 30_000), "never before the window's start")
        assertEquals(50_000L, LiveWindow.liveEdgeMs(60_000, windowStartMs = 30_000))
    }

    @Test fun howFarBehindTheLiveEdge() {
        assertEquals(0L, LiveWindow.behindLiveEdgeMs(positionMs = hour - 10_000, durationMs = hour), "at the edge")
        assertEquals(0L, LiveWindow.behindLiveEdgeMs(positionMs = hour - 4_000, durationMs = hour), "past the edge (a segment just arrived) counts as live")
        assertEquals(5_000L, LiveWindow.behindLiveEdgeMs(positionMs = hour - 15_000, durationMs = hour), "the engines' standing jitter")
        assertEquals(50_000L, LiveWindow.behindLiveEdgeMs(positionMs = hour - 60_000, durationMs = hour))
        assertEquals(hour - 10_000, LiveWindow.behindLiveEdgeMs(positionMs = 0, durationMs = hour), "at the window's start")
        assertEquals(0L, LiveWindow.behindLiveEdgeMs(positionMs = 12_000, durationMs = 0), "no window: never behind")
        assertEquals(0L, LiveWindow.behindLiveEdgeMs(positionMs = 0, durationMs = 6_000), "a window too short to fall behind in")
    }

    @Test fun goToLiveShowsWhenMoreThanTenSecondsBehind() {
        assertFalse(LiveWindow.showGoLive(0))
        assertFalse(LiveWindow.showGoLive(9_999))
        assertFalse(LiveWindow.showGoLive(10_000), "exactly 10 s is not more than 10 s")
        assertTrue(LiveWindow.showGoLive(10_001))
        assertTrue(LiveWindow.showGoLive(hour))
        // A stream at live never shows it, whatever the engines' jitter of up to a segment (5 s) round the edge.
        for (fromEnd in listOf(4_000L, 10_000L, 15_000L, 20_000L)) {
            assertFalse(LiveWindow.showGoLive(LiveWindow.behindLiveEdgeMs(hour - fromEnd, hour)), "$fromEnd ms from the window's end")
        }
        assertTrue(LiveWindow.showGoLive(LiveWindow.behindLiveEdgeMs(hour - 20_001, hour)), "10 s behind where live plays")
    }

    @Test fun seeksStayInTheWindowAndNeverPassTheEdge() {
        assertEquals(1_800_000L, LiveWindow.clampSeek(1_800_000, 0, hour))
        assertEquals(hour - 10_000, LiveWindow.clampSeek(hour, 0, hour), "the end of the window is the edge")
        assertEquals(hour - 10_000, LiveWindow.clampSeek(Long.MAX_VALUE, 0, hour), "Go to live")
        assertEquals(0L, LiveWindow.clampSeek(-5_000, 0, hour))
        assertEquals(30_000L, LiveWindow.clampSeek(10_000, 30_000, hour), "not before the window's start")
        assertEquals(0L, LiveWindow.clampSeek(40_000, 0, 0), "no window: the start")
        assertEquals(0L, LiveWindow.clampSeek(40_000, -20, 0), "a start before 0 counts as 0")
    }

    @Test fun anUpcomingVideoIsUnavailableWithItsStartTime() {
        val premiere = FakeVideoSource.sampleItem("fakevideo12")!!
        val upcoming = PlaybackUiState.Idle.copy(item = premiere, status = PlayerStatus.ERROR, error = VideoUiError.UNAVAILABLE)
        assertTrue(LiveWindow.isUpcoming(upcoming))
        assertEquals(premiere.uploadedAtMs, LiveWindow.upcomingStartMs(upcoming))
        assertEquals(FakeVideoSource.NOW_MS + 2 * 86_400_000L, LiveWindow.upcomingStartMs(upcoming))
        // Not upcoming: another error, a video that is not a premiere, no error at all.
        assertFalse(LiveWindow.isUpcoming(upcoming.copy(error = VideoUiError.NETWORK)))
        assertFalse(LiveWindow.isUpcoming(upcoming.copy(item = FakeVideoSource.sampleItem("fakevideo01"))))
        assertFalse(LiveWindow.isUpcoming(upcoming.copy(error = null, status = PlayerStatus.READY)))
        assertNull(LiveWindow.upcomingStartMs(PlaybackUiState.Idle))
        assertNull(LiveWindow.upcomingStartMs(upcoming.copy(item = premiere.copy(uploadedAtMs = null))), "a premiere whose time the source did not give")
    }
}
