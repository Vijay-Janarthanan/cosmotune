package com.cosmotune.canvas

import com.opencanvas.core.stream.PreviewFrames
import com.opencanvas.core.stream.PreviewImage
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The preview-frame check, against frame changes like those measured on real uploads (see [PreviewFrames.STILL_MEDIAN_CHANGE]). */
class PreviewFramesTest {
    private fun frames(n: Int, step: Double, seed: Int = 1): List<DoubleArray> {
        val random = Random(seed)
        var current = DoubleArray(64) { 120.0 }
        return List(n) { i ->
            if (i == 0) DoubleArray(64) { 10.0 } // an opening card, left out
            else current.map { (it + (random.nextDouble() * 2 - 1) * step * 2).coerceIn(0.0, 255.0) }.toDoubleArray().also { current = it }
        }
    }

    @Test fun aPosterWithCompressionNoiseIsStill() {
        assertEquals(true, PreviewFrames.isStill(frames(60, 3.5)), "like a label's poster (median 3.5)")
        assertEquals(true, PreviewFrames.isStill(frames(60, 6.2)), "like an artist's poster (median 6.2)")
    }

    @Test fun aVideoSongMovesEvenADarkOne() {
        assertEquals(false, PreviewFrames.isStill(frames(25, 16.6)), "a dark video song (median 16.6)")
        assertEquals(false, PreviewFrames.isStill(frames(70, 60.0)), "a bright, busy one")
    }

    @Test fun tooFewFramesDecideNothing() {
        assertNull(PreviewFrames.isStill(frames(4, 50.0)))
    }

    @Test fun theSheetIsTheFirstLevelWithFramesAtLeast80Wide() {
        val spec = "https://i.ytimg.com/sb/abc/storyboard3_L\$L/\$N.jpg?sqp=x|48#27#100#10#10#0#default#rs\$A|80#45#67#10#10#5000#M\$M#rs\$B|160#90#67#5#5#5000#M\$M#rs\$C"
        val sheet = PreviewFrames.sheet(spec)!!
        assertEquals("https://i.ytimg.com/sb/abc/storyboard3_L1/M0.jpg?sqp=x&sigh=rs\$B", sheet.url)
        assertEquals(80, sheet.frameWidth); assertEquals(45, sheet.frameHeight); assertEquals(67, sheet.frames)
        assertNull(PreviewFrames.sheet("not a spec"))
    }

    @Test fun gridsReadEachFrameOfTheSheet() {
        val sheet = PreviewFrames.Sheet("u", 10, 10, 3, 2, 2)
        val pixels = IntArray(20 * 20) { i -> if ((i % 20) < 10) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        val grids = PreviewFrames.grids(PreviewImage(20, 20, pixels), sheet)
        assertEquals(3, grids.size)
        assertTrue(grids[0].all { it < 1.0 }, "left frame black"); assertTrue(grids[1].all { it > 254.0 }, "right frame white")
        assertFalse(grids[2].any { it > 1.0 }, "second row, left")
    }
}
