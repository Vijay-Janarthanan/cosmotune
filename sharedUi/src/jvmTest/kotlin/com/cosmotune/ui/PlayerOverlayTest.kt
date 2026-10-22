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
import com.cosmotune.design.CapsuleGeometry
import com.cosmotune.design.PlayerBarMetrics
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
import com.cosmotune.music.ui.player.PlayerBack
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
        private var shot: org.jetbrains.skia.Bitmap? = null
        fun frames(n: Int) {
            repeat(n) {
                val image = scene.render(nanos)
                shot = org.jetbrains.skia.Bitmap.makeFromImage(image)
                nanos += 16_000_000L
            }
        }
        /** The colour (ARGB) at ([x], [y]) of the last frame rendered. */
        fun pixel(x: Int, y: Int): Int = checkNotNull(shot) { "no frame rendered yet" }.getColor(x, y)
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

    /**
     * The first frames of the grow are the capsule it came out of, whichever capsule it is: its corners and its cover are the
     * capsule's own. The capsule here is as big as the window, so the card has nowhere to grow to and what is left to see in
     * the first frames is its corners and where its cover is; the player's cover is a 320 dp square elsewhere, so the travelling
     * cover is a 48 dp one scaled up a little, and where it is says where it started: 8 dp in and down for the phone's capsule,
     * 16 dp for the desktop's.
     */
    @Test fun theFirstFramesOfTheGrowTakeTheCornersAndTheCoverOfTheCapsuleThatSaysSo() {
        val t = 0.03f
        val desktop = PlayerBarMetrics.capsuleGeometry(PlayerBarMetrics.planFor(PlayerBarMetrics.Tier.FULL), 800f)
        val target = Rect(100f, 60f, 420f, 380f)
        val cornerAlpha = HashMap<String, Int>()
        val coverLeft = HashMap<String, Int>()
        for ((name, geometry) in listOf("phone" to CapsuleGeometry.PHONE, "desktop" to desktop)) {
            val h = Harness(width = 800, height = 400)
            try {
                h.shell.capsuleBounds = Rect(0f, 0f, 800f, 400f)
                h.shell.capsuleLabel = CapsuleLabel("A song", "An artist", null, geometry)
                h.shell.playerCoverBounds = target
                h.open = true
                assertTrue(h.until(max = 20) { h.shell.capsuleHidden && h.progress > 0f } >= 0, "$name: the grow starts")
                // The travelling cover is composed a frame after the card is up. Hold the grow at 3 % (one frame of the spring is
                // more than that) so that it can be looked at.
                h.frames(2)
                kotlinx.coroutines.runBlocking { h.shell.playerProgress.snapTo(t) }
                h.frames(1)
                assertEquals(t, h.progress, 0f, "$name: held at the start of the grow")
                val cover = h.shell.colors.palette.surfaceVariant
                assertTrue(h.shell.colors.palette.backgroundBottom != cover, "the test can tell the ground from a cover")
                // The pixel 3 dp in from the corner: a 22 dp superellipse corner just covers it, a 28 dp one leaves it empty.
                cornerAlpha[name] = h.pixel(3, 3) ushr 24
                // Where the cover is: the box of the pixels that are the cover's own colour (inside its rim and its rounded corners).
                var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var maxX = -1; var maxY = -1
                for (y in 0 until 200) for (x in 0 until 200) if (h.pixel(x, y) == cover) {
                    minX = minOf(minX, x); minY = minOf(minY, y); maxX = maxOf(maxX, x); maxY = maxOf(maxY, y)
                }
                assertTrue(maxX > 0, "$name: the travelling cover is on screen")
                // 320 dp laid out, drawn at 48 / 320 of that growing to 1: 56 dp at 3 %, from where the capsule's cover is to where the player's will be
                val side = 320f * (48f / 320f + (1f - 48f / 320f) * t)
                val x0 = geometry.coverInsetLeftDp + (target.left - geometry.coverInsetLeftDp) * t
                val y0 = geometry.coverInsetTopDp + (target.top - geometry.coverInsetTopDp) * t
                assertEquals(x0, minX.toFloat(), 3f, "$name: the cover's left edge")
                assertEquals(y0, minY.toFloat(), 3f, "$name: the cover's top edge")
                assertEquals(x0 + side, maxX.toFloat(), 3f, "$name: the cover's right edge")
                assertEquals(y0 + side, maxY.toFloat(), 3f, "$name: the cover's bottom edge")
                coverLeft[name] = minX
            } finally { h.close() }
        }
        assertTrue(coverLeft.getValue("desktop") - coverLeft.getValue("phone") >= 6, "the bar's cover starts 8 dp further in than the phone capsule's: $coverLeft")
        val phoneCorner = cornerAlpha.getValue("phone")
        val barCorner = cornerAlpha.getValue("desktop")
        assertTrue(phoneCorner - barCorner >= 80, "the bar's corners are rounder than the phone capsule's: the corner pixel is $barCorner against $phoneCorner")
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
