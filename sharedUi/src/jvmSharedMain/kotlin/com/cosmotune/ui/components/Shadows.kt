package com.cosmotune.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
