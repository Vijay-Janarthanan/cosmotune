package com.cosmotune.ui.blocks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.design.BlockMetrics
import com.cosmotune.design.ControlMetrics
import com.cosmotune.ui.components.CoverImage
import com.cosmotune.ui.components.MorphBox
import com.cosmotune.ui.components.SuperellipseShape
import com.cosmotune.ui.components.ink
import com.cosmotune.ui.components.primaryFill
import com.cosmotune.ui.components.tileEdge
import com.cosmotune.ui.components.tileLiftOf
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics

/*
 * Moods (`components-v3bc.css` `.mood2`, `screens-v2.css` `.mtile`): the chips of the Home page and the tiles of the Search page.
 */

/**
 * A mood chip (44 dp, the label in Archivo 18 sp, weight 700): a faint wash of ink at rest, in a squircle (radius 0.30 of its
 * height); selected it is a pill in the primary fill with an `onAccent` label (0.5); pressed it squares up (0.20) and squishes to
 * 96.5 % of its width. A toggle for screen readers, with the select haptic; it reserves a 48 dp touch target.
 */
@Composable
fun CosmoMoodChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val type = LocalCosmoType.current
    val haptics = rememberHaptics()
    val ink = Color(if (selected) palette.onAccent else palette.textSecondary)
    MorphBox(
        targetFor = { pressed -> BlockMetrics.moodChipTarget(selected, pressed) },
        onClick = { haptics.play(Haptic.Select); onClick() },
        modifier = modifier.minimumInteractiveComponentSize().height(BlockMetrics.MOOD_CHIP_HEIGHT_DP.dp),
        toggled = selected,
        role = Role.Checkbox,
        stateLayer = ink,
        fill = { if (selected) primaryFill(colors) else SolidColor(colors.ink(ControlMetrics.CHIP_REST_FILL)) },
    ) {
        Text(
            text = label,
            style = type.forText(type.mood, label),
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 18.dp),
        )
    }
}

/**
 * A mood tile (`.mtile`): radius 20, 92 dp tall (116 with [big]), the cover's ground with a wash of `ambient` from the top left,
 * the label in Archivo (20 sp, 24 big) at the top left, and a cover of the mood turned 10 degrees and poking out of the bottom right
 * corner, with its shadow. It is lifted and edged like every tile (`.mtile`: `box-shadow: var(--hl), var(--e1)`). It fills the width
 * it is given; the page lays the tiles out ([BlockMetrics.moodRows]). It presses like a mood chip.
 */
@Composable
fun CosmoMoodTile(
    label: String,
    coverUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    big: Boolean = false,
) {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val type = LocalCosmoType.current
    val haptics = rememberHaptics()
    val labelSize = if (big) 24.sp else 20.sp
    val lift = remember(colors) { tileLiftOf(SuperellipseShape(BlockMetrics.MOOD_TILE_RADIUS_DP.dp), colors) }
    MorphBox(
        targetFor = { pressed -> BlockMetrics.moodTileTarget(pressed) },
        onClick = { haptics.play(Haptic.Select); onClick() },
        modifier = modifier.fillMaxWidth().height(BlockMetrics.moodTileHeightDp(big).dp),
        stateLayer = Color(palette.textPrimary),
        lift = lift,
        edge = { outline ->
            clipPath(outline) { ambientWash(colors, cx = 0f, cy = 0f, rx = 1.2f, ry = 1.2f) }
            tileEdge(outline, colors)
        },
        fill = { groundFill(colors) },
    ) {
        // The cover pokes out past the tile's corner: the tile clips it.
        Box(Modifier.fillMaxSize().clip(SuperellipseShape(BlockMetrics.MOOD_TILE_RADIUS_DP.dp))) {
            CoverImage(
                url = coverUrl,
                size = if (big) 84.dp else 64.dp,
                radius = 12.dp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = if (big) 8.dp else 10.dp, y = if (big) 14.dp else 12.dp)
                    .graphicsLayer { rotationZ = -10f },
                shadow = true,
            )
            Text(
                text = label,
                style = type.forText(
                    TextStyle(fontFamily = type.displayFamily, fontWeight = FontWeight(760), fontSize = labelSize, lineHeight = labelSize * 1.1f),
                    label,
                ),
                color = Color(palette.textPrimary),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.TopStart).padding(horizontal = 14.dp, vertical = 12.dp),
            )
        }
    }
}
