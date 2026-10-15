package com.cosmotune.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.cosmotune.design.ControlMetrics
import com.cosmotune.design.ControlMorph
import com.cosmotune.ui.theme.LocalCosmoColors

/**
 * The glass icon button (`CosmoIconButton` with `style = GLASS`, 40 dp) with a slot in place of the icon: a glass circle that
 * scales to 0.94 when pressed, a 48 dp touch target around it, ink for whatever the slot draws without a tint of its own, held to the
 * glass icon button's 20 dp icon. It is for the old screens' own icon buttons (a Material `IconButton { Icon(...) }` whose icon is
 * an arbitrary slot), reached through the bridge's `IconButton`; a screen that has an icon uses `CosmoIconButton`. The slot's own
 * semantics (an icon's content description) become the button's name: a click merges its descendants.
 *
 * It plays no haptic: the old buttons it stands for had none of their own, or played theirs in their own `onClick`.
 */
@Composable
fun CosmoGlassActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    MorphBox(
        targetFor = { pressed -> ControlMorph.iconButton(40f, false, pressed, true) },
        onClick = onClick,
        modifier = modifier
            .minimumInteractiveComponentSize()
            .size(40.dp),
        enabled = enabled,
        role = Role.Button,
        floating = true,
        stateLayer = Color(palette.textPrimary),
        edge = { outline -> glassEdge(outline, colors.dark) },
        fill = { colors.glassFill() },
    ) {
        // The icon is the glass icon button's 20 dp: an `Icon` of the old screen (24 dp unless told otherwise) is held to it by the box.
        CompositionLocalProvider(LocalContentColor provides Color(palette.textPrimary)) {
            Box(Modifier.size(ControlMetrics.iconButtonIconDp(40f).dp), contentAlignment = Alignment.Center) { content() }
        }
    }
}
