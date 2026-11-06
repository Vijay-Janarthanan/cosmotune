package com.cosmotune.ui.sheets

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.blocks.CosmoListRow
import com.cosmotune.ui.blocks.CosmoRowLeading
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.cosmoFocusRing
import com.cosmotune.ui.orbit.OrbitSheetChip
import com.cosmotune.ui.orbit.OrbitSheetChipRow
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.theme.CosmoMotion
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.LocalReducedMotion
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.selected as selectedString
import org.jetbrains.compose.resources.stringResource

/*
 * The rows of a sheet and of a menu in Orbit (board batch-a, section 4, `.mi`): radius 16, 12 dp inside, the icon 20 dp in the ink
 * and 12 dp before the label, the label 14 sp weight 700 in the Orbit text face, the value at the end in 12 sp of the dim ink. A
 * sheet row is 46 dp high inside 4 dp of side padding; a menu row is 42 dp, flush. Both: disabled at 38 %, a layer of the ink at 8 %
 * while pressed or hovered.
 */

private val SHEET_ROW_HEIGHT = 46.dp
private val MENU_ROW_HEIGHT = 42.dp
private val ROW_RADIUS = 16.dp
private val SHEET_ROW_INSET = 4.dp
private val ROW_PADDING = 12.dp
private val ROW_GAP = 12.dp
private val ROW_ICON = 20.dp
private const val LABEL_SP = 14f
private const val VALUE_SP = 12f
private val VALUE_GAP = 12.dp
private const val DISABLED_ALPHA = 0.38f
private const val STATE_LAYER_ALPHA = 0.08f

/** The gap in a sheet between two groups of rows: 6 dp. */
private val SHEET_GAP = 6.dp

/** The rule between two groups of a menu: 1 dp of the ink at 10 %, 4 dp above and below and 12 dp in from the sides. */
private val MENU_RULE_HEIGHT = 1.dp
private val MENU_RULE_MARGIN_V = 4.dp
private val MENU_RULE_MARGIN_H = 12.dp

/**
 * One row of a song or album sheet ([menu] false) or of a menu ([menu] true): the [icon] (in the ink, or [tint] when the icon carries
 * a state such as a filled heart), the [label], and [value] at the end. [enabled] false dims the row to 38 % and stops the tap. The
 * row is one button for assistive technology; the icon is decoration.
 */
@Composable
fun CosmoActionRow(
    icon: ImageVector,
    label: String,
    value: String? = null,
    tint: Color? = null,
    enabled: Boolean = true,
    menu: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val type = LocalCosmoType.current
    val fonts = orbitFonts()
    val interaction = remember { MutableInteractionSource() }
    // Read while drawing, so a press repaints the row and recomposes nothing.
    val pressed = interaction.collectIsPressedAsState()
    val hovered = interaction.collectIsHoveredAsState()
    val shape = remember { RoundedCornerShape(ROW_RADIUS) }
    val fade = if (enabled) 1f else DISABLED_ALPHA
    val ink = Color(palette.textPrimary)
    val labelColor = ink.copy(alpha = ink.alpha * fade)
    val iconColor = (tint ?: ink).let { it.copy(alpha = it.alpha * fade) }
    val valueColor = Color(palette.textSecondary).let { it.copy(alpha = it.alpha * fade) }
    val labelStyle = remember(type, fonts, label) {
        type.forText(fonts.title.copy(fontSize = LABEL_SP.sp, lineHeight = (LABEL_SP * 1.3f).sp), label)
    }
    val valueStyle = remember(fonts) { fonts.body.copy(fontSize = VALUE_SP.sp, lineHeight = (VALUE_SP * 1.3f).sp) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = if (menu) 0.dp else SHEET_ROW_INSET)
            // The focus ring is drawn outside the row, so it comes before the clip.
            .cosmoFocusRing(interaction, ROW_RADIUS, floating = true)
            .clip(shape)
            .drawBehind {
                if (enabled && (pressed.value || hovered.value)) {
                    drawRect(colors.live(CoverField.TEXT_PRIMARY).let { it.copy(alpha = it.alpha * STATE_LAYER_ALPHA) })
                }
            }
            .hoverable(interaction, enabled)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = if (menu) MENU_ROW_HEIGHT else SHEET_ROW_HEIGHT)
            .padding(horizontal = ROW_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(ROW_ICON))
        Spacer(Modifier.width(ROW_GAP))
        Text(
            text = label,
            style = labelStyle,
            color = labelColor,
            maxLines = if (menu) 2 else Int.MAX_VALUE,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(vertical = 10.dp),
        )
        if (value != null) {
            Spacer(Modifier.width(VALUE_GAP))
            Text(text = value, style = valueStyle, color = valueColor, maxLines = 1)
        }
    }
}

