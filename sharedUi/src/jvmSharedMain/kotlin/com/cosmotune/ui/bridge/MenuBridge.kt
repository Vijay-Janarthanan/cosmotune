package com.cosmotune.ui.bridge

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.MenuItemColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.cosmotune.design.GlassPolicy
import com.cosmotune.ui.components.SuperellipseShape
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoShell

/** `.menu3 { border-radius: 22px }`. */
private val MENU_SHAPE = SuperellipseShape(CosmoRadii.lg)

/** `.menu3 button { border-radius: 14px }`, and `.menu3 { padding: 6px }` as the side inset of an item. */
private val ITEM_SHAPE = RoundedCornerShape(14.dp)
private val ITEM_INSET = 6.dp

/**
 * Material's `DropdownMenu` (the overload with `shape`, `containerColor`, `tonalElevation`, `shadowElevation` and `border`,
 * material3 1.3.x) with the same names, order and defaults. With the new look off it is Material's own. With it on the menu is
 * a 22 dp superellipse of solid tinted glass (`surfaceHigh` at [GlassPolicy.SOLID_ALPHA]) lifted by
 * [GlassPolicy.SHADOW_ELEVATION_DP], with no tonal elevation and no border; those five parameters are the look's then.
 */
@Composable
fun DropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    scrollState: ScrollState = rememberScrollState(),
    properties: PopupProperties = PopupProperties(focusable = true),
    shape: Shape = MenuDefaults.shape,
    containerColor: Color = MenuDefaults.containerColor,
    tonalElevation: Dp = MenuDefaults.TonalElevation,
    shadowElevation: Dp = MenuDefaults.ShadowElevation,
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (LocalCosmoShell.current == null) {
        androidx.compose.material3.DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            offset = offset,
            scrollState = scrollState,
            properties = properties,
            shape = shape,
            containerColor = containerColor,
            tonalElevation = tonalElevation,
            shadowElevation = shadowElevation,
            border = border,
            content = content,
        )
        return
    }
    androidx.compose.material3.DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        offset = offset,
        scrollState = scrollState,
        properties = properties,
        shape = MENU_SHAPE,
        containerColor = Color(LocalCosmoColors.current.palette.surfaceHigh).copy(alpha = GlassPolicy.SOLID_ALPHA),
        tonalElevation = 0.dp,
        shadowElevation = GlassPolicy.SHADOW_ELEVATION_DP.dp,
        border = null,
        content = content,
    )
}

/**
 * Material's `DropdownMenuItem` with the same names, order and defaults. With the new look on the item is inset 6 dp from the
 * menu's sides and rounded by 14 dp, so its highlight is a rounded bar inside the menu; it keeps Material's 48 dp minimum
 * height, which is the touch rule.
 */
@Composable
fun DropdownMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    colors: MenuItemColors = MenuDefaults.itemColors(),
    contentPadding: PaddingValues = MenuDefaults.DropdownMenuItemContentPadding,
    interactionSource: MutableInteractionSource? = null,
) {
    if (LocalCosmoShell.current == null) {
        androidx.compose.material3.DropdownMenuItem(
            text = text,
            onClick = onClick,
            modifier = modifier,
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            enabled = enabled,
            colors = colors,
            contentPadding = contentPadding,
            interactionSource = interactionSource,
        )
        return
    }
    androidx.compose.material3.DropdownMenuItem(
        text = text,
        onClick = onClick,
        modifier = modifier.padding(horizontal = ITEM_INSET).clip(ITEM_SHAPE),
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        enabled = enabled,
        colors = colors,
        contentPadding = contentPadding,
        interactionSource = interactionSource,
    )
}
