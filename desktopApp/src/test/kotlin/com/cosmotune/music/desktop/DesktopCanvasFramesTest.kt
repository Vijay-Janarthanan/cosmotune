package com.cosmotune.music.desktop

import androidx.compose.ui.graphics.asSkiaBitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The song's video is drawn from two reused bitmaps at the size it is shown, and decoded only while it can be seen. A new
 * picture per frame (3.7 MB copied three times, 30 times a second) is what ran a 512 MB desktop out of native memory: Skia's
 * allocation failure is a C++ exception no Kotlin catch reaches, and it took the JVM down.
 */
class DesktopCanvasFramesTest {

    private val clip: ByteArray = javaClass.getResource("/canvas/full-hd.mp4")!!.readBytes()

    private class MemorySource(private val bytes: ByteArray) : DesktopAvioSource {
        private var at = 0L
        override fun read(into: ByteArray, count: Int): Int {
            if (at >= bytes.size) return -1
            val n = minOf(count.toLong(), bytes.size - at).toInt()
            System.arraycopy(bytes, at.toInt(), into, 0, n)
            at += n
            return n
        }
        override fun seek(position: Long) { at = position.coerceAtLeast(0L) }
        override fun position(): Long = at
        override fun length(): Long = bytes.size.toLong()
    }

    @Test
    fun theVideoIsDecodedAtTheSizeItIsShownNeverLargerThanTheSourceOrTheCap() {
        // A 16:9 box of 640 x 360 takes 640 x 360 of a 1080p video.
        assertEquals(CanvasFrameSize(640, 360), canvasOutputSize(1920, 1080, 640, 360, 1920))
        // A box of another shape is filled (the video is cropped), so the side that overflows sets the size.
        assertEquals(CanvasFrameSize(712, 400), canvasOutputSize(1920, 1080, 400, 400, 1920))
        // Never upscaled past the source...
        assertEquals(CanvasFrameSize(1280, 720), canvasOutputSize(1280, 720, 2560, 1440, 1920))
        // ...nor past the cap.
        assertEquals(CanvasFrameSize(1920, 1080), canvasOutputSize(3840, 2160, 3840, 2160, 1920))
        // No box yet: the source, capped.
        assertEquals(CanvasFrameSize(480, 270), canvasOutputSize(1920, 1080, 0, 0, 480))
        // Odd sizes come out even.
        val odd = canvasOutputSize(1920, 1080, 333, 187, 1920)
        assertEquals(0, odd.width % 2)
        assertEquals(0, odd.height % 2)
        assertTrue(odd.width >= 333 && odd.height >= 187)
    }

    @Test
    fun aVideoNobodyCanSeeIsNotDecoded() {
        assertTrue(CanvasVideoGate.shouldDecode(windowShown = true, onScreen = true, hasFrame = true, presentationAlpha = 1f))
        // Minimised or put away in the tray.
        assertFalse(CanvasVideoGate.shouldDecode(windowShown = false, onScreen = true, hasFrame = true, presentationAlpha = 1f))
        assertFalse(CanvasVideoGate.shouldDecode(windowShown = false, onScreen = true, hasFrame = false, presentationAlpha = 1f))
        // Scrolled or docked out of the window.
        assertFalse(CanvasVideoGate.shouldDecode(windowShown = true, onScreen = false, hasFrame = true, presentationAlpha = 1f))
        // Faded all the way out by the player.
        assertFalse(CanvasVideoGate.shouldDecode(windowShown = true, onScreen = true, hasFrame = true, presentationAlpha = 0f))
        // But a first frame is always fetched: the player fades the video in only once one is there.
        assertTrue(CanvasVideoGate.shouldDecode(windowShown = true, onScreen = true, hasFrame = false, presentationAlpha = 0f))
    }

    @Test
    fun framesAreWrittenIntoTheSameTwoBitmapsInTurn() {
        val ring = CanvasFrameRing()
        try {
            assertTrue(ring.ensure(64, 36))
            val addresses = ArrayList<Long>()
            val shown = (0 until 10).map {
                assertNotNull(ring.write { addresses += it.address })
                assertNotNull(ring.present())
            }
            assertEquals(2, ring.allocations)
            // Alternating between two bitmaps, never the one on screen.
            assertEquals(2, addresses.toSet().size)
            addresses.zipWithNext().forEach { (a, b) -> assertNotEquals(a, b) }
            assertSame(shown[0], shown[2])
            assertSame(shown[1], shown[3])
            // The same size again makes nothing new; a new size makes a new pair.
            assertTrue(ring.ensure(64, 36))
            assertEquals(2, ring.allocations)
            assertTrue(ring.ensure(128, 72))
            assertEquals(4, ring.allocations)
            assertEquals(128, ring.width)
        } finally {
            ring.close()
        }
        // Closed: nothing more is written.
        assertNull(ring.write { it.address })
        assertNull(ring.present())
    }

