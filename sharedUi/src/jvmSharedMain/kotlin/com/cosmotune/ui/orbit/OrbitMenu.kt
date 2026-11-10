package com.cosmotune.ui.orbit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/** One line of an [OrbitMenu]: a title, an optional line under it, an optional icon; [selected] is the one in force. */
class OrbitMenuItem(
    val title: String,
    val detail: String? = null,
    val icon: ImageVector? = null,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    /** Keep the menu open after the pick (a toggle whose new state the person wants to see). */
    val keepOpen: Boolean = false,
    /** The line's test tag, for a screen whose tests pick from the menu. */
    val testTag: String? = null,
    val onClick: () -> Unit,
)

/**
 * The small popover list (the video view menu, the "more" menus): [width] wide, radius 24, a deep ground of its own in dark and a
 * pale one in light, with an optional mono [header]. It opens under its anchor (the composable it is placed in), its end edge on
 * the anchor's, or above it when there is no room below. The one in force is solid ink with ground-coloured text. A pick closes
 * it (unless the item says [OrbitMenuItem.keepOpen]); a tap outside or back closes it too.
 */
@Composable
fun OrbitMenu(
    open: Boolean,
    onDismiss: () -> Unit,
    items: List<OrbitMenuItem>,
    fonts: OrbitFonts,
    look: OrbitLook,
    header: String? = null,
    width: Dp = 260.dp,
    filters: (@Composable () -> Unit)? = null,
) {
    if (!open) return
    val ground = orbitFloatGround(look)
    val windowSize = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val margin = with(density) { MENU_MARGIN.roundToPx() }
    val maxHeight = if (windowSize.height > 0) with(density) {
        (windowSize.height.toDp() - MENU_MARGIN * 2).coerceIn(1.dp, 520.dp)
    } else 520.dp
    val menuWidth = if (windowSize.width > 0) with(density) {
        (windowSize.width.toDp() - MENU_MARGIN * 2).coerceIn(1.dp, width)
    } else width
    val position = androidx.compose.runtime.remember(margin) { OrbitMenuPosition(margin) }
    Popup(popupPositionProvider = position, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Column(
            Modifier.width(menuWidth).heightIn(max = maxHeight)
                .shadow(24.dp, RoundedCornerShape(24.dp), clip = false)
                .clip(RoundedCornerShape(24.dp)).background(ground)
                .border(1.dp, look.ink.copy(alpha = 0.08f), RoundedCornerShape(24.dp))
                .verticalScroll(rememberScrollState())
                .padding(8.dp),
        ) {
            if (header != null) {
                Text(header.uppercase(), style = fonts.label, color = look.ink.copy(alpha = 0.7f), modifier = Modifier.padding(start = 12.dp, top = 6.dp, end = 12.dp, bottom = 8.dp))
            }
            filters?.invoke()
            items.forEach { item ->
                val ink = when {
                    item.selected -> ground
                    item.enabled -> look.ink
                    else -> look.ink.copy(alpha = 0.4f)
                }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (item.selected) look.ink else Color.Transparent)
                        .clickable(enabled = item.enabled) { if (!item.keepOpen) onDismiss(); item.onClick() }
                        .then(if (item.testTag != null) Modifier.testTag(item.testTag) else Modifier)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (item.icon != null) {
                        Icon(item.icon, null, tint = ink, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(12.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(item.title, style = fonts.title.copy(fontSize = 14.sp), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (item.detail != null) {
                            Text(item.detail, style = fonts.body.copy(fontSize = 12.sp, lineHeight = 15.sp), color = if (item.selected) ground.copy(alpha = 0.7f) else look.dim.copy(alpha = if (item.enabled) 1f else 0.6f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/** The least room a menu keeps from the window's edges: the page margin, so it never touches a side. */
private val MENU_MARGIN = 16.dp

/**
 * Under the anchor with its end edge on the anchor's end edge; above it when it would run off the bottom; always on screen, and at
 * least [margin] in from the window's sides (a menu anchored near the start of a phone's card stays off the edge).
 */
private class OrbitMenuPosition(private val margin: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val gap = 8
        val maxX = (windowSize.width - popupContentSize.width - margin).coerceAtLeast(0)
        val minX = margin.coerceAtMost(maxX)
        val x = (if (layoutDirection == LayoutDirection.Ltr) anchorBounds.right - popupContentSize.width else anchorBounds.left).coerceIn(minX, maxX)
        val below = anchorBounds.bottom + gap
        val y = if (below + popupContentSize.height <= windowSize.height) below
        else (anchorBounds.top - gap - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}
