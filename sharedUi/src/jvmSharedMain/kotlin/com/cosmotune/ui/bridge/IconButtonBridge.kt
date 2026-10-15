package com.cosmotune.ui.bridge

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.cosmotune.ui.chrome.CosmoActionStyle
import com.cosmotune.ui.chrome.LocalCosmoActionStyle
import com.cosmotune.ui.components.CosmoGlassActionButton
import com.cosmotune.ui.theme.LocalCosmoShell

/**
 * Material's `IconButton` with the same parameters, names, order and defaults (material3 1.3.x), so an upstream file takes it by
 * changing one import line. It is Material's own button everywhere except in the actions slot of the new top bar, where the look
 * is on and the slot's style is [CosmoActionStyle.GLASS]: there it is a glass circle ([CosmoGlassActionButton]) and [colors] and
 * [interactionSource] are ignored, the palette decides the colours.
 */
@Composable
fun IconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    if (LocalCosmoShell.current == null || LocalCosmoActionStyle.current != CosmoActionStyle.GLASS) {
        androidx.compose.material3.IconButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            colors = colors,
            interactionSource = interactionSource,
            content = content,
        )
        return
    }
    CosmoGlassActionButton(onClick = onClick, modifier = modifier, enabled = enabled, content = content)
}
