package com.cosmotune.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.ui.nowplaying.WordLifter
import com.cosmotune.ui.nowplaying.edgeOn
import com.cosmotune.ui.nowplaying.featherEdge
import com.cosmotune.ui.nowplaying.sweepWords
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricWord
import org.jetbrains.skia.Bitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The effects on the words of a line, drawn for real on an offscreen Skia surface (red text on white) and read back pixel by
 * pixel: the fill that crosses each timed piece at a steady rate, its feathered edge, a wrapped line filling in reading order,
 * a right-to-left row filling from its right edge, and the word that lifts off the line as it is sung.
 */
@OptIn(ExperimentalComposeUiApi::class)
class LyricsWordEffectsTest {
    private val textStyle = TextStyle(fontSize = 22.sp)

    private class Shot(val bitmap: Bitmap, val layout: TextLayoutResult, val density: Float) {
        /** How red a pixel is, 0 to 255: red text on white loses green and blue as its coverage goes up. */
        fun redness(x: Int, y: Int): Int {
            if (x < 0 || y < 0 || x >= bitmap.width || y >= bitmap.height) return 0
            val argb = bitmap.getColor(x, y)
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            return if (r > 180) 255 - g else 0
        }

        fun isRed(x: Int, y: Int) = redness(x, y) > 190

        /** The x range of solid red pixels in the band of visual line [line], or null when there are none. */
        fun redIn(line: Int): IntRange? {
            val top = layout.getLineTop(line).toInt().coerceAtLeast(0)
            val bottom = layout.getLineBottom(line).toInt().coerceAtMost(bitmap.height)
            var min = Int.MAX_VALUE
            var max = Int.MIN_VALUE
            for (y in top until bottom) for (x in 0 until bitmap.width) if (isRed(x, y)) {
                if (x < min) min = x
                if (x > max) max = x
            }
            return if (max < min) null else min..max
        }

        /** The most red any pixel in the columns [from] until [to] is, over every row. */
        fun strongestRed(from: Int, to: Int): Int {
            var best = 0
            for (y in 0 until bitmap.height) for (x in from until to) best = maxOf(best, redness(x, y))
            return best
        }

        /** The first row with a solid red pixel in the columns [from] until [to], or -1. */
        fun topRedRow(from: Int, to: Int): Int {
            for (y in 0 until bitmap.height) for (x in from until to) if (isRed(x, y)) return y
            return -1
        }
    }

    private enum class Mode { SWEEP, FEATHERED, LIFT }

