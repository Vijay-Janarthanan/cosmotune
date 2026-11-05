package com.cosmotune.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.ui.nowplaying.sweepFill
import org.jetbrains.skia.Bitmap
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The fill of the line being sung, drawn for real on an offscreen Skia surface: red text on white, clipped by [sweepFill] at a
 * given fraction, and the pixels read back. What this pins is what the brief's one-line clip (`size.width * fraction`) would get
 * wrong for a line that wraps: the visual lines fill in reading order, one after the other.
 */
@OptIn(ExperimentalComposeUiApi::class)
class LyricsSweepTest {
    private val text = "and it lasted the whole afternoon, every window is a weather report"
    private val widthDp = 150

    private class Shot(val bitmap: Bitmap, val layout: TextLayoutResult) {
        private fun isRed(argb: Int): Boolean {
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            return r > 190 && g < 90 && b < 90
        }

        /** The x range of red pixels in the band of visual line [line], or null when there are none. */
        fun redIn(line: Int): IntRange? {
            val top = layout.getLineTop(line).toInt().coerceAtLeast(0)
            val bottom = layout.getLineBottom(line).toInt().coerceAtMost(bitmap.height)
            var min = Int.MAX_VALUE
            var max = Int.MIN_VALUE
            for (y in top until bottom) for (x in 0 until bitmap.width) {
                if (isRed(bitmap.getColor(x, y))) {
                    if (x < min) min = x
                    if (x > max) max = x
                }
            }
            return if (max < min) null else min..max
        }
    }

    private fun shoot(fraction: Float): Shot {
        val holder = arrayOfNulls<TextLayoutResult>(1)
        val scene = ImageComposeScene(width = widthDp + 20, height = 300, density = Density(1f)) {
            Box(Modifier.fillMaxSize().background(Color.White)) {
                BasicText(
                    text = text,
                    style = TextStyle(fontSize = 22.sp),
                    color = ColorProducer { Color.Red },
                    onTextLayout = { holder[0] = it },
                    modifier = Modifier.width(widthDp.dp).drawWithContent { sweepFill(holder[0], fraction) },
                )
            }
        }
        scene.render(0)
        val image = scene.render(16_000_000L)
        val layout = assertNotNull(holder[0], "the text was laid out")
        val shot = Shot(Bitmap.makeFromImage(image), layout)
        scene.close()
        return shot
    }

    @Test
    fun thePhraseWrapsOntoSeveralLinesAtThisWidth() {
        assertTrue(shoot(1f).layout.lineCount >= 3, "the text needs three lines or more to say anything about reading order")
    }

    @Test
    fun nothingIsDrawnBeforeTheFirstWord() {
        val shot = shoot(0f)
        for (line in 0 until shot.layout.lineCount) assertNull(shot.redIn(line), "line $line")
    }

    @Test
    fun aWholeLineIsDrawnAtOne() {
        val shot = shoot(1f)
        for (line in 0 until shot.layout.lineCount) assertNotNull(shot.redIn(line), "line $line")
    }

    @Test
    fun aWrappedLineFillsTheFirstVisualLineBeforeTheSecond() {
        val shot = shoot(0.1f)
        assertNotNull(shot.redIn(0), "the first line has begun")
        assertNull(shot.redIn(1), "the second has not")
        assertNull(shot.redIn(2), "nor the third")
    }

    @Test
    fun theLinesAboveTheEdgeAreWholeAndTheOneHoldingItIsPartway() {
        val whole = shoot(1f)
        val length = text.length
        val into = whole.layout.getLineStart(1) + 3
        val shot = shoot(into.toFloat() / length)
        val first = assertNotNull(shot.redIn(0))
        val full = assertNotNull(whole.redIn(0))
        assertTrue(first.last >= full.last - 2, "line 0 is drawn to its end (${first.last} of ${full.last})")
        val second = assertNotNull(shot.redIn(1), "line 1 has begun")
        val secondFull = assertNotNull(whole.redIn(1))
        assertTrue(second.last < secondFull.last - 20, "line 1 is only a little way in (${second.last} of ${secondFull.last})")
        assertNull(shot.redIn(2))
    }

    @Test
    fun theEdgeMovesRightAsMoreIsSung() {
        val a = assertNotNull(shoot(0.04f).redIn(0)).last
        val b = assertNotNull(shoot(0.10f).redIn(0)).last
        val c = assertNotNull(shoot(0.16f).redIn(0)).last
        assertTrue(a < b && b < c, "$a < $b < $c")
    }

    @Test
    fun theFillStartsAtTheStartEdgeOfTheLine() {
        val shot = shoot(0.1f)
        val first = assertNotNull(shot.redIn(0))
        assertTrue(first.first <= shot.layout.getLineLeft(0).toInt() + 4, "the fill begins at the line's left edge (${first.first})")
    }
}
