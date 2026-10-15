package com.cosmotune.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import com.cosmotune.settings.CosmoLookSettings
import com.cosmotune.settings.KeyValueStore
import com.cosmotune.ui.shell.CosmoPlayerOverlay
import com.cosmotune.ui.shell.LocalCosmoPlayerDismiss
import com.cosmotune.ui.theme.CapsuleLabel
import com.cosmotune.ui.theme.CosmoShellState
import com.cosmotune.ui.theme.CosmoType
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoShell
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.LocalReducedMotion
import com.music.bitchord.ui.player.PlayerBack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The player overlay, composed for real in an offscreen Skia scene (no window): it comes out of the capsule and goes back
 * into it, hides the capsule only while it stands there, takes a drag and a back press, provides the dismiss local, and with
 * reduced motion only fades.
 */
@OptIn(ExperimentalComposeUiApi::class)
class PlayerOverlayTest {
    private class MapStore : KeyValueStore {
        private val map = HashMap<String, Boolean>()
        override fun getBoolean(key: String, default: Boolean) = map[key] ?: default
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
    }

    private class Harness(reduced: Boolean = false, val width: Int = 400, val height: Int = 800, prepare: (CosmoShellState) -> Unit = {}) {
        val shell = CosmoShellState(CosmoLookSettings(MapStore()), dark = true).also(prepare)
        var open by mutableStateOf(false)
        var dismissed = 0
        var live = 0                       // copies of the player's content now composed
        var dismissLocal: (() -> Unit)? = null
        var contentWidth: Dp = Dp.Unspecified
        var tick by mutableStateOf(0)       // read by the caller's composition: bumping it recomposes the caller
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            CompositionLocalProvider(
                LocalCosmoColors provides shell.colors,
                LocalCosmoShell provides shell,
                LocalCosmoType provides CosmoType(411f, FontFamily.Default, FontFamily.Default),
                LocalReducedMotion provides reduced,
            ) {
                Box(Modifier.fillMaxSize()) {
                    val seen = tick
                    CosmoPlayerOverlay(open = open, onDismiss = { seen.hashCode(); open = false; dismissed++ }, shell = shell) { w, _ ->
                        DisposableEffect(Unit) { live++; onDispose { live-- } }
                        dismissLocal = LocalCosmoPlayerDismiss.current
                        contentWidth = w
                        Box(Modifier.fillMaxSize().background(Color.Red))
                    }
                }
            }
        }
        private var nanos = 0L
        fun frames(n: Int) { repeat(n) { scene.render(nanos); nanos += 16_000_000L } }
        /** Renders frames until [done], at most [max]; returns the number of frames it took, or -1. */
        fun until(max: Int = 300, done: () -> Boolean): Int {
            repeat(max) { if (done()) return it; frames(1) }
            return if (done()) max else -1
        }
        val progress get() = shell.playerProgress.value
        fun close() = scene.close()

