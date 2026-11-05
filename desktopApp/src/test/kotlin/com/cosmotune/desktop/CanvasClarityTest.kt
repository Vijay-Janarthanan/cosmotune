package com.cosmotune.desktop

import com.cosmotune.music.desktop.DesktopCanvasDecoder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CanvasClarityTest {
    @Test fun aFullHdClipIsDecodedWithoutShrinkingItToSleeveResolution() {
        val source = File(javaClass.getResource("/canvas/full-hd.mp4")!!.toURI())
        val decoder = DesktopCanvasDecoder()
        try {
            decoder.open(source.absolutePath).getOrThrow()
            assertEquals(1920, decoder.width)
            assertEquals(1080, decoder.height)
            val pixels = ByteArray(decoder.width * decoder.height * 4)
            assertTrue(decoder.nextFrame(pixels, loop = false))
            assertTrue(pixels.any { it != 0.toByte() })
            var frames = 1
            while (decoder.nextFrame(pixels, loop = false)) {
                frames++
                assertTrue(frames <= 10, "a non-looping clip stops at EOF")
            }
            assertEquals(10, frames, "delayed codec frames are drained at EOF")
            assertTrue(decoder.nextFrame(pixels, loop = true), "a loop can restart after EOF")
            assertTrue(decoder.seekToMs(200))
            assertTrue(decoder.nextFrame(pixels, loop = false))
        } finally {
            decoder.close()
        }
    }
}
