package com.cosmotune.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cosmotune.design.CssGradient
import com.cosmotune.design.GlassMode
import com.cosmotune.design.GlassPolicy
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.theme.LocalCosmoColors
import com.music.bitchord.ui.components.optimizedHazeEffect
import com.music.bitchord.ui.player.PlayerPlatform
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import kotlin.math.max
import kotlin.math.min

/**
 * The glass surface of everything that floats above content or video (layer C of the B+C system): the tab bar, the mini
 * player, sheets, dialogs, toasts, menus, the desktop player. Draws, in this order:
 *
 * 1. a soft shadow, a lift of 12 dp in black at 50 % (light theme 22 %), outside the shape, when [shadow] is true ([liftShadow]);
 * 2. the backdrop, blurred 22 dp (32 dp on the desktop), by Haze through [hazeState]; or, when [GlassPolicy] says solid, nothing;
 * 3. the `surfaceHigh` fill: the glass 52 % (light 62 %) over a blur, a nearly solid 94 % without one;
 * 4. the cover tint: `accentContainer` at 46 % along 100 degrees, gone by 70 % of the width, or the caller's own [tint];
 * 5. the content;
 * 6. the specular edge: an inner 1 dp ring and a fine top line that follows the corners.
 *
 * Colours are resolved from the palette on screen this frame, inside draw lambdas, so a cover change fades the glass with the
 * rest of the app and recomposes nothing; nothing is cached at theme creation.
 *
 * **The policy.** Glass is the solid tint, never blurred, below Android 12, with "Reduce dynamic blur", on low-RAM phones,
 * when [overVideo] is true (Haze would sample the playing video) and when there is no [hazeState] (Review Focus 5). **At
 * most two glass layers may blur on one screen**: the phone's shell has two (tab bar and mini player); a sheet or a menu that
 * opens over them is the third and passes `hazeState = null` to draw solid while they blur.
 *
 * **Not done: saturation.** The spec asks for the backdrop at saturation x 1.6. Neither Haze version this app runs (1.3.1 on
 * Android and in the compile classpath, 1.7.2 on the Windows desktop) has a colour-matrix hook (`HazeEffectScope`, `HazeStyle`
 * and `HazeTint` carry colour, blend mode and brush only), so the blur is as sampled and the cover tint carries the colour.
 *
 * [tint] replaces the cover tint (see the mini player's progress or the Now Playing cluster, which tint with the cover
 * gradient themselves); it is called in the draw phase.
 */
@Composable
fun Modifier.cosmoGlass(
    hazeState: HazeState?,
    shape: Shape,
    overVideo: Boolean = false,
    tint: (DrawScope.() -> Brush)? = null,
    shadow: Boolean = true,
): Modifier {
    val colors = LocalCosmoColors.current
    val palette = colors.palette            // the palette being shown: changes once per cover
    val dark = colors.dark                  // read after the palette, which it travels with
    val sdk = platformSdkInt
    val blurred = rememberGlassMode(hasBackdrop = hazeState != null, overVideo = overVideo) == GlassMode.BLUR
    val fillAlpha = GlassPolicy.fillAlpha(if (blurred) GlassMode.BLUR else GlassMode.SOLID, dark)

    var glass: Modifier = this
    if (shadow) {
        val ink = Color.Black.copy(alpha = (GlassPolicy.shadowAlpha(dark) * 1.8f).coerceAtMost(1f))
        glass = glass.liftShadow(shape, GlassPolicy.SHADOW_ELEVATION_DP.dp, spot = { ink }, ambient = { ink.copy(alpha = ink.alpha * 0.4f) })
    }
    glass = glass.clip(shape)
    if (blurred) {
        val style = remember(palette, sdk) {
            HazeStyle(
                backgroundColor = Color(palette.backgroundBottom),
                tints = emptyList(),
                blurRadius = GlassPolicy.blurDp(sdk).dp,
                noiseFactor = 0f,
                // Only if Haze cannot blur after all; the policy has already chosen solid for the known cases.
                fallbackTint = HazeTint(Color(palette.surfaceHigh).copy(alpha = GlassPolicy.SOLID_ALPHA)),
            )
        }
        glass = glass.optimizedHazeEffect(hazeState!!, style)
    }
    return glass.drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        // The live colours are read here, so the cache is rebuilt only while a palette fade runs.
        val fill = colors.live(CoverField.SURFACE_HIGH).copy(alpha = fillAlpha)
        val coverTint: Brush? = if (tint == null) {
            val container = colors.live(CoverField.ACCENT_CONTAINER)
            val line = FloatArray(4)
            CssGradient.line(size.width, size.height, GlassPolicy.COVER_TINT_ANGLE_DEG, line)
            Brush.linearGradient(
                0f to container.copy(alpha = container.alpha * GlassPolicy.COVER_TINT_ALPHA),
                GlassPolicy.COVER_TINT_FADE_END to container.copy(alpha = 0f),
                1f to container.copy(alpha = 0f),
                start = Offset(line[0], line[1]),
                end = Offset(line[2], line[3]),
            )
        } else null
        val ring = (if (dark) Color.White else Color.Black).copy(alpha = GlassPolicy.edgeRingAlpha(dark))
        val top = topLightBrush(Color.White.copy(alpha = GlassPolicy.edgeTopAlpha(dark)), size.height)
        // The shape clips every stroke to its inside, so a 2 dp stroke shows 1 dp.
        val stroke = Stroke(INNER_EDGE_STROKE.toPx())
        onDrawWithContent {
            drawRect(fill)
            if (coverTint != null) drawRect(coverTint) else drawRect(tint!!.invoke(this))
            drawContent()
            drawOutline(outline, ring, style = stroke)
            drawOutline(outline, top, style = stroke)
        }
    }
}

/**
 * What [cosmoGlass] will draw with these inputs: [GlassPolicy.mode] with the device's API level, the "Reduce dynamic blur"
 * setting and the low-RAM flag; solid when there is no [hasBackdrop] (no Haze state to sample).
 */
@Composable
internal fun rememberGlassMode(hasBackdrop: Boolean, overVideo: Boolean): GlassMode {
    val reduceBlur by PlayerPlatform.host.settings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val lowRam = rememberLowRamDevice()
    return if (hasBackdrop) GlassPolicy.mode(platformSdkInt, reduceBlur, lowRam, overVideo) else GlassMode.SOLID
}

/** A 1 dp inner line (drawn as a 2 dp stroke under the shape's clip). */
internal val INNER_EDGE_STROKE = 2.dp

/** The fine highlight along the top edge of a surface: [color] at the top, gone within 20 dp (or half the height), so it follows the corners and vanishes down the sides. */
internal fun androidx.compose.ui.unit.Density.topLightBrush(color: Color, heightPx: Float): Brush {
    val fade = max(1f, min(heightPx / 2f, 20.dp.toPx()))
    return Brush.verticalGradient(0f to color, 1f to color.copy(alpha = 0f), startY = 0f, endY = fade)
}