    private fun shoot(
        line: LyricLine,
        positionMs: Long,
        mode: Mode,
        widthDp: Int = 150,
        density: Float = 1f,
        direction: LayoutDirection = LayoutDirection.Ltr,
        style: TextStyle = textStyle,
    ): Shot {
        val holder = arrayOfNulls<TextLayoutResult>(1)
        val lifter = WordLifter(line)
        val scene = ImageComposeScene(width = ((widthDp + 20) * density).toInt(), height = (300 * density).toInt(), density = Density(density)) {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    val base = Modifier.width(widthDp.dp)
                    val drawn = when (mode) {
                        Mode.SWEEP -> base.drawWithContent { sweepWords(holder[0], line, positionMs, null) }
                        Mode.FEATHERED -> base
                            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                            .drawWithCache {
                                val feather = featherEdge()
                                onDrawWithContent { sweepWords(holder[0], line, positionMs, feather) }
                            }
                        Mode.LIFT -> base.drawWithContent {
                            val layout = holder[0]
                            if (layout == null) drawContent() else lifter.lift(this, layout, positionMs)
                        }
                    }
                    BasicText(
                        text = line.text,
                        style = style,
                        color = ColorProducer { Color.Red },
                        onTextLayout = { holder[0] = it },
                        modifier = drawn,
                    )
                }
            }
        }
        scene.render(0)
        val image = scene.render(16_000_000L)
        val layout = assertNotNull(holder[0], "the text was laid out")
        val shot = Shot(Bitmap.makeFromImage(image), layout, density)
        scene.close()
        return shot
    }

    /** One word per space-separated token, each sung over 250 ms with 50 ms between, from the first. */
    private fun evenlySung(text: String): LyricLine {
        var at = 0L
        val words = text.split(" ").filter { it.isNotEmpty() }.map { token ->
            LyricWord(at, at + 250L, token).also { at += 300L }
        }
        return LyricLine(0L, text, words = words)
    }

    // ---- the edge crosses a piece at a steady rate ---------------------------------------------------------------------------

    private val steady = LyricLine(
        0L, "illWM tail",
        words = listOf(LyricWord(0L, 1_000L, "illWM"), LyricWord(1_200L, 2_000L, "tail")),
    )

    @Test
    fun theEdgeCrossesAWordsWidthAtASteadyRateNotItsLettersAtASteadyRate() {
        val shot = shoot(steady, 500L, Mode.SWEEP, widthDp = 300, style = TextStyle(fontSize = 30.sp))
        val layout = shot.layout
        val from = layout.getHorizontalPosition(0, true)
        val to = layout.getHorizontalPosition(5, true)
        val steadyEdge = from + (to - from) * 0.5f
        // by letters, half way through "illWM" would be a letter and a half in: through "i" and "l" and half of the second "l"
        val byLetters = (layout.getHorizontalPosition(2, true) + layout.getHorizontalPosition(3, true)) / 2f
        assertTrue(steadyEdge - byLetters > 8f, "the two readings differ visibly in this word ($byLetters against $steadyEdge)")
        val red = assertNotNull(shot.redIn(0))
        assertTrue(red.last in (steadyEdge - 3f).toInt()..(steadyEdge + 3f).toInt(), "the edge is half way across the word's width (${red.last}, wanted $steadyEdge)")
    }

    @Test
    fun nothingIsFilledBeforeTheFirstWordAndAWholeLineAfterTheLast() {
        val before = shoot(steady, -50L, Mode.SWEEP)
        assertNull(before.redIn(0))
        val after = shoot(steady, 5_000L, Mode.SWEEP)
        assertNotNull(after.redIn(0))
        val full = shoot(steady, 5_000L, Mode.SWEEP).layout.getLineRight(0).toInt()
        assertTrue(assertNotNull(after.redIn(0)).last >= full - 3)
    }

    @Test
    fun theSpaceBetweenTwoWordsFillsOverThePauseBetweenThem() {
        val shot0 = shoot(steady, 1_000L, Mode.SWEEP, widthDp = 300, style = TextStyle(fontSize = 30.sp))
        val shot1 = shoot(steady, 1_100L, Mode.SWEEP, widthDp = 300, style = TextStyle(fontSize = 30.sp))
        val wordEnd = shot0.layout.getHorizontalPosition(5, true)
        val nextStart = shot0.layout.getHorizontalPosition(6, true)
        // the edge rests on the end of the first word and creeps over the space to the start of the next, never past it
        assertTrue(assertNotNull(shot0.redIn(0)).last <= wordEnd.toInt() + 1)
        assertTrue(assertNotNull(shot1.redIn(0)).last <= nextStart.toInt() + 1)
    }

    // ---- a wrapped line fills in reading order ---------------------------------------------------------------------------------

    private val wrapped = evenlySung("and it lasted the whole afternoon, every window is a weather report")

    @Test
    fun aWrappedLineFillsItsFirstVisualLineBeforeItsSecond() {
        val shot = shoot(wrapped, 150L, Mode.SWEEP)
        assertTrue(shot.layout.lineCount >= 3)
        assertNotNull(shot.redIn(0), "the first line has begun")
        assertNull(shot.redIn(1), "the second has not")
        assertNull(shot.redIn(2), "nor the third")
    }

    @Test
    fun theLinesAboveTheEdgeAreWholeAndTheOneHoldingItIsPartway() {
        val shot = shoot(wrapped, 0L, Mode.SWEEP)
        val rowTwoStart = shot.layout.getLineStart(1)
        // the word that begins the second visual line, a little way into it
        val index = wrapped.wordSpans.indexOfFirst { it.last >= rowTwoStart }
        val position = index * 300L + 100L
        val into = shoot(wrapped, position, Mode.SWEEP)
        val full = shoot(wrapped, 99_000L, Mode.SWEEP)
        val first = assertNotNull(into.redIn(0))
        assertTrue(first.last >= assertNotNull(full.redIn(0)).last - 3, "the first line is drawn to its end")
        assertNotNull(into.redIn(1), "the second has begun")
        assertTrue(assertNotNull(into.redIn(1)).last < assertNotNull(full.redIn(1)).last - 10, "and is only a little way in")
        assertNull(into.redIn(2))
    }

    @Test
    fun theEdgeNeverGoesBackwardsAcrossTheWholeLine() {
        var last = -1
        for (position in 0L..4_000L step 40L) {
            val shot = shoot(wrapped, position, Mode.SWEEP)
            var row = -1
            for (r in 0 until shot.layout.lineCount) if (shot.redIn(r) != null) row = r
            if (row < 0) continue
            val x = assertNotNull(shot.redIn(row)).last
            val key = row * 10_000 + x
            assertTrue(key >= last - 2, "at $position the edge is on row $row at $x, after $last")
            last = maxOf(last, key)
        }
    }

    // ---- the soft edge ---------------------------------------------------------------------------------------------------------

    private val heavy = LyricLine(0L, "WWWWWWWWWWWWWW", words = listOf(LyricWord(0L, 1_000L, "WWWWWWWWWWWWWW")))

    @Test
    fun theLeadingEdgeFadesOutOverThirtyDpInsteadOfEndingOnACut() {
        val style = TextStyle(fontSize = 34.sp)
        val hard = shoot(heavy, 700L, Mode.SWEEP, widthDp = 400, style = style)
        val soft = shoot(heavy, 700L, Mode.FEATHERED, widthDp = 400, style = style)
        val edge = assertNotNull(hard.redIn(0)).last
        // a cut: as strong against the edge as anywhere
        assertTrue(hard.strongestRed(edge - 6, edge) > 200, "the cut edge is solid")
        // a feather: well behind the edge as strong as before, against it nearly gone
        assertTrue(soft.strongestRed(edge - 60, edge - 40) > 200, "well behind the edge it is solid")
        assertTrue(soft.strongestRed(edge - 4, edge) < 120, "against the edge it has faded (${soft.strongestRed(edge - 4, edge)})")
        // and monotonic: the nearer the edge the fainter, band by band
        val bands = (0 until 5).map { band -> soft.strongestRed(edge - 30 + band * 6, edge - 30 + band * 6 + 6) }
        assertTrue(bands.zipWithNext().all { (a, b) -> b <= a + 12 }, "fainter towards the edge: $bands")
    }

    @Test
    fun theFeatherNeverReachesTheLineBelow() {
        val twoLines = LyricLine(0L, "WWWWWWWWWW WWWWWWWWWW", words = listOf(LyricWord(0L, 1_000L, "WWWWWWWWWW"), LyricWord(1_000L, 2_000L, "WWWWWWWWWW")))
        val shot = shoot(twoLines, 1_500L, Mode.FEATHERED, widthDp = 220, style = TextStyle(fontSize = 34.sp))
        assertTrue(shot.layout.lineCount >= 2)
        // the first line is a whole line: no feather on it, because it ends at the wrap, not at the edge
        val firstEnd = shot.layout.getLineRight(0).toInt()
        assertTrue(shot.strongestRed(firstEnd - 10, firstEnd) > 200, "the first line is solid to its end")
    }

    // ---- a right-to-left line fills from its right edge --------------------------------------------------------------------------

    private val hebrew = "שלום עולם שלום עולם"
    private val rtlLine = LyricLine(
        0L, hebrew,
        words = hebrew.split(" ").let { tokens ->
            var at = 0L
            tokens.map { LyricWord(at, at + 400L, it).also { at += 450L } }
        },
    )

    @Test
    fun aRightToLeftLineFillsFromItsRightEdgeAndTheEdgeMovesLeft() {
        val full = shoot(rtlLine, 9_000L, Mode.SWEEP, direction = LayoutDirection.Rtl)
        val fullRed = full.redIn(0)
        // without a font that has Hebrew there is nothing to see and nothing to say
        if (fullRed == null) return
        val early = shoot(rtlLine, 300L, Mode.SWEEP, direction = LayoutDirection.Rtl)
        val later = shoot(rtlLine, 800L, Mode.SWEEP, direction = LayoutDirection.Rtl)
        val a = assertNotNull(early.redIn(0))
        val b = assertNotNull(later.redIn(0))
        assertTrue(a.last >= fullRed.last - 4, "begins at the right edge (${a.last} of ${fullRed.last})")
        assertTrue(b.first < a.first, "and the edge has moved left as more is sung (${b.first} after ${a.first})")
    }

    @Test
    fun theEdgeOfARightToLeftRowIsLeftOfWhereItStarted() {
        val shot = shoot(rtlLine, 0L, Mode.SWEEP, direction = LayoutDirection.Rtl)
        val spans = rtlLine.sweepSpans
        val start = shot.layout.edgeOn(0, 0f, spans)
        val later = shot.layout.edgeOn(0, 2.5f, spans)
        assertTrue(later < start, "$later < $start")
    }

    // ---- the word that is sung lifts off the line -----------------------------------------------------------------------------------

    private val lifting = LyricLine(
        0L, "up high",
        words = listOf(LyricWord(0L, 400L, "up"), LyricWord(500L, 1_500L, "high")),
    )

    @Test
    fun theWordBeingSungLiftsOffTheLineAndTheLineBeforeItBeginsStandsStill() {
        val density = 4f
        val still = shoot(lifting, -100L, Mode.LIFT, density = density, style = TextStyle(fontSize = 22.sp))
        val during = shoot(lifting, 1_000L, Mode.LIFT, density = density, style = TextStyle(fontSize = 22.sp))
        val layout = still.layout
        val highFrom = layout.getBoundingBox(3).left.toInt()
        val highTo = layout.getBoundingBox(6).right.toInt()
        val upFrom = layout.getBoundingBox(0).left.toInt()
        val upTo = layout.getBoundingBox(1).right.toInt()
        val flatTop = still.topRedRow(highFrom, highTo)
        val liftedTop = during.topRedRow(highFrom, highTo)
        assertTrue(flatTop > 0 && liftedTop > 0, "both are drawn")
        // two dp is eight pixels at this density, and the word has risen most of the way
        assertTrue(flatTop - liftedTop >= 4, "'high' is up (${flatTop - liftedTop} px)")
        assertTrue(flatTop - liftedTop <= 9, "and not by more than its peak")
        assertEquals(still.topRedRow(upFrom, upTo).toFloat(), during.topRedRow(upFrom, upTo).toFloat(), 1.5f, "'up' is behind it and has all but settled")
    }

    @Test
    fun aLineWithNothingOffTheFloorIsDrawnAsItIs() {
        val plain = shoot(lifting, -100L, Mode.LIFT)
        val reference = shoot(lifting, 99_000L, Mode.SWEEP)
        // every pixel of the flat slices is a pixel of the line drawn whole
        var differing = 0
        for (y in 0 until plain.bitmap.height) for (x in 0 until plain.bitmap.width) {
            if ((plain.redness(x, y) > 190) != (reference.redness(x, y) > 190)) differing++
        }
        assertTrue(differing == 0 || differing < 25, "$differing pixels differ")
    }
}
