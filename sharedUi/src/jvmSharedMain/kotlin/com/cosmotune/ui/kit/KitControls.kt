package com.cosmotune.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cosmotune.music.ui.haptics.Haptic
import com.cosmotune.music.ui.haptics.rememberHaptics
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.cosmoFocusRing
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import androidx.compose.foundation.ScrollState

/**
 * A thin filter chip (Prism's library tabs, YTXTube's All / Today / Videos): 32 dp, a low-contrast `surface2` fill at rest, the
 * inverse neutral (primary ink as the fill, the page's colour as the text) when [selected]. No outline. A toggle for screen readers, with a 48 dp touch target.
 */
@Composable
fun KitChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val haptics = rememberHaptics()
    // DESIGN_SYSTEM.md 4.4: the chosen chip is the inverse neutral (never the accent); the others a quiet surface2 fill.
    val fill = Color(if (selected) palette.textPrimary else palette.surfaceHigh)
    val ink = Color(if (selected) palette.backgroundBottom else palette.textPrimary)
    val interaction = remember { MutableInteractionSource() }
    // The whole 48 dp is the target; the pill and its ripple are the 32 dp inside it.
    Box(
        modifier
            .heightIn(min = 48.dp)
            .selectable(selected = selected, interactionSource = interaction, indication = null, role = Role.Tab, onClick = { if (!selected) haptics.play(Haptic.Select); onClick() }),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .heightIn(min = CHIP_HEIGHT)
                .clip(CHIP_SHAPE)
                .background(fill)
                .indication(interaction, ripple())
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (icon != null) Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(16.dp))
            Text(label, style = type.control, color = ink, maxLines = 1)
        }
    }
}

/** A sideways-scrolling row of [KitChip]s, one chosen ([selectedIndex]); the page's margin at both ends. */
@Composable
fun KitChipRow(labels: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues(horizontal = CosmoSpacing.margin)) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { index, label -> KitChip(label, index == selectedIndex, onClick = { onSelect(index) }) }
    }
}

/**
 * A small pill of text ("Continue Blinding Lights ›", a count, a status): a low-contrast fill, the theme's ink, optional [icon] and
 * trailing chevron. With [onClick] it is a button.
 */
@Composable
fun KitPill(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, trailing: ImageVector? = null, onClick: (() -> Unit)? = null) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val ink = Color(palette.textPrimary)
    Row(
        modifier
            .heightIn(min = 36.dp)
            .clip(CHIP_SHAPE)
            .background(Color(palette.surface))
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color(palette.accent), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = type.bodyText, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (trailing != null) {
            Spacer(Modifier.width(6.dp))
            Icon(trailing, contentDescription = null, tint = Color(palette.textSecondary), modifier = Modifier.size(16.dp))
        }
    }
}

/** A text-only button ("See all", "Retry"): the secondary ink, a 48 dp target; disabled at 38 %. */
@Composable
fun KitTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    Row(
        modifier
            .minimumInteractiveComponentSize()
            .heightIn(min = 40.dp)
            .alpha(if (enabled) 1f else 0.38f)
            .clip(CHIP_SHAPE)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = type.control, color = Color(palette.textSecondary), maxLines = 1)
    }
}

/** The kinds of [KitButton] (DESIGN_SYSTEM.md 4.12): the accent-filled main action (one per screen), the tonal second one, the quiet (outlined) third. */
enum class KitButtonKind { PRIMARY, TONAL, QUIET }

/**
 * A button of the kit (DESIGN_SYSTEM.md 4.12): 48 dp, or 40 dp [dense] for an action row; PRIMARY is the accent with its ink, TONAL
 * `surface2` with the primary ink (never a tint of the accent), QUIET a 1 dp outline. A flat pill: no morph, no gradient. Disabled at
 * 38 %.
 */
@Composable
fun KitButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, kind: KitButtonKind = KitButtonKind.PRIMARY, icon: ImageVector? = null, enabled: Boolean = true, dense: Boolean = false) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val fill = when (kind) {
        KitButtonKind.PRIMARY -> Color(palette.accent)
        KitButtonKind.TONAL -> Color(palette.surfaceHigh)
        KitButtonKind.QUIET -> Color.Transparent
    }
    val ink = if (kind == KitButtonKind.PRIMARY) Color(palette.onAccent) else Color(palette.textPrimary)
    Row(
        modifier
            .minimumInteractiveComponentSize()
            .cosmoFocusRing(interaction, 24.dp)
            .heightIn(min = if (dense) 40.dp else 48.dp)
            .alpha(if (enabled) 1f else 0.38f)
            .clip(CHIP_SHAPE)
            .background(fill)
            .then(if (kind == KitButtonKind.QUIET) Modifier.border(1.dp, Color(palette.textPrimary).copy(alpha = 0.16f), CHIP_SHAPE) else Modifier)
            .clickable(interactionSource = interaction, indication = ripple(), enabled = enabled, role = Role.Button) { haptics.play(Haptic.Select); onClick() }
            .padding(horizontal = if (dense) 16.dp else 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = type.control, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The action row of a watch or a detail page (DESIGN_SYSTEM.md 4.12): one sideways-scrolling row of dense tonal pills, 8 dp apart,
 * the page's margin at both ends. It never wraps.
 */
@Composable
fun KitActionRow(modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues(horizontal = CosmoSpacing.margin), scrollState: ScrollState = rememberScrollState(), content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(scrollState).padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** The detail header's play button (DESIGN_SYSTEM.md 4.12): a 56 dp accent circle with its glyph; the one primary action of the page. */
@Composable
fun KitPlayCircle(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector = CosmoIcons.Play, enabled: Boolean = true) {
    val palette = LocalCosmoColors.current.palette
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(56.dp)
            .cosmoFocusRing(interaction, 28.dp)
            .alpha(if (enabled) 1f else 0.38f)
            .clip(CircleShape)
            .background(Color(palette.accent))
            .clickable(interactionSource = interaction, indication = ripple(), enabled = enabled, role = Role.Button, onClickLabel = label) { haptics.play(Haptic.Select); onClick() }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color(palette.onAccent), modifier = Modifier.size(28.dp))
    }
}

private val CHIP_HEIGHT = 32.dp
private val CHIP_SHAPE = RoundedCornerShape(50)
