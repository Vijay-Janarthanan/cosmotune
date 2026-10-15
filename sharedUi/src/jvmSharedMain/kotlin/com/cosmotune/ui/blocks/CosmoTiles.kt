package com.cosmotune.ui.blocks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.design.BlockMetrics
import com.cosmotune.design.ColorMix
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.components.CoverImage
import com.cosmotune.ui.components.MorphBox
import com.cosmotune.ui.components.SuperellipseShape
import com.cosmotune.ui.components.cssGradient
import com.cosmotune.ui.components.heroFill
import com.cosmotune.ui.theme.CosmoColors
import com.cosmotune.ui.theme.drawEllipseGlow
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.music.bitchord.ui.components.contextClick
import com.music.bitchord.ui.components.longPressMenuClickable
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import com.cosmotune.ui.components.CosmoIcons

/*
 * Cards and tiles that hold a page's blocks (`tokens-v3bc.css` `--tile-fill`, `screens-v2.css` `.liked2`, `.mix2`, `.fan`): no
 * outline and no shadow, the cover's own colours inside.
 */

/**
 * The fill of a card or tile: a 180 degree gradient from `mix(surfaceHigh, surface, 0.4)` to `surface`, clipped to [shape]
 * (the spec: card 24, tile 28, superellipse). The colours are read while drawing, so the tile follows a palette fade.
 */
@Composable
fun Modifier.cosmoTile(shape: Shape): Modifier {
    val colors = LocalCosmoColors.current
    return clip(shape).drawBehind { drawRect(tileFill(colors)) }
}

private fun DrawScope.tileFill(c: CosmoColors): Brush {
    val high = c.live(CoverField.SURFACE_HIGH).toArgb()
    val base = c.live(CoverField.SURFACE).toArgb()
    return cssGradient(180f, ColorMix.mix(high, base, 0.4f), base)
}

/** The ground of a mix tile and a mood tile: `backgroundTop` to `backgroundBottom` at 160 degrees, in OKLab (`--c-bg-top`, `--c-bg-bottom`). */
internal fun DrawScope.groundFill(c: CosmoColors): Brush =
    cssGradient(160f, c.live(CoverField.BACKGROUND_TOP).toArgb(), c.live(CoverField.BACKGROUND_BOTTOM).toArgb())

/**
 * `radial-gradient(<rx> <ry> at <cx> <cy>, ambient, transparent 70%)` over the box: [cx] and [cy] are fractions of the width and
 * the height of the centre, [rx] and [ry] the radii as fractions of the width and the height. It fades to its own transparent, not
 * black, as the CSS does; the stretched circle is the page's ([drawEllipseGlow]).
 */
internal fun DrawScope.ambientWash(c: CosmoColors, cx: Float, cy: Float, rx: Float, ry: Float) {
    val ambient = c.live(CoverField.AMBIENT)
    drawEllipseGlow(arrayOf(0f to ambient, 0.7f to ambient.copy(alpha = 0f)), size.width * cx, size.height * cy, size.width * rx, size.height * ry)
}

/**
 * The pinned "Liked songs" tile (`.liked2`): 172 dp tall, superellipse 26, filled with the hero gradient (`buttonGradientStart` to
 * `buttonGradientEnd` at 145 degrees), a heart in a 52 dp tile at the top left, and at the bottom the title in Archivo (22 sp) and
 * [subtitle]. A screen draws at most one gradient tile. Hold (or a right click on the desktop) calls [onLongPress], the Library's menu
 * for the playlist; without it the tile has no hold.
 */
