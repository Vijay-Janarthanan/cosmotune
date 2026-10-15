package com.cosmotune.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp

/**
 * A soft shadow under a surface, drawn by the platform's own elevation shadow (Android's RenderNode on the phone, Skia's
 * shadow on the desktop) through [graphicsLayer]: [spot] is the key light's shadow, [ambient] the soft one all round. Both
 * colours are read in the layer block, which Compose observes, so a palette fade moves the shadow with it and recomposes nothing.
 *
 * This is used instead of `Modifier.dropShadow`: that modifier draws its blur into a layer whose bounds stop one blur radius
 * outside the shape, which cuts the Gaussian tail at about 1.7 sigma and leaves a faint rectangle on a light ground (seen
 * in a headless render of the cover on the light theme). The elevation shadow has no such edge. It needs an opaque or
 * translucent convex outline, which every shape here is, and it never clips the content ([graphicsLayer]'s `clip` stays false).
 * Below Android 9 the spot colour is ignored and the shadow is black, which is the same shape of shadow.
 */
internal fun Modifier.liftShadow(shape: Shape, elevation: Dp, spot: () -> Color, ambient: () -> Color): Modifier =
    graphicsLayer {
        shadowElevation = elevation.toPx()
        this.shape = shape
        clip = false
        spotShadowColor = spot()
        ambientShadowColor = ambient()
    }

/**
 * What [liftShadow] draws, for a surface whose own layer also moves: a [MorphBox] applies it in the layer block that scales it, so the
 * shadow shrinks with a press instead of showing round the edges of the squished surface. The colours are read in that layer block.
 */
internal class Lift(val shape: Shape, val elevation: Dp, val spot: () -> Color, val ambient: () -> Color)

/** [lift] in this layer: its elevation, shape and the two colours, read now; never a clip. */
internal fun GraphicsLayerScope.applyLift(lift: Lift) {
    shadowElevation = lift.elevation.toPx()
    shape = lift.shape
    clip = false
    spotShadowColor = lift.spot()
    ambientShadowColor = lift.ambient()
}
