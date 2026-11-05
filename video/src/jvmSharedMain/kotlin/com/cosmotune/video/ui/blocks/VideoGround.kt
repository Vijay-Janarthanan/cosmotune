package com.cosmotune.video.ui.blocks

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.theme.LocalCosmoColors

/** The ground a sticky or floating row of a Video page is painted with (the page's flat `bg`), so what scrolls under it is not seen through it. */
@Composable
internal fun Modifier.videoSolidGround(): Modifier {
    val colors = LocalCosmoColors.current
    return drawBehind { drawRect(colors.live(CoverField.BACKGROUND_TOP)) }
}
