package com.cosmotune.ui

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.snapshots.Snapshot
import com.cosmotune.theme.CoverColors
import com.cosmotune.theme.CoverField
import com.cosmotune.theme.CoverPalette
import com.cosmotune.ui.theme.CosmoPaletteState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CosmoPaletteStateTest {
    private fun solid(argb: Int, dark: Boolean = true) = CoverPalette.fromPixels(IntArray(64 * 64) { argb }, 64, 64, dark = dark)

    private class RecordingClock(val afterFrame: () -> Unit) : MonotonicFrameClock {
        private var now = 0L
        var frames = 0
            private set
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            now += 16_000_000L
            frames++
            return onFrame(now).also { afterFrame() }
        }
    }

    private class NoFrameClock : MonotonicFrameClock {
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = error("a frame was waited for")
    }

    /** Runs [block] with a clock that cancels it at its [frames]th frame: the effect that is restarted in the middle of a fade. */
    private suspend fun cutShortAfter(frames: Int, block: suspend () -> Unit) {
        var seen = 0
        val clock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
                if (++seen > frames) throw CancellationException("the effect was restarted")
                return onFrame(seen * 16_000_000L)
            }
        }
        try { withContext(clock) { block() } } catch (_: CancellationException) { }
    }

    private fun close(a: Int, b: Int, slack: Int = 2) = (0..2).all { s -> kotlin.math.abs(((a shr (8 * s)) and 0xFF) - ((b shr (8 * s)) and 0xFF)) <= slack }

    @Test fun theTargetChangesOnceAndTheGroundGlides() = runBlocking {
        val green = solid(0xFF466044.toInt()); val red = solid(0xFFD4552E.toInt())
        val state = CosmoPaletteState(green, dark = true)
        val targets = mutableListOf<CoverColors>(); val grounds = mutableListOf<Int>()
        withContext(RecordingClock { targets += state.target; grounds += state.liveArgb(CoverField.BACKGROUND_TOP) }) {
            state.show(red, dark = true, reducedMotion = false, animate = true)
        }
        assertTrue(targets.isNotEmpty() && targets.all { it == red }, "the target (and the Material scheme) changed before the first frame and never again")
        assertTrue(grounds.distinct().size > 10, "the ground moved over many frames")
        assertEquals(red.backgroundTop, state.liveArgb(CoverField.BACKGROUND_TOP))
    }

    @Test fun aPaletteThatSnapsInStillInvalidatesWhatReadTheGround() = runBlocking {
        val green = solid(0xFF466044.toInt()); val red = solid(0xFFD4552E.toInt())
        val state = CosmoPaletteState(green, dark = true)
        val read = mutableSetOf<Any>()
        val snapshot = Snapshot.takeSnapshot(readObserver = { read += it })
        snapshot.enter { state.liveArgb(CoverField.BACKGROUND_TOP) }   // what a drawWithCache lambda reads
        snapshot.dispose()
        val changed = mutableSetOf<Any>()
        val handle = Snapshot.registerApplyObserver { objects, _ -> changed.addAll(objects) }
        try {
            withContext(NoFrameClock()) { state.show(red, dark = true, reducedMotion = false, animate = false) }
            Snapshot.sendApplyNotifications()
        } finally { handle.dispose() }
        assertTrue(read.any { it in changed }, "the ground's draw cache is told the palette changed even though no fade ran")
    }

    @Test fun theFadeStartsFromTheOldColourAndNeverJumps() = runBlocking {
        val green = solid(0xFF466044.toInt()); val red = solid(0xFFD4552E.toInt())
        val state = CosmoPaletteState(green, dark = true)
        val grounds = mutableListOf<Int>()
        withContext(RecordingClock { grounds += state.liveArgb(CoverField.BACKGROUND_TOP) }) {
            state.show(red, dark = true, reducedMotion = false, animate = true)
        }
        assertTrue(close(green.backgroundTop, grounds.first(), 1), "the first frame is still the old ground")
        // No frame moves the ground further than a fraction of the whole way (a jump would be one big step).
        val oklabSteps = grounds.zipWithNext { a, b -> (0..2).sumOf { s -> kotlin.math.abs(((a shr (8 * s)) and 0xFF) - ((b shr (8 * s)) and 0xFF)) } }
        val whole = (0..2).sumOf { s -> kotlin.math.abs(((green.backgroundTop shr (8 * s)) and 0xFF) - ((red.backgroundTop shr (8 * s)) and 0xFF)) }
        assertTrue(oklabSteps.max() <= whole / 2 + 3, "the biggest step of $oklabSteps is under half of $whole")
    }

    @Test fun noFrameIsNeededWhenNotAnimating() = runBlocking {
        val green = solid(0xFF466044.toInt()); val red = solid(0xFFD4552E.toInt())
        val state = CosmoPaletteState(green, dark = true)
        withContext(NoFrameClock()) { state.show(red, dark = true, reducedMotion = false, animate = false) }
        assertEquals(red, state.target)
        assertEquals(red.backgroundTop, state.liveArgb(CoverField.BACKGROUND_TOP))
        assertEquals(red.textPrimary, state.liveArgb(CoverField.TEXT_PRIMARY))
    }

    @Test fun theSamePaletteAgainChangesNothing() = runBlocking {
        val green = solid(0xFF466044.toInt())
        val state = CosmoPaletteState(green, dark = true)
        withContext(NoFrameClock()) { state.show(green, dark = true, reducedMotion = false, animate = true) }
        assertEquals(green, state.target)
    }

    @Test fun reducedMotionIsAShortFade() = runBlocking {
        val green = solid(0xFF466044.toInt()); val red = solid(0xFFD4552E.toInt())
        fun frames(reduced: Boolean): Int = runBlocking {
            val state = CosmoPaletteState(green, dark = true)
            val clock = RecordingClock { }
            withContext(clock) { state.show(red, dark = true, reducedMotion = reduced, animate = true) }
            clock.frames
        }
        val normal = frames(false); val reduced = frames(true)
        assertTrue(normal in 38..52, "700 ms is about 44 frames of 16 ms, got $normal")
        assertTrue(reduced in 8..14, "150 ms is about 10 frames, got $reduced")
    }

    @Test fun aNewCoverInTheMiddleOfAFadeStartsFromWhatIsOnScreen() = runBlocking {
        val green = solid(0xFF466044.toInt()); val red = solid(0xFFD4552E.toInt()); val blue = solid(0xFF2E55D4.toInt())
        val state = CosmoPaletteState(green, dark = true)
        cutShortAfter(12) { state.show(red, dark = true, reducedMotion = false, animate = true) }
        val mid = state.liveArgb(CoverField.BACKGROUND_TOP)
        assertNotEquals(red.backgroundTop, mid, "the fade was cut short")
        assertNotEquals(green.backgroundTop, mid)
        val grounds = mutableListOf<Int>()
        withContext(RecordingClock { grounds += state.liveArgb(CoverField.BACKGROUND_TOP) }) {
            state.show(blue, dark = true, reducedMotion = false, animate = true)
        }
        assertTrue(close(mid, grounds.first(), 2), "the second fade starts where the first stopped: ${mid.toUInt().toString(16)} then ${grounds.first().toUInt().toString(16)}")
        assertEquals(blue.backgroundTop, state.liveArgb(CoverField.BACKGROUND_TOP))
    }

    @Test fun aFadeCutShortIsFinishedWhenTheSamePaletteIsAskedForAgain() = runBlocking {
        val green = solid(0xFF466044.toInt()); val red = solid(0xFFD4552E.toInt())
        val state = CosmoPaletteState(green, dark = true)
        cutShortAfter(12) { state.show(red, dark = true, reducedMotion = false, animate = true) }
        assertNotEquals(red.backgroundTop, state.liveArgb(CoverField.BACKGROUND_TOP), "stuck half-way")
        // The effect that restarts finds the palette it wants already the target: it must not leave the screen half-way.
        withContext(RecordingClock { }) { state.show(red, dark = true, reducedMotion = false, animate = true) }
        assertEquals(red.backgroundTop, state.liveArgb(CoverField.BACKGROUND_TOP))
        assertEquals(red.accent, state.liveArgb(CoverField.ACCENT))
    }

    @Test fun finishingWithoutAnimationNeedsNoFrame() = runBlocking {
        val green = solid(0xFF466044.toInt()); val red = solid(0xFFD4552E.toInt())
        val state = CosmoPaletteState(green, dark = true)
        cutShortAfter(12) { state.show(red, dark = true, reducedMotion = false, animate = true) }
        withContext(NoFrameClock()) { state.finishFade(reducedMotion = false, animate = false) }
        assertEquals(red.backgroundTop, state.liveArgb(CoverField.BACKGROUND_TOP))
    }

    @Test fun aThemeFlipIsRecordedWithTheTarget() = runBlocking {
        val dark = solid(0xFF466044.toInt(), dark = true); val light = solid(0xFF466044.toInt(), dark = false)
        val state = CosmoPaletteState(dark, dark = true)
        withContext(NoFrameClock()) { state.show(light, dark = false, reducedMotion = false, animate = false) }
        assertEquals(false, state.targetDark)
        assertEquals(light, state.target)
    }

    @Test fun aClockThatThrowsReallyFailsAnAnimatedShow() {
        val green = solid(0xFF466044.toInt()); val red = solid(0xFFD4552E.toInt())
        val state = CosmoPaletteState(green, dark = true)
        assertFailsWith<IllegalStateException> {
            runBlocking { withContext(NoFrameClock()) { state.show(red, dark = true, reducedMotion = false, animate = true) } }
        }
    }
}