        private var millis = 1_000L
        fun pointer(kind: PointerEventType, x: Float, y: Float) {
            scene.sendPointerEvent(kind, Offset(x, y), timeMillis = millis, type = PointerType.Touch)
            millis += 16
        }
        /** A finger going down at [from], moving to [to] in steps 16 ms apart and lifting there. */
        fun drag(from: Offset, to: Offset, steps: Int = 12) {
            pointer(PointerEventType.Move, from.x, from.y)
            pointer(PointerEventType.Press, from.x, from.y)
            frames(1)
            for (i in 1..steps) {
                pointer(PointerEventType.Move, from.x + (to.x - from.x) * i / steps, from.y + (to.y - from.y) * i / steps)
                frames(1)
            }
            pointer(PointerEventType.Release, to.x, to.y)
            frames(1)
        }
    }

    private fun harness(reduced: Boolean = false, block: Harness.() -> Unit) {
        val h = Harness(reduced)
        try { h.block() } finally { h.close() }
    }

    private fun Harness.openAndSettle() {
        open = true
        assertTrue(until { progress >= 1f } >= 0, "the player opens")
        frames(2)
    }

    @Test fun composesNothingWhileClosed() = harness {
        frames(5)
        assertEquals(0, live, "no player content while closed")
        assertEquals(0f, progress)
        assertFalse(shell.capsuleHidden)
    }

    @Test fun growsOutOfTheCapsuleHidesItAndSettles() = harness {
        shell.capsuleBounds = Rect(16f, 640f, 384f, 704f)
        shell.capsuleLabel = CapsuleLabel("A song", "An artist", null)
        open = true
        frames(1)
        assertEquals(1, live, "the content is composed on the first frame")
        var mid = false
        var hiddenWhileMoving = true
        var guard = 0
        while (progress < 1f && guard++ < 300) {
            if (progress > 0.05f && progress < 0.95f) { mid = true; hiddenWhileMoving = hiddenWhileMoving && shell.capsuleHidden }
            frames(1)
        }
        assertTrue(mid, "progress passes through the middle: it is a morph, not a jump")
        assertTrue(hiddenWhileMoving, "the capsule is hidden from the start of the morph to its end")
        frames(2)
        assertTrue(shell.playerSettled, "playerSettled is true once the morph has finished")
        assertTrue(shell.capsuleHidden, "the capsule stays hidden while the player stands open")
        assertEquals(400.0f, contentWidth.value, "the content is told the window's width")
    }

    @Test fun shrinksBackIntoTheCapsuleAndShowsItAgain() = harness {
        shell.capsuleBounds = Rect(16f, 640f, 384f, 704f)
        openAndSettle()
        open = false
        assertTrue(until { progress <= 0f } >= 0, "the player closes")
        frames(3)
        assertFalse(shell.capsuleHidden, "the capsule is back")
        assertEquals(0, live, "the content is gone")
        assertFalse(shell.playerSettled)
    }

    @Test fun closingTwiceWorksAsWellAsTheFirstTime() = harness {
        repeat(2) {
            openAndSettle()
            assertEquals(1, live)
            open = false
            assertTrue(until { progress <= 0f } >= 0, "closes, round ${it + 1}")
            frames(3)
            assertEquals(0, live, "gone, round ${it + 1}")
            assertFalse(shell.capsuleHidden, "capsule back, round ${it + 1}")
        }
    }

    @Test fun theDismissLocalClosesThePlayerAndIsOneFunctionForTheWholeLife() = harness {
        openAndSettle()
        val first = assertNotNull(dismissLocal)
        tick++                          // the caller recomposes with a new onDismiss lambda: the player must not see a new function
        frames(3)
        assertTrue(first === dismissLocal, "the same function: the static local never recomposes the player")
        first()
        assertEquals(1, dismissed)
        assertFalse(open)
    }

    @Test fun aBackPressClosesAnOpenPlayerAndIsLeftAloneOtherwise() = harness {
        assertFalse(PlayerBack.dispatch(), "closed: nothing takes back")
        openAndSettle()
        assertTrue(PlayerBack.dispatch(), "open: the overlay takes back")
        assertEquals(1, dismissed)
        until { progress <= 0f }
        frames(3)
        assertFalse(PlayerBack.dispatch(), "closed again: nothing takes back")
    }

    @Test fun aLongDragDownClosesItAndAShortOneComesBack() = harness {
        openAndSettle()
        drag(Offset(200f, 200f), Offset(200f, 330f))      // 130 of 800 px: progress 0.84, above the line
        assertEquals(0, dismissed, "a short pull does not close")
        assertTrue(until { progress >= 1f } >= 0, "and the player settles back to the window")
        assertTrue(open)
        drag(Offset(200f, 100f), Offset(200f, 700f))      // 600 of 800 px: progress 0.25
        assertEquals(1, dismissed, "a long pull closes through onDismiss")
        assertTrue(until { progress <= 0f } >= 0, "and the player goes back into the capsule")
        frames(3)
        assertFalse(shell.capsuleHidden)
    }

    @Test fun withReducedMotionItOnlyFadesAndTheCapsuleStays() {
        harness(reduced = true) {
            shell.capsuleBounds = Rect(16f, 640f, 384f, 704f)
            open = true
            var everHidden = false
            val took = until(max = 60) { everHidden = everHidden || shell.capsuleHidden; progress >= 1f }
            assertTrue(took in 1..16, "a 150 ms fade is at most about ten frames, took $took")
            assertFalse(everHidden, "the capsule is never hidden: the player fades in over it")
            open = false
            assertTrue(until(max = 60) { progress <= 0f } in 1..16, "and fades out the same way")
            frames(3)
            assertEquals(0, live)
        }
    }

    @Test fun anOverlayRemountedHalfOpenDoesNotReplayTheClosing() {
        // The song went away while the player was up and the overlay left the composition: the shell keeps its progress.
        val h = Harness(prepare = { shell ->
            kotlinx.coroutines.runBlocking { shell.playerProgress.snapTo(1f) }
            shell.capsuleHidden = true
        })
        try {
            h.frames(3)
            assertEquals(0f, h.progress, "a closed overlay found with the player half-open puts it away at once")
            assertFalse(h.shell.capsuleHidden)
            assertEquals(0, h.live)
        } finally { h.close() }
    }

    @Test fun theProgressNeverLeavesZeroToOneAndEndsOnTheCapsuleWithoutASettle() = harness {
        openAndSettle()
        assertEquals(1f, progress, "it ends exactly on the window, not on an overshoot of it")
        open = false
        var lowest = 1f
        var guard = 0
        while (!(progress <= 0f && !shell.capsuleHidden) && guard++ < 300) {
            lowest = minOf(lowest, progress)
            assertFalse(shell.capsuleHidden && live == 0, "a frame with neither the card nor the capsule on screen")
            frames(1)
        }
        assertTrue(guard < 300, "the closing ends, with the capsule handed back")
        assertTrue(lowest >= 0f, "the spring's undershoot is cut off at the capsule: $lowest")
        assertEquals(0f, progress)
    }
}