/**
 * The break between two groups of rows. In a sheet it is 6 dp of air; in a menu ([menu] true) a 1 dp rule in `textPrimary` at 10 %,
 * 4 dp above and below and 12 dp in from the sides.
 */
@Composable
fun CosmoSheetGap(menu: Boolean) {
    if (!menu) {
        Spacer(Modifier.height(SHEET_GAP))
        return
    }
    val rule = Color(LocalCosmoColors.current.palette.textPrimary).let { it.copy(alpha = it.alpha * SHEET_DIVIDER_ALPHA) }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = MENU_RULE_MARGIN_H, vertical = MENU_RULE_MARGIN_V)
            .height(MENU_RULE_HEIGHT)
            .background(rule),
    )
}

/**
 * The chips a song's sheet leads with (board batch-a, section 4): its main actions, Play next solid and the others quiet, the heart a
 * round chip, in a row under the sheet's head, 16 dp in from the sides. The rows follow for the rest.
 */
@Composable
fun CosmoSheetChips(chips: List<OrbitSheetChip>, modifier: Modifier = Modifier) {
    if (chips.isEmpty()) return
    OrbitSheetChipRow(chips, orbitFonts(), orbitLook(), modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp))
}

/**
 * A row of the playlist picker: a [CosmoListRow] with the playlist's cover in front and, at the end, a 24 dp disc that is the
 * accent with a check when the playlist is ticked ([selected]) and an empty ring when it is not. One button for assistive
 * technology; a ticked row reads "Selected".
 */
@Composable
fun CosmoPickRow(
    title: String,
    subtitle: String?,
    imageUrl: String?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CosmoListRow(
        title = title,
        onClick = onClick,
        modifier = modifier,
        subtitle = subtitle?.takeIf { it.isNotBlank() },
        leading = CosmoRowLeading.Cover(imageUrl),
        trailing = { CheckDisc(selected) },
    )
}

private val DISC = 24.dp
private val DISC_RING = 1.5.dp
private val DISC_CHECK = 16.dp

/** The tick of [CosmoPickRow]: fills with the accent and shows the check when [selected]; the fade is read while drawing. */
@Composable
private fun CheckDisc(selected: Boolean) {
    val colors = LocalCosmoColors.current
    val reduced = LocalReducedMotion.current
    val fill = animateFloatAsState(if (selected) 1f else 0f, CosmoMotion.effects(reduced), label = "pick disc")
    val label = if (selected) stringResource(Res.string.selectedString) else null
    Box(
        Modifier
            .size(DISC)
            .drawBehind {
                val amount = fill.value.coerceIn(0f, 1f)
                val ring = DISC_RING.toPx()
                if (amount < 1f) {
                    val line = colors.live(CoverField.TEXT_TERTIARY)
                    drawCircle(line.copy(alpha = line.alpha * (1f - amount)), radius = (size.minDimension - ring) / 2f, center = Offset(size.width / 2f, size.height / 2f), style = Stroke(ring))
                }
                if (amount > 0f) {
                    val accent = colors.live(CoverField.ACCENT)
                    drawCircle(accent.copy(alpha = accent.alpha * amount), radius = size.minDimension / 2f)
                }
            }
            .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = CosmoIcons.Check,
            contentDescription = null,
            tint = Color(colors.palette.onAccent),
            modifier = Modifier.size(DISC_CHECK).graphicsLayer { alpha = fill.value.coerceIn(0f, 1f) },
        )
    }
}
