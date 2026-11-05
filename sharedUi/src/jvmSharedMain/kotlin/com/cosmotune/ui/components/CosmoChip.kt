package com.cosmotune.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.design.ControlMetrics
import com.cosmotune.design.ControlMorph
import com.cosmotune.ui.theme.CosmoColors
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.music.ui.haptics.Haptic
import com.cosmotune.music.ui.haptics.rememberHaptics

/** The weight of a chip label, `.chip2` in `components-v3bc.css`. */
private const val CHIP_WEIGHT = 620

/**
 * A filter chip of the B+C system, 36 dp tall (44 with [big]). In the page it is a rounded square (0.22 of its height) with a
 * faint ink wash and a 1 dp outline that turns round (0.5) and takes the tonal cover fill, a check and `onAccentContainer`
 * ink when [selected]; pressed it squares up to 0.14. On a floating layer ([floating], over video or in a sheet) it is a glass
 * pill that becomes solid ink with a `backgroundBottom` label when selected.
 *
 * It is a toggle for screen readers (`Role.Checkbox`) and plays the select haptic, unless [haptic] is false (a filter row passes
 * `haptic = !selected`, so a tap on the chip that is already chosen changes nothing and buzzes for nothing). It reserves a 48 dp
 * touch target.
 */
@Composable
fun CosmoChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    big: Boolean = false,
    floating: Boolean = false,
    haptic: Boolean = true,
) {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val haptics = rememberHaptics()
    val height = ControlMetrics.chipHeightDp(big)
    val ink = Color(
        when {
            floating && selected -> palette.backgroundBottom   // the label of a solid ink chip is the ground's colour
            floating -> palette.textPrimary
            selected -> palette.onAccentContainer
            else -> palette.textPrimary
        },
    )
    MorphBox(
        targetFor = { pressed -> ControlMorph.chip(height, selected, pressed, floating) },
        onClick = { if (haptic) haptics.play(Haptic.Select); onClick() },
        modifier = modifier.minimumInteractiveComponentSize().height(height.dp),
        toggled = selected,
        role = Role.Checkbox,
        floating = floating,
        stateLayer = ink,
        edge = { outline -> chipEdge(outline, selected, floating, colors) },
        fill = { chipFill(selected, floating, colors) },
    ) {
        Row(
            Modifier.padding(horizontal = ControlMetrics.CHIP_PADDING_DP.dp),
            horizontalArrangement = Arrangement.spacedBy(ControlMetrics.CHIP_GAP_DP.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected) Icon(CosmoIcons.Check, contentDescription = null, tint = ink, modifier = Modifier.size(ControlMetrics.CHIP_ICON_DP.dp))
            val type = LocalCosmoType.current
            Text(
                text = label,
                color = ink,
                style = type.forText(type.label.copy(fontSize = ControlMetrics.chipFontSp(big).sp, fontWeight = FontWeight(CHIP_WEIGHT)), label),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun DrawScope.chipFill(selected: Boolean, floating: Boolean, c: CosmoColors): Brush = when {
    floating -> if (selected) SolidColor(c.ink(1f)) else c.glassFill()
    selected -> tonalFill(c)
    else -> SolidColor(c.ink(ControlMetrics.CHIP_REST_FILL))
}

private fun DrawScope.chipEdge(outline: Path, selected: Boolean, floating: Boolean, c: CosmoColors) {
    if (selected) return
    if (floating) innerRing(outline, (if (c.dark) Color.White else Color.Black).copy(alpha = ControlMetrics.glassRing(c.dark)), 1f)
    else innerRing(outline, c.ink(ControlMetrics.OUTLINE), 1f)
}
