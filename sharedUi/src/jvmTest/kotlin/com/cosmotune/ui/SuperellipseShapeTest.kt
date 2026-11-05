package com.cosmotune.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asSkiaPath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.cosmotune.design.Superellipse
import com.cosmotune.ui.components.MorphPath
import com.cosmotune.ui.components.SuperellipseShape
import com.cosmotune.ui.components.forShadow
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

    // ---- the shadow's stand-in (P4-21) ----

    @Test fun theShadowIsCastFromARoundedRectangleThroughTheSquirclesFortyFiveDegreePoint() {
        // 10 dp at density 2 is 20 px
        val rr = (SuperellipseShape(10.dp).shadowShape.createOutline(Size(200f, 100f), LayoutDirection.Ltr, density) as Outline.Rounded).roundRect
        near(Superellipse.shadowRadius(20f, 4f), rr.topLeftCornerRadius.x)
        near(Superellipse.shadowRadius(20f, 4f), rr.bottomRightCornerRadius.y)
        near(0f, rr.left); near(0f, rr.top); near(200f, rr.right); near(100f, rr.bottom)
    }

    @Test fun theStandInClampsTheRadiusAsTheSuperellipseDoes() {
        // 100 dp (200 px) on a 100 x 60 box: the superellipse's corner is clamped to 30 px, and the stand-in is made from that
        val rr = (SuperellipseShape(100.dp).shadowShape.createOutline(Size(100f, 60f), LayoutDirection.Ltr, density) as Outline.Rounded).roundRect
        near(Superellipse.shadowRadius(30f, 4f), rr.topLeftCornerRadius.x)
    }

    @Test fun onlyASuperellipseIsStoodInFor() {
        val squircle = SuperellipseShape(12.dp)
        assertSame(squircle.shadowShape, squircle.forShadow())
        val round = RoundedCornerShape(8.dp)
        assertSame(round, round.forShadow())
    }

    @Test fun everyLiftCastsItsShadowFromTheStandIn() {
        val shadows = SourceContract.code(SourceContract.read("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/components/Shadows.kt"))
        assertTrue("this.shape = shape.forShadow()" in shadows, "liftShadow")
        assertTrue("shape = lift.shape.forShadow()" in shadows, "applyLift (a MorphBox's lift)")
    }
}
