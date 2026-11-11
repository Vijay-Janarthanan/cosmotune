package com.cosmotune.ui.orbit

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.cosmo_theme
import com.cosmotune.music.sharedui.resources.cosmo_theme_dark
import com.cosmotune.music.sharedui.resources.cosmo_theme_light
import com.cosmotune.music.sharedui.resources.cosmo_theme_system
import com.cosmotune.music.ui.haptics.Haptic
import com.cosmotune.music.ui.haptics.rememberHaptics
import com.cosmotune.ui.components.IconBuilder
import com.cosmotune.ui.components.cosmoFocusRing
import com.cosmotune.ui.theme.CosmoMotion
import com.cosmotune.ui.theme.LocalReducedMotion
import com.cosmotune.ui.theme.LocalThemeChoice
import com.cosmotune.ui.theme.ThemeChoice
import org.jetbrains.compose.resources.stringResource

/** The switch's options in the order they stand: the sun, the moon, then "A" for following the system. */
val ORBIT_THEME_ORDER = listOf(ThemeChoice.LIGHT, ThemeChoice.DARK, ThemeChoice.SYSTEM)

/** The track's height, its inner padding and one segment's width: a compact 40 dp pill of three 44 dp segments. */
private val TRACK_HEIGHT = 40.dp
private val TRACK_PAD = 3.dp
private val SEGMENT_WIDTH = 44.dp
private val GLYPH = 18.dp

/** Between the label and the switch: at least the 8 dp of the profile menus' rhythm. */
private val LABEL_GAP = 8.dp

/**
 * The quick theme switch of the profile menus (the phone's drawer, the desktop's account popover): a compact Orbit pill of three
 * segments, the sun (Light), the moon (Dark) and "A" (follow the system), with the chosen one under a solid ink thumb that carries
 * its glyph in the ground's colour; the others are the dim ink on the chip fill. The thumb slides on the move spring (a short fade
 * under reduced motion). A selectable group, each segment a radio button named after its choice.
 */
@Composable
fun OrbitThemeSwitch(choice: ThemeChoice, onChoose: (ThemeChoice) -> Unit, modifier: Modifier = Modifier) {
    val look = orbitLook()
    val reduced = LocalReducedMotion.current
    val haptics = rememberHaptics()
    val index = ORBIT_THEME_ORDER.indexOf(choice).coerceAtLeast(0)
    val thumb = remember { Animatable(index.toFloat()) }
    LaunchedEffect(index, reduced) { thumb.animateTo(index.toFloat(), CosmoMotion.move(reduced)) }
    val names = ORBIT_THEME_ORDER.map { themeName(it) }
    Row(
        modifier
            .heightIn(min = TRACK_HEIGHT)
            .drawBehind {
                val radius = CornerRadius(size.height / 2f)
                drawRoundRect(look.chip, cornerRadius = radius)
                val pad = TRACK_PAD.toPx()
                val width = SEGMENT_WIDTH.toPx()
                val height = size.height - pad * 2
                drawRoundRect(
                    look.ink,
                    topLeft = Offset(pad + thumb.value * width, pad),
                    size = Size(width, height),
                    cornerRadius = CornerRadius(height / 2f),
                )
            }
            .padding(TRACK_PAD)
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ORBIT_THEME_ORDER.forEachIndexed { at, option ->
            val selected = option == choice
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            Box(
                Modifier
                    .size(SEGMENT_WIDTH, TRACK_HEIGHT - TRACK_PAD * 2)
                    .cosmoFocusRing(interaction, (TRACK_HEIGHT - TRACK_PAD * 2) / 2)
                    .hoverable(interaction)
                    .selectable(selected, interaction, indication = null, role = Role.RadioButton) {
                        if (!selected) { haptics.play(Haptic.Select); onChoose(option) }
                    }
                    .semantics { contentDescription = names[at] },
                contentAlignment = Alignment.Center,
            ) {
                val tint = when {
                    selected -> look.ground
                    hovered -> look.ink
                    else -> look.dim
                }
                Icon(themeGlyph(option), contentDescription = null, tint = tint, modifier = Modifier.size(GLYPH))
            }
        }
    }
}

/**
 * The theme row of a profile menu: "THEME" in Orbit's label face at [labelStart] from the menu's edge and the [OrbitThemeSwitch] at
 * [end], at least 8 dp apart, on one line as tall as the switch (so the menu's own gaps above and below it are the gaps seen). It reads and stores the app's own choice ([LocalThemeChoice]), the one Settings
 * changes, so the two always agree; nothing where the app installed no choice.
 */
@Composable
fun OrbitThemeRow(modifier: Modifier = Modifier, labelStart: Dp = 0.dp, end: Dp = 0.dp) {
    val host = LocalThemeChoice.current ?: return
    val choice = host.collect()
    val fonts = orbitFonts()
    val look = orbitLook()
    Row(
        modifier.fillMaxWidth().heightIn(min = TRACK_HEIGHT).padding(start = labelStart, end = end),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(Res.string.cosmo_theme).uppercase(), style = fonts.label, color = look.dim, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(LABEL_GAP))
        OrbitThemeSwitch(choice, host::choose)
    }
}

@Composable
private fun themeName(choice: ThemeChoice): String = stringResource(
    when (choice) {
        ThemeChoice.SYSTEM -> Res.string.cosmo_theme_system
        ThemeChoice.LIGHT -> Res.string.cosmo_theme_light
        ThemeChoice.DARK -> Res.string.cosmo_theme_dark
    },
)

/** The glyph of [choice]: the sun, the crescent moon, or the "A" of following the system, on the kit's 24 px grid and 2 px stroke. */
fun themeGlyph(choice: ThemeChoice): ImageVector = when (choice) {
    ThemeChoice.LIGHT -> SunGlyph
    ThemeChoice.DARK -> MoonGlyph
    ThemeChoice.SYSTEM -> AutoGlyph
}

private fun glyph(name: String, block: IconBuilder.() -> Unit): Lazy<ImageVector> = lazy {
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).also { IconBuilder(it).block() }.build()
}

private val SunGlyph by glyph("theme-light") {
    circle(12f, 12f, 3.75f)
    listOf("M12 3v1.6", "M12 19.4V21", "M3 12h1.6", "M19.4 12H21", "m5.6 5.6 1.15 1.15", "m17.25 17.25 1.15 1.15", "m5.6 18.4 1.15-1.15", "m17.25 6.75 1.15-1.15")
        .forEach { stroke(it) }
}

private val MoonGlyph by glyph("theme-dark") { stroke("M19 14.6A7.6 7.6 0 0 1 9.4 5a7.6 7.6 0 1 0 9.6 9.6z") }

private val AutoGlyph by glyph("theme-system") { stroke("M6.5 19.5 12 4.5l5.5 15"); stroke("M8.4 14.4h7.2") }
