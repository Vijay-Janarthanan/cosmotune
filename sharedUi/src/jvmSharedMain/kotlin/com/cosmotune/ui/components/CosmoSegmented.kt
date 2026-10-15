package com.cosmotune.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cosmotune.design.ControlMetrics
import com.cosmotune.design.ControlMorph
import com.cosmotune.ui.theme.CosmoMotion
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.LocalReducedMotion
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import kotlin.math.min

/** The weight of a segment label, `.seg2` in `components-v3bc.css`. */
private const val SEGMENT_WEIGHT = 640

/** Between a segment's check and its label. */
private const val SEGMENT_ICON_GAP_DP = 6

/**
 * A segmented control of the B+C system: a connected group of 40 dp segments 3 dp apart. In the page the outer ends are
 * pills and the corners between segments are 6 dp, which the press spring morphs to 16 dp while pressed; the selected
 * segment becomes a full pill (20 dp), takes the tonal cover fill and shows a check. On a floating layer ([floating]) it is a
 * glass track (radius 24, 4 dp padding) with an ink thumb, 32 dp tall, that slides to the selected option on the move spring
 * and inverts the label it covers; its options share the width of the widest.
 *
 * Semantics: the row is a selectable group and each segment a `Role.Tab` with its selected state. Selecting plays the select
 * haptic.
 *
 * Outer and inner corners differ in a segment, so a segment is drawn with four circular corner radii (a `RoundRect` refilled
 * into one `Path` each frame: no shape object per frame) rather than the superellipse of the other controls.
 */
@Composable
fun CosmoSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    floating: Boolean = false,
) {
    if (floating) {
        FloatingSegmented(options, selectedIndex, onSelect, modifier)
        return
    }
    Row(modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(ControlMetrics.SEGMENT_GAP_DP.dp)) {
        options.forEachIndexed { index, label ->
            Segment(label, index, options.size, selected = index == selectedIndex) { onSelect(index) }
        }
    }
}

@Composable
private fun Segment(label: String, index: Int, count: Int, selected: Boolean, onSelect: () -> Unit) {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val reduced = LocalReducedMotion.current
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val target = ControlMorph.segment(ControlMetrics.SEGMENT_HEIGHT_DP, selected, pressed)
    val inner = remember { Animatable(target.radiusDp) }
    val layer = remember { Animatable(0f) }
    val layerTarget = if (pressed) STATE_LAYER_PRESSED else if (hovered) STATE_LAYER_HOVERED else 0f
    LaunchedEffect(target.radiusDp, reduced) { inner.animateTo(target.radiusDp, CosmoMotion.press(reduced)) }
    LaunchedEffect(layerTarget, reduced) { layer.animateTo(layerTarget, CosmoMotion.effects(reduced)) }
    val corners = remember { FloatArray(4) }
    val outline = remember { Path() }
    val ink = Color(if (selected) palette.onAccentContainer else palette.textSecondary)
    Row(
        Modifier
            .minimumInteractiveComponentSize()
            .height(ControlMetrics.SEGMENT_HEIGHT_DP.dp)
            .cosmoFocusRing(interaction, ControlMetrics.SEGMENT_OUTER_RADIUS_DP.dp)
            .drawBehind {
                keepRedrawnWith(palette)
                ControlMetrics.segmentCorners(index, count, inner.value, corners)
                outline.reset()
                outline.addRoundRect(
                    RoundRect(
                        0f, 0f, size.width, size.height,
                        topLeftCornerRadius = CornerRadius(corners[0].dp.toPx()), topRightCornerRadius = CornerRadius(corners[1].dp.toPx()),
                        bottomRightCornerRadius = CornerRadius(corners[2].dp.toPx()), bottomLeftCornerRadius = CornerRadius(corners[3].dp.toPx()),
                    ),
                )
                if (selected) drawPath(outline, tonalFill(colors)) else drawPath(outline, SolidColor(colors.ink(ControlMetrics.SEGMENT_REST_FILL)))
                if (layer.value > 0.001f) drawPath(outline, ink.copy(alpha = layer.value))
                if (selected) topLine(outline, Color.White.copy(alpha = ControlMetrics.HIGHLIGHT_TONAL))
                else innerRing(outline, colors.ink(ControlMetrics.OUTLINE), 1f)
            }
            .hoverable(interaction)
            .selectable(selected, interaction, indication = null, role = Role.Tab) { haptics.play(Haptic.Select); onSelect() }
            .padding(horizontal = ControlMetrics.SEGMENT_PADDING_DP.dp),
        horizontalArrangement = Arrangement.spacedBy(SEGMENT_ICON_GAP_DP.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) Icon(CosmoIcons.Check, contentDescription = null, tint = ink, modifier = Modifier.size(ControlMetrics.SEGMENT_ICON_DP.dp))
        SegmentLabel(label, ink)
    }
}

