package com.cosmotune.video.ui.blocks

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.theme.CosmoColors
import com.cosmotune.ui.theme.LocalCosmoColors

/*
 * Video's ground. The video apps are dark first: in the dark theme every Video page stands on a near-black (#0F0F0F) instead of the
 * tinted ground of the music side, with chips and cards a wash of white over it (the pill's 10 % of white on it comes to #272727). In
 * the light theme the page's own ground is kept, so light stays as legible as it was. Read while drawing, so a palette fade repaints.
 */

/** Video's page ground in the dark theme. */
internal val VIDEO_DARK_GROUND = Color(0xFF0F0F0F)

/** The ground of Video's pages for [colors]: near-black in the dark theme, else the palette's [lightField]. */
internal fun videoGroundColor(colors: CosmoColors, lightField: Int): Color =
    if (colors.dark) VIDEO_DARK_GROUND else colors.live(lightField)

/** Video's page ground behind the content; in the light theme it paints nothing, the page's own ground shows. */
@Composable
internal fun Modifier.videoGround(): Modifier {
    val colors = LocalCosmoColors.current
    return drawBehind { if (colors.dark) drawRect(VIDEO_DARK_GROUND) }
}

/** The ground a sticky or floating row of a Video page is painted with, so what scrolls under it is not seen through it. */
@Composable
internal fun Modifier.videoSolidGround(): Modifier {
    val colors = LocalCosmoColors.current
    return drawBehind { drawRect(videoGroundColor(colors, CoverField.BACKGROUND_TOP)) }
}
