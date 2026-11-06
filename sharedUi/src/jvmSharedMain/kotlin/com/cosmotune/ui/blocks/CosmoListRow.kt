package com.cosmotune.ui.blocks

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.cosmotune.design.BlockMetrics
import com.cosmotune.design.CoverArt
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.CoverImage
import com.cosmotune.ui.components.SuperellipseShape
import com.cosmotune.ui.components.tonalFill
import com.cosmotune.ui.orbit.OrbitSongRow
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.theme.CosmoColors
import com.cosmotune.ui.theme.CosmoMotion
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.CosmoType
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.LocalReducedMotion
import com.cosmotune.ui.theme.tabular
import com.cosmotune.music.data.model.artworkAt
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.now_playing
import com.cosmotune.music.ui.components.contextClick
import com.cosmotune.music.ui.components.longPressMenuClickable
import org.jetbrains.compose.resources.stringResource

/*
 * The list row (DESIGN_SYSTEM.md 4.6): a 64 dp row with a cover, a number or an icon in front, a title, a subtitle and an optional
 * third line, and whatever the caller puts at the end, on the page's ground with a hairline from the text column on. The row that is
 * playing has its title in the accent and shows the equaliser; the selected one stands on `surface3`.
 */

/** What stands in front of a row's text. */
@Immutable
sealed interface CosmoRowLeading {
    /** A 48 dp cover (radius 8), or a 48 dp circle for an artist. */
    data class Cover(val url: String?, val circle: Boolean = false) : CosmoRowLeading

    /** A track number in a 22 dp column; the equaliser stands in its place on the row that is playing. */
    data class Number(val number: Int) : CosmoRowLeading

    /** A 24 dp icon in the secondary ink. */
    data class Icon(val icon: ImageVector) : CosmoRowLeading

    /** A mark (the Spotify logo) in the same place, drawn in its own colours. */
    data class IconPainter(val painter: Painter) : CosmoRowLeading

    data object None : CosmoRowLeading
}

/**
 * A row of a list, in Orbit: the one Orbit row ([OrbitSongRow]), radius 22 on the ink at 5 %, inset by the page's margin and 3 dp
 * from its neighbours. A cover (an artist's too) is a disc, a track number stands in the disc's place in the narrow face, an icon sits
 * on a quiet disc. The row that [isCurrent] has the peach wash, a peach title and the orbit round its disc (circling while
 * [isPlaying]). [onClick] null makes the row a plain display (no press, no role); [onLongPress] is what a hold, and a right click on
 * the desktop, does. [height] is clamped to Orbit's 62 to 68 dp. [why], the third line, follows the subtitle on its line.
 *
 * [titleContent] replaces the title text (the song row draws its explicit mark with it). [trailing] follows the text.
 */
@Composable
fun CosmoListRow(
    title: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    why: String? = null,
    leading: CosmoRowLeading = CosmoRowLeading.None,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    height: Dp = 64.dp,
    titleContent: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val line = listOfNotNull(subtitle?.takeIf { it.isNotBlank() }, why?.takeIf { it.isNotBlank() }).joinToString(" · ")
    val none = leading is CosmoRowLeading.None
    OrbitSongRow(
        title = title,
        subtitle = line,
        cover = (leading as? CosmoRowLeading.Cover)?.url,
        fonts = fonts,
        look = look,
        onClick = onClick,
        modifier = modifier.fillMaxWidth().padding(horizontal = CosmoSpacing.margin, vertical = 3.dp),
        now = isCurrent,
        playing = isPlaying,
        onLongClick = onLongPress,
        height = height.coerceIn(62.dp, 68.dp),
        icon = (leading as? CosmoRowLeading.Icon)?.icon,
        // No leading: the disc's box shrinks to a sliver, so the words start near the row's edge.
        discSize = if (none) 0.dp else 44.dp,
        gap = if (none) 6.dp else 12.dp,
        titleContent = titleContent,
        discContent = when (leading) {
            is CosmoRowLeading.Number -> {
                {
                    Text(
                        "${leading.number}",
                        style = fonts.number.copy(fontSize = 26.sp, lineHeight = 26.sp),
                        color = if (isCurrent) look.accent else look.ink.copy(alpha = 0.4f),
                        maxLines = 1,
                    )
                }
            }
            is CosmoRowLeading.IconPainter -> {
                {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(look.chip), contentAlignment = Alignment.Center) {
                        Icon(leading.painter, contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(22.dp))
                    }
                }
            }
            CosmoRowLeading.None -> { {} }
            else -> null
        },
        trailing = trailing,
    )
}

/**
 * The body of every row: the song row shares it with [CosmoListRow], and differs only in what stands in front ([leadingContent],
 * which may be a check disc), in [washed] (the current row and a selected one are both washed, only the current one has its title
 * in the accent), and in what a right click does ([onSecondaryClick]: the old song row opened the actions sheet).
 */
