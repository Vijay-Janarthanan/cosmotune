package com.cosmotune.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asSkiaPath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.components.MorphPath
import com.cosmotune.ui.components.SuperellipseShape
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SuperellipseShapeTest {
    private val density = Density(2f)

    private fun Path.has(x: Float, y: Float) = asSkiaPath().contains(x, y)

    private fun near(expected: Float, actual: Float, eps: Float = 0.01f) = assertTrue(abs(expected - actual) <= eps, "$expected vs $actual")

    @Test fun theOutlineFitsTheSizeAndIsRoundedAtTheCorners() {
        val outline = SuperellipseShape(10.dp).createOutline(Size(200f, 100f), LayoutDirection.Ltr, density) as Outline.Generic
        val b = outline.path.getBounds()
        near(0f, b.left); near(0f, b.top); near(200f, b.right); near(100f, b.bottom)
        // radius 10 dp at density 2 is 20 px: the top-left corner point of the box is cut away
        assertTrue(!outline.path.has(0.5f, 0.5f), "the corner is rounded off")
        assertTrue(outline.path.has(100f, 50f), "the middle is inside")
    }

    @Test fun aRadiusOfHalfTheShortSideIsAStadiumWithAnExponentOfTwo() {
        val outline = SuperellipseShape(100.dp, exponent = 2f).createOutline(Size(100f, 100f), LayoutDirection.Ltr, density) as Outline.Generic
        // exponent 2 and radius 50 px: a circle, so a point on the diagonal at 70 % of the radius is inside and at 72 % is outside
        val c = 50f
        val d = 0.7071f * 50f
        assertTrue(outline.path.has(c + d * 0.99f, c + d * 0.99f))
        assertTrue(!outline.path.has(c + d * 1.05f, c + d * 1.05f))
    }

    @Test fun equalShapesAreEqual() {
        assertEquals(SuperellipseShape(12.dp), SuperellipseShape(12.dp))
        assertTrue(SuperellipseShape(12.dp) != SuperellipseShape(14.dp))
    }

    @Test fun theMorphPathIsRefilledInPlace() {
        val morph = MorphPath()
        val first = morph.update(100f, 60f, 10f, 4f)
        val second = morph.update(120f, 80f, 40f, 2f)
        assertSame(first, second, "one Path for the whole morph")
        val b = second.getBounds()
        near(120f, b.right); near(80f, b.bottom)
    }
}