@Composable
fun CosmoLikedTile(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null,
) {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val type = LocalCosmoType.current
    val haptics = rememberHaptics()
    val onAccent = Color(palette.onAccent)
    MorphBox(
        targetFor = { pressed -> BlockMetrics.tileTarget(pressed) },
        onClick = { haptics.play(Haptic.Tap); onClick() },
        modifier = modifier.fillMaxWidth().height(BlockMetrics.TILE_HEIGHT_DP.dp).contextClick(onLongPress),
        onLongPress = onLongPress,
        stateLayer = onAccent,
        fill = { heroFill(colors) },
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(14.dp)
                    .size(52.dp)
                    .clip(SuperellipseShape(18.dp))
                    .drawBehind { drawRect(colors.live(CoverField.ON_ACCENT).copy(alpha = 0.12f)) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(CosmoIcons.HeartFill, contentDescription = null, tint = onAccent, modifier = Modifier.size(32.dp))
            }
            Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                Text(
                    text = title,
                    style = type.forText(tileTitleStyle(type), title),
                    color = onAccent,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = type.forText(tileSubtitleStyle(type), subtitle),
                        color = onAccent.copy(alpha = 0.78f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Archivo, weight 760, 22 sp, line height 1.05 (`.mix-txt b`, `.liked2 b`). */
internal fun tileTitleStyle(type: com.cosmotune.ui.theme.CosmoType): TextStyle =
    TextStyle(fontFamily = type.displayFamily, fontWeight = FontWeight(760), fontSize = 22.sp, lineHeight = 22.sp * 1.05f)

internal fun tileSubtitleStyle(type: com.cosmotune.ui.theme.CosmoType): TextStyle =
    TextStyle(fontFamily = type.textFamily, fontWeight = FontWeight.Normal, fontSize = 12.5.sp, lineHeight = 12.5.sp * 1.35f)

/**
 * A mix (`.mix2`, `.fan`): 172 dp tall; the cover's ground (`backgroundTop` to `backgroundBottom`) with a wash of `ambient` from the
 * top corner, three of its [covers] fanned at the top right, each with its shadow, and the title (Archivo 22 sp) and [subtitle]
 * at the bottom left. Hold calls [onLongPress].
 */
@Composable
fun CosmoMixTile(
    title: String,
    subtitle: String?,
    covers: List<String?>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null,
) {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val type = LocalCosmoType.current
    val shape = remember { SuperellipseShape(BlockMetrics.TILE_RADIUS_DP.dp) }
    Box(
        modifier
            .fillMaxWidth()
            .height(BlockMetrics.TILE_HEIGHT_DP.dp)
            .semantics(mergeDescendants = true) { role = Role.Button }
            // The press highlight has to stop at the corners, and so does the picture taken when the tile is held.
            .clip(shape)
            .longPressMenuClickable(onClick = onClick, onLongClick = onLongPress)
            .contextClick(onLongPress)
            .clip(shape)
            .drawBehind {
                drawRect(groundFill(colors))
                ambientWash(colors, cx = 1f, cy = 0f, rx = 1.2f, ry = 0.9f)
            },
    ) {
        // The first three covers, in the fan's order; a mix with fewer keeps its covers in the middle of the fan.
        val shown = covers.take(FAN.size)
        val slots = when (shown.size) { 1 -> listOf(1); 2 -> listOf(1, 2); else -> listOf(0, 1, 2) }
        Box(Modifier.align(Alignment.TopEnd).padding(top = 14.dp, end = 12.dp).size(132.dp, 96.dp)) {
            shown.forEachIndexed { index, url ->
                val place = FAN[slots[index]]
                CoverImage(
                    url = url,
                    size = 76.dp,
                    radius = 14.dp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = place.top.dp, end = place.right.dp)
                        .graphicsLayer { rotationZ = place.degrees },
                    shadow = true,
                )
            }
        }
        Column(Modifier.align(Alignment.BottomStart).padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) {
            Text(
                text = title,
                style = type.forText(tileTitleStyle(type), title),
                color = Color(palette.textPrimary),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = type.forText(tileSubtitleStyle(type), subtitle),
                    color = Color(palette.textSecondary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Where a cover of the fan stands in its 132 x 96 box (`.fan .cv:nth-child(1..3)`): right, top, and the turn in degrees. */
private class FanPlace(val right: Float, val top: Float, val degrees: Float)

private val FAN = listOf(FanPlace(52f, 12f, -13f), FanPlace(26f, 2f, -2f), FanPlace(0f, 14f, 11f))
