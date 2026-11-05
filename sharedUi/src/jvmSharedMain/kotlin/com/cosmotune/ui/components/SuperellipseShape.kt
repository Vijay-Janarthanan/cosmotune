package com.cosmotune.ui.components

import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import com.cosmotune.design.Superellipse
import kotlin.math.min

/** Containers and covers: superellipse corners (02 section 3). The outline is built once per size by Compose. */
class SuperellipseShape(private val radius: Dp, private val exponent: Float = 4f) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val points = FloatArray(Superellipse.BUFFER_SIZE)
        val count = Superellipse.outline(size.width, size.height, with(density) { radius.toPx() }, exponent, points)
        return Outline.Generic(Path().also { it.fillFrom(points, count) })
    }

    /**
     * The outline this shape casts its elevation shadow from: a rounded rectangle through the superellipse's own 45 degree point
     * ([Superellipse.shadowRadius], after the same clamp to half the shorter side). The surface itself keeps its superellipse; under
     * a soft shadow the two outlines cannot be told apart, and the platform draws a rounded rectangle's shadow analytically where a
     * path's is tessellated on the CPU again on every frame it moves (P4-21).
     */
    val shadowShape: Shape = ShadowStandIn(radius, exponent)

    override fun equals(other: Any?): Boolean = other is SuperellipseShape && other.radius == radius && other.exponent == exponent
    override fun hashCode(): Int = 31 * radius.hashCode() + exponent.hashCode()
}

private class ShadowStandIn(private val radius: Dp, private val exponent: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = Superellipse.shadowRadius(with(density) { radius.toPx() }.coerceIn(0f, min(size.width, size.height) / 2f), exponent)
        return Outline.Rounded(RoundRect(0f, 0f, size.width, size.height, r, r))
    }

    override fun equals(other: Any?): Boolean = other is ShadowStandIn && other.radius == radius && other.exponent == exponent
    override fun hashCode(): Int = 31 * radius.hashCode() + exponent.hashCode() + 1
}

/** The shape the platform casts the shadow of: a superellipse's [SuperellipseShape.shadowShape], any other shape itself. */
internal fun Shape.forShadow(): Shape = if (this is SuperellipseShape) shadowShape else this

/** One Path refilled in place every frame of a morph: no allocation (handoff pitfall "Superellipse clipping"). */
class MorphPath {
    private val points = FloatArray(Superellipse.BUFFER_SIZE)
    val path = Path()
    fun update(width: Float, height: Float, radius: Float, exponent: Float): Path {
        path.fillFrom(points, Superellipse.outline(width, height, radius, exponent, points)); return path
    }
}

internal fun Path.fillFrom(points: FloatArray, count: Int) {
    reset(); moveTo(points[0], points[1]); for (i in 1 until count) lineTo(points[2 * i], points[2 * i + 1]); close()
}
