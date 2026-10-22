package com.cosmotune.ui

import androidx.compose.runtime.MonotonicFrameClock
import com.cosmotune.ui.nowplaying.ControlsSheetRules
import com.cosmotune.ui.nowplaying.ControlsSheetRules.EXPANDED
import com.cosmotune.ui.nowplaying.ControlsSheetRules.MINIMIZED
import com.cosmotune.ui.nowplaying.ControlsSheetState
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rules of Now Playing's controls panel that can be minimized over a video (NP-1): where a drag puts it, where it settles
 * when the finger lifts, what it does when the video goes away or a panel opens, and the instant change of reduced motion. The
 * pure rules are tested as numbers; the state holder is run on a frame clock of its own (16 ms a frame, as fast as the test can
 * go), so a spring runs to its end without a window.
 */
class ControlsSheetRulesTest {

    private class FrameClock : MonotonicFrameClock {
        var nanos = 0L
        var frames = 0
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            nanos += 16_000_000L
            frames++
            return onFrame(nanos)
        }
    }

    private fun sheet(stored: Boolean = false, kept: MutableList<Boolean> = mutableListOf()) =
        ControlsSheetState(stored) { kept += it }

    private fun <T> onClock(clock: FrameClock = FrameClock(), block: suspend () -> T): T = runBlocking(clock) { block() }

    // ---- the drag ---------------------------------------------------------------------------------------------------------

    @Test fun zeroIsExpandedAndOneIsMinimized() {
        assertEquals(0f, EXPANDED)
        assertEquals(1f, MINIMIZED)
    }

    @Test fun aDragDownMovesTowardMinimizedByItsShareOfTheTravel() {
        assertEquals(0.25f, ControlsSheetRules.dragged(0f, 50f, 200f), 1e-6f)
        assertEquals(0.5f, ControlsSheetRules.dragged(0.75f, -50f, 200f), 1e-6f, "a drag up comes back")
    }

    @Test fun theProgressIsClampedToZeroAndOne() {
        assertEquals(1f, ControlsSheetRules.dragged(0.9f, 500f, 200f))
        assertEquals(0f, ControlsSheetRules.dragged(0.1f, -500f, 200f))
        assertEquals(0f, ControlsSheetRules.clamp(-0.3f))
        assertEquals(1f, ControlsSheetRules.clamp(1.7f))
        assertEquals(0f, ControlsSheetRules.clamp(Float.NaN), "nothing measured yet is expanded")
    }

    @Test fun noTravelMeasuredYetLeavesTheProgressWhereItIs() {
        assertEquals(0.4f, ControlsSheetRules.dragged(0.4f, 80f, 0f))
        assertEquals(0.4f, ControlsSheetRules.dragged(0.4f, 80f, -10f))
    }

    @Test fun theTravelIsTheStackLessTheDockAndNeverZero() {
        assertEquals(300f, ControlsSheetRules.travelPx(500f, 200f))
        assertEquals(1f, ControlsSheetRules.travelPx(100f, 200f), "a stack shorter than the dock still divides")
    }

    // ---- the release ------------------------------------------------------------------------------------------------------

    @Test fun aFlingSettlesByItsDirectionWhereverTheFingerLifts() {
        val fling = ControlsSheetRules.FLING_DP_PER_S
        assertEquals(MINIMIZED, ControlsSheetRules.settleTarget(0.1f, fling), "a fling down minimizes from near the top")
        assertEquals(EXPANDED, ControlsSheetRules.settleTarget(0.9f, -fling), "a fling up expands from near the bottom")
        assertEquals(MINIMIZED, ControlsSheetRules.settleTarget(0f, fling * 4f))
        assertEquals(EXPANDED, ControlsSheetRules.settleTarget(1f, -fling * 4f))
    }

    @Test fun aSlowReleaseSettlesOnTheSideOfHalfWay() {
        val slow = ControlsSheetRules.FLING_DP_PER_S * 0.5f
        assertEquals(EXPANDED, ControlsSheetRules.settleTarget(0.49f, slow), "under half way goes back, even drifting down")
        assertEquals(MINIMIZED, ControlsSheetRules.settleTarget(0.5f, 0f), "half way is minimized")
        assertEquals(MINIMIZED, ControlsSheetRules.settleTarget(0.51f, -slow), "past half way stays down, even drifting up")
        assertEquals(EXPANDED, ControlsSheetRules.settleTarget(0f, 0f))
        assertEquals(MINIMIZED, ControlsSheetRules.settleTarget(1f, 0f))
    }

    // ---- when it applies, and the way back to expanded --------------------------------------------------------------------

    @Test fun theTargetIsMinimizedOnlyWhileItAppliesAndThePersonChoseIt() {
        assertEquals(MINIMIZED, ControlsSheetRules.target(applies = true, preferMinimized = true))
        assertEquals(EXPANDED, ControlsSheetRules.target(applies = true, preferMinimized = false))
        assertEquals(EXPANDED, ControlsSheetRules.target(applies = false, preferMinimized = true), "no video: exactly today's screen")
        assertEquals(EXPANDED, ControlsSheetRules.target(applies = false, preferMinimized = false))
    }

    @Test fun aVideoOnItsWayHoldsTheBarAndAVideoGoneDoesNot() {
        assertEquals(0L, ControlsSheetRules.holdBeforeExpandingMs(videoComing = false), "the video turned off or the song has none: at once")
        assertTrue(ControlsSheetRules.holdBeforeExpandingMs(videoComing = true) in 300L..1000L, "the next song's clip is about to show: a short hold")
    }

    @Test fun aPanelOpenedOverTheBarExpandsTheControlsForGood() {
        assertFalse(ControlsSheetRules.preferenceAfterPanel(preferMinimized = true, panelOpen = true, shownMinimized = true))
        assertTrue(ControlsSheetRules.preferenceAfterPanel(preferMinimized = true, panelOpen = true, shownMinimized = false), "a panel restored open at start leaves the choice alone")
        assertTrue(ControlsSheetRules.preferenceAfterPanel(preferMinimized = true, panelOpen = false, shownMinimized = true))
        assertFalse(ControlsSheetRules.preferenceAfterPanel(preferMinimized = false, panelOpen = true, shownMinimized = true))
    }

    // ---- what follows the progress ----------------------------------------------------------------------------------------

    @Test fun theBottomScrimFadesToAQuarterOfItsStrength() {
        assertEquals(1f, ControlsSheetRules.bottomScrimStrength(0f))
        assertEquals(0.25f, ControlsSheetRules.bottomScrimStrength(1f), 1e-6f)
        assertEquals(0.625f, ControlsSheetRules.bottomScrimStrength(0.5f), 1e-6f)
        assertEquals(0.25f, ControlsSheetRules.bottomScrimStrength(3f), 1e-6f, "clamped")
    }

    @Test fun theStackFadesOutAsTheDockFadesIn() {
        assertEquals(1f, ControlsSheetRules.stackAlpha(0f))
        assertEquals(0f, ControlsSheetRules.stackAlpha(1f))
        assertEquals(0f, ControlsSheetRules.dockAlpha(0f))
        assertEquals(1f, ControlsSheetRules.dockAlpha(1f))
        var last = 2f
        for (i in 0..20) {
            val a = ControlsSheetRules.stackAlpha(i / 20f)
            assertTrue(a <= last, "the stack only fades out")
            last = a
        }
    }

    @Test fun eachSideIsTakenOutOfTouchAndDrawingOnlyAtItsOtherEndAndNeverMidDrag() {
        assertTrue(ControlsSheetRules.stackHidden(1f, dragging = false))
        assertFalse(ControlsSheetRules.stackHidden(1f, dragging = true), "a drag that reaches the end keeps its node")
        assertFalse(ControlsSheetRules.stackHidden(0.6f, dragging = false))
        assertTrue(ControlsSheetRules.dockHidden(0f, dragging = false))
        assertFalse(ControlsSheetRules.dockHidden(0f, dragging = true))
        assertFalse(ControlsSheetRules.dockHidden(0.3f, dragging = false))
    }

    // ---- the state holder -------------------------------------------------------------------------------------------------

    @Test fun itAlwaysOpensExpandedAndRemembersTheStoredChoice() {
        val s = sheet(stored = true)
        assertEquals(EXPANDED, s.progress.value, "the video is not on yet when the screen opens")
        assertTrue(s.preferMinimized)
        assertFalse(sheet().preferMinimized, "first ever use is expanded")
    }

    @Test fun reducedMotionChangesAtOnceWithoutASpring() {
        val clock = FrameClock()
        val kept = mutableListOf<Boolean>()
        val s = sheet(kept = kept)
        onClock(clock) { s.toggle(reduced = true) }
        assertEquals(MINIMIZED, s.progress.value)
        assertEquals(0, clock.frames, "no frame was waited for")
        assertEquals(listOf(true), kept, "the choice is kept")
        onClock(clock) { s.toggle(reduced = true) }
        assertEquals(EXPANDED, s.progress.value)
        assertEquals(0, clock.frames)
        assertEquals(listOf(true, false), kept)
    }

    @Test fun withoutReducedMotionASpringRunsAndEndsExactlyOnTheTarget() {
        val clock = FrameClock()
        val s = sheet()
        onClock(clock) { s.toggle(reduced = false) }
        assertTrue(clock.frames > 3, "it took frames: ${clock.frames}")
        assertEquals(MINIMIZED, s.progress.value, "bounded: the overshoot stops at the end")
    }

    @Test fun aReleaseSettlesByTheRulesAndKeepsTheChoice() {
        val kept = mutableListOf<Boolean>()
        val s = sheet(kept = kept)
        onClock {
            s.startDrag()
            s.dragBy(140f, 200f)
            assertTrue(s.dragging)
            s.release(0f, reduced = false)
        }
        assertFalse(s.dragging)
        assertEquals(MINIMIZED, s.progress.value, "past half way")
        assertEquals(listOf(true), kept)
        onClock {
            s.startDrag()
            s.dragBy(-20f, 200f)
            s.release(-ControlsSheetRules.FLING_DP_PER_S * 2f, reduced = false)
        }
        assertEquals(EXPANDED, s.progress.value, "a fling up from near the bottom")
        assertEquals(listOf(true, false), kept)
    }

    @Test fun aReleaseThatLandsWhereItStartedKeepsNothingNew() {
        val kept = mutableListOf<Boolean>()
        val s = sheet(kept = kept)
        onClock {
            s.startDrag()
            s.dragBy(30f, 200f)
            s.release(0f, reduced = true)
        }
        assertEquals(EXPANDED, s.progress.value)
        assertEquals(emptyList(), kept, "the stored choice is written only when it changes")
    }

    @Test fun followingTheVideoMinimizesWhenItShowsAndSnapsBackWhenItGoes() {
        val clock = FrameClock()
        val s = sheet(stored = true)
        onClock(clock) { s.follow(applies = true, panelOpen = false, videoComing = true, reduced = false) }
        assertEquals(MINIMIZED, s.progress.value, "the remembered choice comes back with the video")
        val before = clock.frames
        onClock(clock) { s.follow(applies = false, panelOpen = false, videoComing = false, reduced = false) }
        assertEquals(EXPANDED, s.progress.value, "the video went: back to today's screen")
        assertEquals(before, clock.frames, "without an animation")
        assertTrue(s.preferMinimized, "and the choice is still remembered for the next video")
    }

    @Test fun aPanelOpenedWhileMinimizedExpandsAndForgetsTheChoice() {
        val kept = mutableListOf<Boolean>()
        val s = sheet(stored = true, kept = kept)
        onClock { s.follow(applies = true, panelOpen = false, videoComing = true, reduced = true) }
        assertEquals(MINIMIZED, s.progress.value)
        onClock { s.follow(applies = false, panelOpen = true, videoComing = true, reduced = true) }
        assertEquals(EXPANDED, s.progress.value)
        assertFalse(s.preferMinimized)
        assertEquals(listOf(false), kept)
    }

    @Test fun anExplicitExpandAndMinimizeDoWhatTheySay() {
        val s = sheet()
        onClock { s.minimize(reduced = true) }
        assertEquals(MINIMIZED, s.progress.value)
        onClock { s.minimize(reduced = true) }
        assertEquals(MINIMIZED, s.progress.value, "minimizing twice stays minimized")
        onClock { s.expand(reduced = true) }
        assertEquals(EXPANDED, s.progress.value)
    }
}
