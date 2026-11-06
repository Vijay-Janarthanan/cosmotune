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
import com.cosmotune.ui.orbit.OrbitPillButton
import com.cosmotune.ui.orbit.OrbitSelectChip
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import androidx.compose.foundation.ScrollState

/**
 * A filter chip in Orbit (the library's tabs, Video's All / Today / Videos): [OrbitSelectChip], 40 dp, the ink at 8 % at rest and
 * solid ink with the ground's colour for its words when [selected]; [icon] before the words. A tab for screen readers, with a 48 dp
 * touch target; a tap on the chip already chosen plays no haptic.
 */
@Composable
fun KitChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    // The whole 48 dp is the chip's place (and its test tag's); the pill is the 40 dp inside it.
    Box(modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
        OrbitSelectChip(
            title = label,
            selected = selected,
            fonts = orbitFonts(),
            look = orbitLook(),
            onClick = onClick,
            modifier = Modifier.semantics { role = Role.Tab; this.selected = selected },
            icon = icon,
            haptic = !selected,
        )
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

/**
 * A text-only button ("See all", "Retry") in Orbit: the words in the chip face and the accent, as a page section's "Show all"
 * ([com.cosmotune.ui.orbit.OrbitPageSection]); a 48 dp target; disabled at 40 %.
 */
@Composable
fun KitTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val look = orbitLook()
    Row(
        modifier
            .minimumInteractiveComponentSize()
            .heightIn(min = 40.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = orbitFonts().chip, color = look.accent, maxLines = 1)
    }
}

/** The kinds of [KitButton]: the one peach action of a screen, the quiet chip, and the quiet chip again (Orbit draws no outlines). */
enum class KitButtonKind { PRIMARY, TONAL, QUIET }

/**
 * A button of the kit in Orbit: the pill a dialog's actions are ([OrbitPillButton]), 48 dp, or 40 dp [dense] for an action row.
 * PRIMARY is the peach action, TONAL and QUIET the quiet chip (the ink at 8 %). Disabled at 40 %. The select haptic on a tap.
 */
@Composable
fun KitButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, kind: KitButtonKind = KitButtonKind.PRIMARY, icon: ImageVector? = null, enabled: Boolean = true, dense: Boolean = false) {
    val haptics = rememberHaptics()
    OrbitPillButton(
        label = text,
        onClick = { haptics.play(Haptic.Select); onClick() },
        fonts = orbitFonts(),
        look = orbitLook(),
        modifier = modifier.minimumInteractiveComponentSize(),
        primary = kind == KitButtonKind.PRIMARY,
        enabled = enabled,
        icon = icon,
        height = if (dense) 40.dp else 48.dp,
    )
}

/**
 * The action row of a watch or a detail page, as Orbit's chip row ([com.cosmotune.ui.orbit.OrbitChipRow]): one sideways-scrolling
 * row of chips and pills, 8 dp apart, the page's margin at both ends. It never wraps.
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
