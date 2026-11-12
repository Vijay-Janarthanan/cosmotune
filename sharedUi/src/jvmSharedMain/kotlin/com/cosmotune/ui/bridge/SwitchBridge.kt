package com.cosmotune.ui.bridge

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.cosmotune.ui.components.CosmoSwitch

/**
 * Material's `Switch` with the same parameters, names, order and defaults (material3 1.3.x), so an upstream file takes it by
 * changing one import line. It is the B switch ([CosmoSwitch]:
 * gradient track, a thumb with a check): the palette decides the colours, so [thumbContent], [colors] and [interactionSource]
 * are ignored.
 *
 * A null [onCheckedChange] makes Material's switch display-only. Here it makes the switch disabled and removes it from
 * the accessibility tree, so the row that holds it owns the click and the label.
 */
@Composable
fun Switch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    thumbContent: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    colors: SwitchColors = SwitchDefaults.colors(),
    interactionSource: MutableInteractionSource? = null,
) {
    CosmoSwitch(
        checked = checked,
        onCheckedChange = { onCheckedChange?.invoke(it) },
        modifier = if (onCheckedChange == null) modifier.clearAndSetSemantics {} else modifier,
        enabled = enabled && onCheckedChange != null,
    )
}
