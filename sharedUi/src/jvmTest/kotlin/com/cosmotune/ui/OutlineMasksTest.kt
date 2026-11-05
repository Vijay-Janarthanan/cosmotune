package com.cosmotune.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.components.OutlineMasks
import com.cosmotune.ui.components.SuperellipseShape
import com.cosmotune.ui.components.outlineStroke
import com.cosmotune.ui.components.topLightBrush
import com.cosmotune.ui.components.topLightFade
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rims, edges and glass edges along a superellipse are drawn from masks made once (P4-21): stroked directly, every one of
 * them cost the RenderThread a fresh coverage mask on every frame of a scroll. The mask has to draw the same pixels as the stroke
 * it replaces, under the same clip.
 */
class OutlineMasksTest {
    private val density = Density(3f)
    private val ltr = LayoutDirection.Ltr

    @AfterTest fun forget() = OutlineMasks.clear()

    /** Draws [block] under [shape]'s clip into a transparent picture of [size] and returns its pixels. */
    private fun render(shape: Shape, size: Size, block: DrawScope.(Outline) -> Unit): IntArray {
        val image = ImageBitmap(size.width.toInt(), size.height.toInt())
        val outline = shape.createOutline(size, ltr, density)
        CanvasDrawScope().draw(density, ltr, Canvas(image), size) {
            val path = (outline as Outline.Generic).path
            clipPath(path) { block(outline) }
        }
        return IntArray(image.width * image.height).also { image.readPixels(it) }
    }

    /** Compares the two pictures channel by channel, premultiplied (what lands on the screen), and returns the largest difference. */
    private fun assertSamePixels(direct: IntArray, masked: IntArray, what: String) {
        var worst = 0
        var at = -1
        var drawn = 0
        for (i in direct.indices) {
            val da = direct[i] ushr 24
            val ma = masked[i] ushr 24
            for (shift in intArrayOf(24, 16, 8, 0)) {
                val d = if (shift == 24) da else ((direct[i] ushr shift) and 0xFF) * da / 255
                val m = if (shift == 24) ma else ((masked[i] ushr shift) and 0xFF) * ma / 255
                if (abs(d - m) > worst) { worst = abs(d - m); at = i }
            }
            if (da != 0) drawn++
        }
        assertTrue(drawn > 100, "$what: the stroke drew something ($drawn pixels)")
        assertTrue(worst <= 3, "$what: the mask draws the stroke's pixels (largest difference $worst of 255 at $at: " +
            "${direct.getOrNull(at)?.toUInt()?.toString(16)} vs ${masked.getOrNull(at)?.toUInt()?.toString(16)})")
    }

    @Test fun aCoverRimFromTheMaskIsTheStrokeItReplaces() {
        val shape = SuperellipseShape(14.dp)
        val size = Size(228f, 228f)
        val ring = Color.White.copy(alpha = 0.14f)
        val direct = render(shape, size) { drawOutline(it, ring, style = Stroke(1.dp.toPx())) }
        val masked = render(shape, size) { outlineStroke(shape, it, size, ltr, 1.dp.toPx()).draw(this, ring) }
        assertSamePixels(direct, masked, "ring")
    }

    @Test fun theTopLightFadesTheSameWayFromTheMask() {
        val shape = SuperellipseShape(28.dp)
        val size = Size(540f, 516f)
        val white = Color.White.copy(alpha = 0.8f)
        val direct = render(shape, size) { drawOutline(it, topLightBrush(white, size.height), style = Stroke(2.dp.toPx())) }
        val masked = render(shape, size) { outlineStroke(shape, it, size, ltr, 2.dp.toPx(), fadePx = topLightFade(size.height)).draw(this, white) }
        assertSamePixels(direct, masked, "top light")
    }

    @Test fun aColourOfTheMomentTintsTheSameMask() {
        val shape = SuperellipseShape(24.dp)
        val size = Size(336f, 336f)
        val ink = Color(0xFF2A1F3D).copy(alpha = 0.06f)
        val direct = render(shape, size) { drawOutline(it, ink, style = Stroke(1.dp.toPx())) }
        val masked = render(shape, size) { outlineStroke(shape, it, size, ltr, 1.dp.toPx()).draw(this, ink) }
        assertSamePixels(direct, masked, "hairline in the ink")
    }

    @Test fun oneMaskServesEveryOutlineOfTheSameShapeSizeAndStroke() {
        val shape = SuperellipseShape(14.dp)
        val size = Size(228f, 228f)
        val outline = shape.createOutline(size, ltr, density)
        val first = density.outlineStroke(shape, outline, size, ltr, 3f)
        val kept = OutlineMasks.keptBytes
        assertEquals(228L * 228L, kept, "one alpha mask, a byte a pixel")
        density.outlineStroke(SuperellipseShape(14.dp), shape.createOutline(size, ltr, density), size, ltr, 3f)
        assertEquals(kept, OutlineMasks.keptBytes, "an equal shape at the same size and width makes no second mask")
        density.outlineStroke(shape, outline, size, ltr, 6f)
        assertEquals(2 * kept, OutlineMasks.keptBytes, "another width is another mask")
        assertTrue(first.masked)
    }

    @Test fun aRoundedRectangleIsStillStrokedDirectly() {
        val shape = RoundedCornerShape(32.dp)
        val size = Size(900f, 192f)
        val stroke = density.outlineStroke(shape, shape.createOutline(size, ltr, density), size, ltr, 6f)
        assertFalse(stroke.masked, "the GPU strokes a rounded rectangle analytically: no mask needed")
        assertEquals(0L, OutlineMasks.keptBytes)
    }

    @Test fun theMasksStayInsideTheirBudget() {
        assertEquals(6L * 1024 * 1024, OutlineMasks.BUDGET_BYTES)
        val shape = SuperellipseShape(14.dp)
        for (side in 200 until 600 step 4) {
            val size = Size(side.toFloat(), side.toFloat())
            density.outlineStroke(shape, shape.createOutline(size, ltr, density), size, ltr, 3f)
        }
        assertTrue(OutlineMasks.keptBytes <= OutlineMasks.BUDGET_BYTES, "kept ${OutlineMasks.keptBytes} bytes")
    }

    @Test fun theRimsEdgesAndGlassEdgesNoLongerStrokeTheOutlineDirectly() {
        for (file in listOf("components/CoverImage.kt", "components/TileSurface.kt", "components/Glass.kt")) {
            val src = SourceContract.code(SourceContract.read("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/$file"))
            assertFalse(Regex("""drawOutline\([^)]*style\s*=""").containsMatchIn(src), "$file strokes an outline directly")
            assertTrue("outlineStroke(shape, outline, size, layoutDirection" in src, "$file draws its strokes through outlineStroke")
        }
    }
}
