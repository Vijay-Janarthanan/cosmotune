package com.cosmotune.video.player

import com.cosmotune.video.design.VideoMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** When the chrome over the video shows and hides (spec 6.5 "Hiding"). */
class ChromeVisibilityTest {

    private fun ChromeState.on(event: ChromeEvent, nowMs: Long) = ChromeVisibility.next(this, event, nowMs)

    private val playingShown = ChromeState().on(ChromeEvent.PLAY, 0)

    @Test
    fun theFirstStateIsShownAndStaysWhilePaused() {
        val first = ChromeState()
        assertTrue(first.shown)
        assertNull(first.hiddenAt)
        assertFalse(first.playing)
        assertTrue(first.pinned, "paused: always shown")
        assertEquals(first, first.on(ChromeEvent.TICK, 1_000_000), "nothing hides a paused chrome")
    }

    @Test
    fun playingHidesThreeSecondsAfterTheLastInput() {
        assertTrue(playingShown.shown)
        assertEquals(VideoMetrics.HIDE_AFTER_MS, playingShown.hiddenAt)
        val before = playingShown.on(ChromeEvent.TICK, VideoMetrics.HIDE_AFTER_MS - 1)
        assertTrue(before.shown, "not yet")
        val after = playingShown.on(ChromeEvent.TICK, VideoMetrics.HIDE_AFTER_MS)
        assertFalse(after.shown)
        assertNull(after.hiddenAt)
    }

    @Test
    fun anyInputShowsAndRestartsTheTimer() {
        val input = playingShown.on(ChromeEvent.INPUT, 2_000)
        assertEquals(2_000 + VideoMetrics.HIDE_AFTER_MS, input.hiddenAt)
        assertTrue(input.on(ChromeEvent.TICK, 4_000).shown, "the first deadline no longer counts")
        val hidden = input.on(ChromeEvent.TICK, 5_000)
        assertFalse(hidden.shown)
        val back = hidden.on(ChromeEvent.INPUT, 9_000)
        assertTrue(back.shown, "a key or a pointer move brings it back")
        assertEquals(9_000 + VideoMetrics.HIDE_AFTER_MS, back.hiddenAt)
    }

    @Test
    fun aTapShowsWhenHiddenAndHidesWhenShown() {
        val hidden = playingShown.on(ChromeEvent.TICK, VideoMetrics.HIDE_AFTER_MS)
        val shown = hidden.on(ChromeEvent.TAP, 10_000)
        assertTrue(shown.shown)
        assertEquals(10_000 + VideoMetrics.HIDE_AFTER_MS, shown.hiddenAt)
        val again = shown.on(ChromeEvent.TAP, 10_500)
        assertFalse(again.shown, "a tap while shown hides")
        assertNull(again.hiddenAt)
    }

    @Test
    fun aTapDoesNotHideWhatMustStayShown() {
        val paused = ChromeState().on(ChromeEvent.TAP, 100)
        assertTrue(paused.shown, "paused: a tap keeps it")
        assertNull(paused.hiddenAt)
        val scrubbing = playingShown.on(ChromeEvent.SCRUB_START, 100).on(ChromeEvent.TAP, 200)
        assertTrue(scrubbing.shown)
        val sheet = playingShown.on(ChromeEvent.SHEET_OPEN, 100).on(ChromeEvent.TAP, 200)
        assertTrue(sheet.shown)
    }

    @Test
    fun pausingShowsAndPinsAndPlayingAgainRestartsTheTimer() {
        val hidden = playingShown.on(ChromeEvent.TICK, VideoMetrics.HIDE_AFTER_MS)
        val paused = hidden.on(ChromeEvent.PAUSE, 20_000)
        assertTrue(paused.shown)
        assertNull(paused.hiddenAt)
        assertFalse(paused.playing)
        val playing = paused.on(ChromeEvent.PLAY, 30_000)
        assertTrue(playing.shown)
        assertEquals(30_000 + VideoMetrics.HIDE_AFTER_MS, playing.hiddenAt)
    }

    @Test
    fun playingWhileHiddenStaysHidden() {
        val hidden = playingShown.on(ChromeEvent.TICK, VideoMetrics.HIDE_AFTER_MS)
        val again = hidden.on(ChromeEvent.PLAY, 5_000)
        assertFalse(again.shown)
        assertNull(again.hiddenAt)
    }

    @Test
    fun scrubbingKeepsItShownUntilTheEndThenTheTimerRuns() {
        val start = playingShown.on(ChromeEvent.SCRUB_START, 1_000)
        assertTrue(start.shown)
        assertNull(start.hiddenAt)
        assertTrue(start.scrubbing)
        assertTrue(start.on(ChromeEvent.TICK, 60_000).shown, "never hides while scrubbing")
        val end = start.on(ChromeEvent.SCRUB_END, 61_000)
        assertFalse(end.scrubbing)
        assertEquals(61_000 + VideoMetrics.HIDE_AFTER_MS, end.hiddenAt)
    }

    @Test
    fun aSheetKeepsItShownUntilItCloses() {
        val open = playingShown.on(ChromeEvent.SHEET_OPEN, 1_000)
        assertTrue(open.sheetOpen)
        assertNull(open.hiddenAt)
        assertTrue(open.on(ChromeEvent.TICK, 60_000).shown)
        assertTrue(open.on(ChromeEvent.INPUT, 2_000).hiddenAt == null, "input under a sheet does not start the timer")
        val closed = open.on(ChromeEvent.SHEET_CLOSE, 61_000)
        assertEquals(61_000 + VideoMetrics.HIDE_AFTER_MS, closed.hiddenAt)
    }

    @Test
    fun theEndOfAScrubWhilePausedStartsNoTimer() {
        val paused = ChromeState().on(ChromeEvent.SCRUB_START, 0).on(ChromeEvent.SCRUB_END, 500)
        assertTrue(paused.shown)
        assertNull(paused.hiddenAt)
        val sheet = ChromeState().on(ChromeEvent.SHEET_OPEN, 0).on(ChromeEvent.SHEET_CLOSE, 500)
        assertNull(sheet.hiddenAt)
    }

    @Test
    fun aTickBeforeAnyDeadlineChangesNothing() {
        assertEquals(playingShown, playingShown.on(ChromeEvent.TICK, 10))
    }
}