@Composable
private fun SegmentLabel(label: String, ink: Color) {
    val type = LocalCosmoType.current
    Text(
        text = label,
        color = ink,
        style = type.forText(type.label.copy(fontWeight = FontWeight(SEGMENT_WEIGHT)), label),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun FloatingSegmented(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier) {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val reduced = LocalReducedMotion.current
    val haptics = rememberHaptics()
    val thumb = remember { Animatable(selectedIndex.toFloat()) }
    LaunchedEffect(selectedIndex, reduced) { thumb.animateTo(selectedIndex.toFloat(), CosmoMotion.move(reduced)) }
    val track = remember { Path() }
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .height(ControlMetrics.SEGMENT_HEIGHT_DP.dp)
            .drawBehind {
                keepRedrawnWith(palette)
                val radius = min(ControlMetrics.C_SEGMENT_TRACK_RADIUS_DP.dp.toPx(), size.height / 2f)
                track.reset()
                track.addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(radius)))
                drawPath(track, colors.glassFill())
                innerRing(track, (if (colors.dark) Color.White else Color.Black).copy(alpha = if (colors.dark) 0.12f else ControlMetrics.glassRing(false)), 1f)
            }
            .padding(ControlMetrics.C_SEGMENT_TRACK_PADDING_DP.dp),
    ) {
        Row(
            Modifier
                .width(IntrinsicSize.Max)
                .height(ControlMetrics.C_SEGMENT_THUMB_DP.dp)
                .selectableGroup()
                .drawBehind {
                    // The thumb: an ink pill that covers one segment; `thumb` is in segments, so it slides between them.
                    val each = size.width / options.size
                    drawRoundRect(colors.ink(1f), Offset(thumb.value * each, 0f), Size(each, size.height), CornerRadius(size.height / 2f))
                },
        ) {
            options.forEachIndexed { index, label ->
                FloatingSegment(label, selected = index == selectedIndex, Modifier.weight(1f).fillMaxHeight()) { haptics.play(Haptic.Select); onSelect(index) }
            }
        }
    }
}

@Composable
private fun FloatingSegment(label: String, selected: Boolean, modifier: Modifier, onSelect: () -> Unit) {
    val palette = LocalCosmoColors.current.palette
    val reduced = LocalReducedMotion.current
    val interaction = remember { MutableInteractionSource() }
    // the inverted label arrives with the thumb, not before it
    val ink by animateColorAsState(Color(if (selected) palette.backgroundBottom else palette.textSecondary), CosmoMotion.effects(reduced))
    Box(
        modifier
            .cosmoFocusRing(interaction, (ControlMetrics.C_SEGMENT_THUMB_DP / 2f).dp, floating = true)
            .hoverable(interaction)
            .selectable(selected, interaction, indication = null, role = Role.Tab, onClick = onSelect)
            .padding(horizontal = ControlMetrics.SEGMENT_PADDING_DP.dp),
        contentAlignment = Alignment.Center,
    ) { SegmentLabel(label, ink) }
}