    @Test
    fun theFrameOnScreenIsNotFreedWhileTheNextOneIsDecodedAtANewSize() {
        // Switching a video between Whole and Fill changes the size it is shown at, so the ring makes a new pair of bitmaps.
        // The frame on screen is still drawn until the next one is presented; freeing it as soon as that next frame was
        // decoded (on the decoder's thread) let a repaint read freed pixels, and Skia took the whole app down.
        val ring = CanvasFrameRing()
        try {
            assertTrue(ring.ensure(64, 36))
            assertNotNull(ring.write { })
            val shown = assertNotNull(ring.present())
            assertTrue(ring.ensure(128, 72))
            assertNotNull(ring.write { })
            assertFalse(shown.asSkiaBitmap().isClosed)
            val next = assertNotNull(ring.present())
            assertEquals(128, next.width)
            // Once the new frame is the one on screen, the old pair goes.
            assertTrue(shown.asSkiaBitmap().isClosed)
        } finally {
            ring.close()
        }
    }

    @Test
    fun aRingClosedMidWriteIsFreedOnlyOnceTheWriteEnds() {
        val ring = CanvasFrameRing()
        assertTrue(ring.ensure(32, 18))
        val result = ring.write { target ->
            ring.close()
            // Still writable: the memory is not freed under the decoder.
            val bytes = ByteArray(target.width * target.height * 4)
            target.copyTo(bytes)
        }
        assertEquals(true, result)
        assertNull(ring.write { true })
    }

    @Test
    fun aFrameHandedOutIsASmallCopyNotTheBitmapBeingRewritten() {
        val ring = CanvasFrameRing()
        try {
            assertTrue(ring.ensure(640, 360))
            ring.write { }
            val shown = assertNotNull(ring.present())
            val snapshot = assertNotNull(canvasFrameSnapshot(shown, 128))
            assertEquals(128, snapshot.width)
            assertEquals(72, snapshot.height)
        } finally {
            ring.close()
        }
    }

    @Test
    fun theDecoderWritesStraightIntoTheRingAndRebuildsItsScalerOnlyWhenTheSizeChanges() {
        val decoder = DesktopCanvasDecoder()
        val ring = CanvasFrameRing()
        try {
            decoder.showAt(640, 360)
            assertTrue(decoder.open(MemorySource(clip)).isSuccess)
            assertEquals(640, decoder.width)
            assertEquals(360, decoder.height)
            assertEquals(16f / 9f, decoder.sourceAspect, 0.01f)

            fun decodeOne(): Boolean {
                assertTrue(ring.ensure(decoder.width, decoder.height))
                val decoded = ring.write { decoder.nextFrame(it, loop = true) } == true
                if (decoded) ring.present()
                return decoded
            }
            repeat(6) { assertTrue(decodeOne()) }
            assertEquals(1, decoder.scalerBuilds)
            assertEquals(2, ring.allocations)

            // The same shown size again changes nothing.
            assertFalse(decoder.showAt(640, 360))
            repeat(3) { assertTrue(decodeOne()) }
            assertEquals(1, decoder.scalerBuilds)

            // A new shown size: one new scaler, one new pair of bitmaps, and the frames keep coming.
            assertTrue(decoder.showAt(320, 180))
            assertEquals(320, decoder.width)
            repeat(3) { assertTrue(decodeOne()) }
            assertEquals(2, decoder.scalerBuilds)
            assertEquals(4, ring.allocations)

            // A target of the wrong size is refused rather than overrun.
            val wrong = CanvasFrameRing()
            try {
                assertTrue(wrong.ensure(640, 360))
                assertEquals(false, wrong.write { decoder.nextFrame(it, loop = true) })
            } finally {
                wrong.close()
            }

            // The byte-array path the decorative loops and probes use still works at the new size.
            assertTrue(decoder.nextFrame(ByteArray(320 * 180 * 4)))
        } finally {
            ring.close()
            decoder.close()
        }
    }
}
