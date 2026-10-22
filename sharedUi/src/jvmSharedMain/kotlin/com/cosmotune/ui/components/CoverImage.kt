package com.cosmotune.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.cosmotune.design.CoverArt
import com.cosmotune.design.TileDepth
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.theme.CosmoColors
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.music.data.model.artworkAt

/**
 * A cover: the picture at [url] (null shows the placeholder), [size] square, clipped by superellipse corners of [radius],
 * with the cover's own shadow when [shadow] is true (the playing cover; list covers have none) and a fine light rim.
 * The picture is asked for at the next rung of [CoverArt]'s ladder (160, 320, 480, 720 or 1200 px), so a row, a card and the
 * player share image cache entries.
 */
@Composable
fun CoverImage(
    url: String?,
    size: Dp,
    radius: Dp,
    modifier: Modifier = Modifier,
    shadow: Boolean = true,
    contentDescription: String? = null,
) {
    val density = LocalDensity.current.density
    val px = remember(size, density) { CoverArt.px(size.value, density) }
    CoverFrame(size, radius, modifier, shadow) {
        AsyncImage(
            model = url.artworkAt(px),
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * What a cover looks like, whatever is inside it: [size] square, superellipse corners of [radius], a placeholder in
 * `surfaceVariant` behind [content], the shadow (see [coverShadows]) when [shadow] is true, and the light rim on top.
 * [CoverImage] fills it with a picture; the look preview fills it with stripes.
 */
@Composable
fun CoverFrame(
    size: Dp,
    radius: Dp,
    modifier: Modifier = Modifier,
    shadow: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = LocalCosmoColors.current
    val shape = remember(radius) { SuperellipseShape(radius) }
    Box(
        modifier
            .size(size)
            .then(if (shadow) Modifier.coverShadows(shape, colors) else Modifier)
            .clip(shape)
            // Read while drawing: the placeholder follows a palette fade.
            .drawBehind { drawRect(colors.live(CoverField.SURFACE_VARIANT)) },
    ) {
        content()
        Box(Modifier.matchParentSize().coverRim(shape, colors))
    }
}

/**
 * The shadow under the cover (`--cover-shadow`, `tokens-v3bc.css`): a lift of [COVER_ELEVATION] whose key shadow is the cover's
 * own colour, the palette's `shadow` at full strength (the platform scales a spot shadow down to about a fifth, so the token's own
 * 65 % would not show), resolved in the layer block so it follows a palette fade, over a soft black ambient shadow. See [liftShadow] for why this is not `dropShadow`.
 */
private fun Modifier.coverShadows(shape: Shape, colors: CosmoColors): Modifier =
    liftShadow(shape, COVER_ELEVATION, spot = { colors.live(CoverField.SHADOW).copy(alpha = 1f) }, ambient = { Color.Black.copy(alpha = 0.45f) })

private val COVER_ELEVATION = 14.dp

/**
 * The cover's rim (`.cv::after`): an inner ring of white 14 % (0.5 dp) and a fine top line of white 16 %. Drawn on top of the picture.
 * In the light theme a white ring vanishes on a pale cover over a pale page, so it also takes the tile's hairline (`--hl` light: the
 * ink at 6 %), read while drawing; reading the palette in the cache rebuilds it when the theme flips. The strokes along the
 * superellipse come from masks made once per size ([outlineStroke]): stroked directly, every cover of a scrolling page cost the
 * RenderThread a fresh mask on every frame.
 */
private fun Modifier.coverRim(shape: Shape, colors: CosmoColors): Modifier = drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val ring = outlineStroke(shape, outline, size, layoutDirection, 1.dp.toPx())          // the clip shows half of it: 0.5 dp
    val top = outlineStroke(shape, outline, size, layoutDirection, 2.dp.toPx(), fadePx = topLightFade(size.height))  // 1 dp inside
    keepRedrawnWith(colors.palette)
    val light = !colors.dark
    onDrawBehind {
        ring.draw(this, Color.White.copy(alpha = 0.14f))
        top.draw(this, Color.White.copy(alpha = 0.16f))
        if (light) ring.draw(this, colors.live(CoverField.TEXT_PRIMARY).copy(alpha = TileDepth.hairlineAlpha(dark = false)))
    }
}