@Composable
internal fun CosmoRowFrame(
    title: String,
    onClick: (() -> Unit)?,
    modifier: Modifier,
    subtitle: String?,
    why: String?,
    isCurrent: Boolean,
    isPlaying: Boolean,
    washed: Boolean,
    hasLeading: Boolean,
    numbered: Boolean,
    onLongPress: (() -> Unit)?,
    onSecondaryClick: (() -> Unit)?,
    height: Dp,
    titleContent: (@Composable () -> Unit)?,
    leadingContent: @Composable () -> Unit,
    trailing: (@Composable RowScope.() -> Unit)?,
    /** Drawn before the subtitle on its line, in the subtitle's colour (a song row's source-quality badge). */
    subtitleLeading: (@Composable (color: Color, style: TextStyle) -> Unit)? = null,
) {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val type = LocalCosmoType.current
    val reduced = LocalReducedMotion.current
    // The wash fades in and out as the song changes; its alpha is read while drawing, so the row does not recompose per frame.
    val washAlpha = animateFloatAsState(if (washed && !isCurrent) 1f else 0f, CosmoMotion.effects(reduced), label = "row wash")
    val interaction = if (onClick != null) Modifier.longPressMenuClickable(onClick = onClick, onLongClick = onLongPress).contextClick(onSecondaryClick) else Modifier
    // The gap between the pieces of a row (4.6): 16 dp; a numbered track list sits a little closer.
    val gap = (if (numbered) 12f else BlockMetrics.ROW_ART_GAP_DP).dp
    val hairlineStart = CosmoSpacing.margin + (if (!hasLeading) 0.dp else if (numbered) NUMBER_COLUMN + gap else BlockMetrics.ROW_ART_DP.dp + gap)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = height)
            .semantics(mergeDescendants = true) { if (onClick != null) role = Role.Button }
            // The hold picture is taken from what is below this modifier: the row with its wash.
            .then(interaction)
            .rowWash(colors, washAlpha)
            .rowHairline(colors, hairlineStart)
            .padding(horizontal = CosmoSpacing.margin),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leadingContent()
        Column(Modifier.weight(1f).padding(start = if (hasLeading) gap else 0.dp)) {
            if (titleContent != null) {
                titleContent()
            } else {
                Text(
                    text = title,
                    style = type.forText(type.rowTitleStyle(), title),
                    color = rowTitleColor(colors, isCurrent),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (subtitleLeading != null) {
                val style = type.forText(type.rowSubtitleStyle(), subtitle.orEmpty())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    subtitleLeading(Color(palette.textSecondary), style)
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = " · $subtitle",
                            style = style,
                            color = Color(palette.textSecondary),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            } else if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = type.forText(type.rowSubtitleStyle(), subtitle),
                    color = Color(palette.textSecondary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!why.isNullOrBlank()) {
                Text(
                    text = why,
                    style = type.forText(type.rowWhyStyle(), why),
                    color = Color(palette.textTertiary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
        // A cover or an icon leaves no room for the bars, so the row that is playing shows them at its end; a number gives its place.
        if (isCurrent && !numbered) {
            Spacer(Modifier.width(gap))
            CosmoEqualiser(playing = isPlaying)
        }
        if (trailing != null) {
            Spacer(Modifier.width(gap))
            trailing()
        }
    }
}

/** The title of a row: `rowTitle`. */
internal fun CosmoType.rowTitleStyle(): TextStyle = rowTitle

/** The subtitle: `meta`, `textSecondary`. */
internal fun CosmoType.rowSubtitleStyle(): TextStyle = meta

/** The third line: `caption`, `textTertiary`. */
internal fun CosmoType.rowWhyStyle(): TextStyle = caption

/** The title's colour: `textPrimary`, and `accentStrong` on the row that is playing. */
internal fun rowTitleColor(colors: CosmoColors, isCurrent: Boolean): Color =
    Color(if (isCurrent) colors.palette.accentStrong else colors.palette.textPrimary)

/** The front of a row: what [CosmoRowLeading] says, and the equaliser in the place of a number on the current row. */
@Composable
internal fun RowLeadingContent(leading: CosmoRowLeading, isCurrent: Boolean, isPlaying: Boolean) {
    val palette = LocalCosmoColors.current.palette
    when (leading) {
        is CosmoRowLeading.Cover ->
            if (leading.circle) CircleCover(leading.url, BlockMetrics.ROW_ART_DP.dp, shadow = false)
            else CoverImage(leading.url, BlockMetrics.ROW_ART_DP.dp, CosmoRadii.rSm, shadow = false)
        is CosmoRowLeading.Number -> Box(Modifier.width(NUMBER_COLUMN), contentAlignment = Alignment.Center) {
            if (isCurrent) {
                CosmoEqualiser(playing = isPlaying)
            } else {
                val type = LocalCosmoType.current
                Text(
                    text = "${leading.number}",
                    style = type.meta.tabular(),
                    color = Color(palette.textTertiary),
                    maxLines = 1,
                )
            }
        }
        is CosmoRowLeading.Icon -> IconSlot {
            Icon(leading.icon, contentDescription = null, tint = Color(palette.textSecondary), modifier = Modifier.size(24.dp))
        }
        is CosmoRowLeading.IconPainter -> IconSlot {
            Icon(leading.painter, contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(24.dp))
        }
        CosmoRowLeading.None -> Unit
    }
}

/** An icon's place in front of a row: as wide as a cover, so the text columns of a list line up. No tile: the icon stands on the page. */
@Composable
private fun IconSlot(content: @Composable () -> Unit) {
    Box(Modifier.size(BlockMetrics.ROW_ART_DP.dp), contentAlignment = Alignment.Center) { content() }
}

/** A track number's column. */
private val NUMBER_COLUMN = 22.dp

/** The 1 dp hairline under a row, from the text column to the end. */
private fun Modifier.rowHairline(colors: CosmoColors, start: Dp): Modifier = drawBehind {
    val line = 1.dp.toPx()
    val inset = start.toPx()
    val rtl = layoutDirection == LayoutDirection.Rtl
    drawRect(
        colors.live(CoverField.TEXT_PRIMARY).copy(alpha = 0.08f),
        topLeft = androidx.compose.ui.geometry.Offset(if (rtl) 0f else inset, size.height - line),
        size = androidx.compose.ui.geometry.Size(size.width - inset, line),
    )
}

/** The picture of an artist: [size] square, a circle, the placeholder in `surfaceVariant` behind it (the cover rim and shadow are for squares). */
@Composable
internal fun CircleCover(url: String?, size: Dp, shadow: Boolean, modifier: Modifier = Modifier) {
    val colors = LocalCosmoColors.current
    val density = LocalDensity.current.density
    val px = remember(size, density) { CoverArt.px(size.value, density) }
    val lift = if (shadow) {
        val palette = colors.palette
        Modifier.shadow(14.dp, CircleShape, clip = false, ambientColor = Color.Black.copy(alpha = 0.45f), spotColor = Color(palette.shadow).copy(alpha = 1f))
    } else Modifier
    Box(
        modifier
            .size(size)
            .then(lift)
            .clip(CircleShape)
            .drawBehind { drawRect(colors.live(CoverField.SURFACE_VARIANT)) },
    ) {
        AsyncImage(
            model = url.artworkAt(px),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size),
        )
    }
}

/** The selected row's fill (DESIGN_SYSTEM.md 5.8): `surface3` across the row. [alpha] is the fade in or out. */
private fun Modifier.rowWash(colors: CosmoColors, alpha: State<Float>): Modifier = drawBehind {
    val a = alpha.value
    if (a <= 0.001f) return@drawBehind
    drawRect(colors.live(CoverField.SURFACE_VARIANT).copy(alpha = a))
}

/**
 * The equaliser (`.eq`): three 3 dp bars, 2 dp apart, in `accent` (or [color]), standing at their rest heights; while [playing]
 * they bounce, each on its own beat. Under reduced motion they stand still. The heights are read while drawing, so nothing
 * recomposes per frame, and a paused row has no animation running at all.
 */
@Composable
internal fun CosmoEqualiser(playing: Boolean, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    val colors = LocalCosmoColors.current
    val reduced = LocalReducedMotion.current
    val label = stringResource(Res.string.now_playing)
    val box = modifier
        .size(EQ_WIDTH, BlockMetrics.EQ_HEIGHT_DP.dp)
        .semantics { contentDescription = label }
    if (playing && !reduced) {
        val transition = rememberInfiniteTransition(label = "equaliser")
        val phases = List(3) { index ->
            transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(EQ_PERIOD_MS + index * EQ_PERIOD_STEP_MS, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "equaliser bar $index",
            )
        }
        EqualiserBars(box, colors, color) { index ->
            val rest = BlockMetrics.equaliserRest(index)
            rest + (BlockMetrics.equaliserPeak(index) - rest) * phases[index].value
        }
    } else {
        EqualiserBars(box, colors, color) { index -> BlockMetrics.equaliserRest(index) }
    }
}

@Composable
private fun EqualiserBars(modifier: Modifier, colors: CosmoColors, override: Color, height: (Int) -> Float) {
    Box(
        modifier.drawBehind {
            val ink = if (override.isSpecified) override else colors.live(CoverField.ACCENT)
            val bar = BlockMetrics.EQ_BAR_DP.dp.toPx()
            val gap = BlockMetrics.EQ_GAP_DP.dp.toPx()
            val radius = CornerRadius(BlockMetrics.EQ_RADIUS_DP.dp.toPx())
            for (index in 0..2) {
                val h = height(index) * size.height
                drawRoundRect(ink, Offset(index * (bar + gap), size.height - h), Size(bar, h), radius)
            }
        },
    )
}

private val EQ_WIDTH = (3 * BlockMetrics.EQ_BAR_DP + 2 * BlockMetrics.EQ_GAP_DP).dp
private const val EQ_PERIOD_MS = 480
private const val EQ_PERIOD_STEP_MS = 90
