package com.cosmotune.ui.blocks

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import com.cosmotune.ui.theme.LocalCosmoColors

/**
 * The fill of one of the few real cards (DESIGN_SYSTEM.md P4, 2.5): flat `surface1`, clipped to [shape]. No gradient, no shadow, no rim:
 * the tier step is what separates it from the page.
 */
@Composable
fun Modifier.cosmoTile(shape: Shape): Modifier {
    val palette = LocalCosmoColors.current.palette
    return clip(shape).background(Color(palette.surface))
}
