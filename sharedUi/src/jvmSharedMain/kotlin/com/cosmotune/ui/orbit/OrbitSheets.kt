package com.cosmotune.ui.orbit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The Orbit pieces of what floats over a page (board batch-a, section 4): the ground of a sheet, a menu and a dialog, the pill button
 * a dialog's actions are (one peach, the rest quiet), the chips a song's sheet leads with, and the toast (an ink pill with one action,
 * or the dark card of a problem with a peach one). The `ui/sheets` pieces every screen calls are drawn from these.
 */

/** A menu's, a dialog's and a problem toast's ground: a deep plum of its own in dark, a warm paper in light. */
internal fun orbitFloatGround(look: OrbitLook): Color = if (look.dark) Color(0xFF1C1418) else Color(0xFFFBF6F2)

/** A sheet's ground, a shade under the menu's so a menu opened from a sheet stands off it. */
internal fun orbitSheetGround(look: OrbitLook): Color = if (look.dark) Color(0xFF1A1215) else Color(0xFFF7EFE9)

/** The edge round a floating card: the ink at 8 %. */
internal fun orbitFloatEdge(look: OrbitLook): Color = look.ink.copy(alpha = 0.08f)

/** The one action of a dialog or a toast: peach with a dark word in dark, the deep peach with white in light. */
internal fun orbitActionFill(look: OrbitLook): Color = if (look.dark) PEACH else PEACH_INK
internal fun orbitActionInk(look: OrbitLook): Color = if (look.dark) Color(0xFF2A100A) else Color.White

/** The grabber at the top of a sheet: 44 by 5 dp, the ink at 25 %. */
internal val SHEET_GRAB_WIDTH = 44.dp
internal val SHEET_GRAB_HEIGHT = 5.dp

/**
 * A dialog's or a toast's button: a 44 dp pill ([height]); [primary] is the one peach action, the others the quiet chip. Disabled at
 * 40 %. [icon] goes before the word.
 */
@Composable
internal fun OrbitPillButton(
    label: String,
    onClick: () -> Unit,
    fonts: OrbitFonts,
    look: OrbitLook,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    height: Dp = 44.dp,
) {
    val fill = if (primary) orbitActionFill(look) else look.chip
    val ink = (if (primary) orbitActionInk(look) else look.ink).let { if (enabled) it else it.copy(alpha = 0.4f) }
    Row(
        modifier.heightIn(min = height).clip(CircleShape).background(if (enabled) fill else fill.copy(alpha = fill.alpha * 0.5f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (icon != null) 16.dp else 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, style = fonts.chip.copy(fontWeight = FontWeight(800)), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** One of the chips a song's sheet leads with: Play next (the [primary] one, solid), Queue, the heart. A chip with no [label] is round. */
class OrbitSheetChip(
    val label: String?,
    val icon: ImageVector,
    val description: String,
    val primary: Boolean = false,
    /** A state the chip carries (the heart when the song is liked): drawn in the accent. */
    val on: Boolean = false,
    val onClick: () -> Unit,
)

/** The chips in a row, 8 dp apart; a round chip is 40 dp across. */
@Composable
internal fun OrbitSheetChipRow(chips: List<OrbitSheetChip>, fonts: OrbitFonts, look: OrbitLook, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        for (chip in chips) {
            if (chip.label == null) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(look.chip)
                        .clickable(role = Role.Button, onClick = chip.onClick)
                        .semantics { contentDescription = chip.description },
                    contentAlignment = Alignment.Center,
                ) { Icon(chip.icon, contentDescription = null, tint = if (chip.on) look.accent else look.ink, modifier = Modifier.size(20.dp)) }
            } else {
                OrbitChip(
                    chip.label, fonts, look, chip.onClick, selected = chip.primary,
                    leading = { Icon(chip.icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
        }
    }
}

/**
 * The toast: an ink pill with ground-coloured words and, when there is one, a round button of its [actionIcon] (Undo) at its end;
 * or, for a problem ([alert]), the dark floating card with a peach button of [actionLabel] (Retry). [height] 52 dp at least.
 */
@Composable
internal fun OrbitToastPill(
    message: String,
    fonts: OrbitFonts,
    look: OrbitLook,
    modifier: Modifier = Modifier,
    alert: Boolean = false,
    actionLabel: String? = null,
    actionIcon: ImageVector? = null,
    onAction: (() -> Unit)? = null,
) {
    val ground = if (alert) orbitFloatGround(look) else look.ink
    val ink = if (alert) look.ink else look.ground
    Row(
        modifier.heightIn(min = 52.dp).shadow(18.dp, CircleShape, clip = false).clip(CircleShape).background(ground)
            .then(if (alert) Modifier.border(1.dp, orbitFloatEdge(look), CircleShape) else Modifier)
            .padding(start = 18.dp, end = if (onAction != null) 8.dp else 18.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, style = fonts.chip.copy(fontSize = 14.sp, lineHeight = 18.sp), color = ink, modifier = Modifier.weight(1f, fill = false))
        if (onAction != null) {
            Spacer(Modifier.width(12.dp))
            when {
                actionIcon != null -> Box(
                    Modifier.size(36.dp).clip(CircleShape).background(look.ground).clickable(role = Role.Button, onClick = onAction)
                        .semantics { contentDescription = actionLabel ?: "" },
                    contentAlignment = Alignment.Center,
                ) { Icon(actionIcon, contentDescription = null, tint = look.ink, modifier = Modifier.size(18.dp)) }
                actionLabel != null -> OrbitPillButton(actionLabel, onAction, fonts, look, primary = true, height = 36.dp)
            }
        }
    }
}

/** The mono overline over a dialog's question ("DELETE DOWNLOAD"). */
@Composable
internal fun OrbitOverline(text: String, fonts: OrbitFonts, look: OrbitLook, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = fonts.label, color = look.dim, modifier = modifier, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** The grabber of a sheet. */
@Composable
internal fun OrbitSheetGrab(look: OrbitLook, modifier: Modifier = Modifier) {
    Box(modifier.padding(top = 12.dp, bottom = 14.dp).size(SHEET_GRAB_WIDTH, SHEET_GRAB_HEIGHT).clip(RoundedCornerShape(3.dp)).background(look.ink.copy(alpha = 0.25f)))
}
