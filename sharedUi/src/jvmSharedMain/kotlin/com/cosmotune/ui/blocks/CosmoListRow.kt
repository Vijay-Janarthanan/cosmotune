package com.cosmotune.ui.blocks

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
 * The list row of the B+C system (`components-v3bc.css` `.mrow`, `.row2`): a 64 dp row with a cover, a number or an icon tile
 * in front, a title, a subtitle and an optional third line, and whatever the caller puts at the end. No dividers. The row that
 * is playing is washed in the cover's colour and shows the equaliser.
 */

/** What stands in front of a row's text. */
@Immutable
sealed interface CosmoRowLeading {
    /** A 52 dp cover (radius 12), or a 52 dp circle for an artist. */
    data class Cover(val url: String?, val circle: Boolean = false) : CosmoRowLeading

    /** A track number in a 22 dp column; the equaliser stands in its place on the row that is playing. */
    data class Number(val number: Int) : CosmoRowLeading

    /** An icon in a 40 dp tonal tile. */
    data class Icon(val icon: ImageVector) : CosmoRowLeading

    /** A mark (the Spotify logo) in the same tile, drawn in its own colours. */
    data class IconPainter(val painter: Painter) : CosmoRowLeading

    data object None : CosmoRowLeading
}

/**
 * A row of a list. [onClick] null makes the row a plain display (no press, no role); [onLongPress] is what a hold, and a right
 * click on the desktop, does, through the same modifiers the old rows used, so the held-row menu still lifts the row. [height]
 * is a minimum: a row grows with a large system font instead of cutting its text.
 *
 * [titleContent] replaces the title text (the song row draws its explicit mark with it) and is drawn in [rowTitleStyle] and
 * [rowTitleColor]. [trailing] follows the text after a gap.
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
    CosmoRowFrame(
        title = title,
        onClick = onClick,
        modifier = modifier,
        subtitle = subtitle,
        why = why,
        isCurrent = isCurrent,
        isPlaying = isPlaying,
        washed = isCurrent,
        hasLeading = leading !is CosmoRowLeading.None,
        numbered = leading is CosmoRowLeading.Number,
        onLongPress = onLongPress,
        onSecondaryClick = onLongPress,
        height = height,
        titleContent = titleContent,
        leadingContent = { RowLeadingContent(leading, isCurrent, isPlaying) },
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
    val washAlpha = animateFloatAsState(if (washed) 1f else 0f, CosmoMotion.effects(reduced), label = "row wash")
    val interaction = if (onClick != null) Modifier.longPressMenuClickable(onClick = onClick, onLongClick = onLongPress).contextClick(onSecondaryClick) else Modifier
    // The gap between the pieces of a row: 14 dp; a numbered track list sits a little closer (`.tracks2 .mrow`: 12).
    val gap = (if (numbered) 12 else 14).dp
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = height)
            .semantics(mergeDescendants = true) { if (onClick != null) role = Role.Button }
            // The hold picture is taken from what is below this modifier: the row with its wash.
            .then(interaction)
            .rowWash(colors, washAlpha)
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

/** The title of a row: Hanken Grotesk 15.5 sp, weight 650, line height 1.3 (`.mrow .ttl`). */
internal fun CosmoType.rowTitleStyle(): TextStyle =
    TextStyle(fontFamily = textFamily, fontWeight = FontWeight(650), fontSize = 15.5.sp, lineHeight = 15.5.sp * 1.3f)

/** The subtitle (`.mrow .sub`): 13 sp, `textSecondary`. */
internal fun CosmoType.rowSubtitleStyle(): TextStyle =
    TextStyle(fontFamily = textFamily, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 13.sp * 1.35f)

/** The third line (`.mrow .why`): 12.5 sp, `textTertiary`. */
internal fun CosmoType.rowWhyStyle(): TextStyle =
    TextStyle(fontFamily = textFamily, fontWeight = FontWeight.Normal, fontSize = 12.5.sp, lineHeight = 12.5.sp * 1.35f)

/** The title's colour: `textPrimary`, and `accentStrong` on the row that is playing. */
internal fun rowTitleColor(colors: CosmoColors, isCurrent: Boolean): Color =
    Color(if (isCurrent) colors.palette.accentStrong else colors.palette.textPrimary)

/** The front of a row: what [CosmoRowLeading] says, and the equaliser in the place of a number on the current row. */
@Composable
internal fun RowLeadingContent(leading: CosmoRowLeading, isCurrent: Boolean, isPlaying: Boolean) {
    val palette = LocalCosmoColors.current.palette
    when (leading) {
        is CosmoRowLeading.Cover ->
            if (leading.circle) CircleCover(leading.url, BlockMetrics.ROW_COVER_DP.dp, shadow = false)
            else CoverImage(leading.url, BlockMetrics.ROW_COVER_DP.dp, CosmoRadii.coverRow, shadow = false)
        is CosmoRowLeading.Number -> Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
            if (isCurrent) {
                CosmoEqualiser(playing = isPlaying)
            } else {
                val type = LocalCosmoType.current
                Text(
                    text = "${leading.number}",
                    style = type.body(weight = FontWeight.Normal).copy(fontSize = 14.sp, lineHeight = 14.sp * 1.3f).tabular(),
                    color = Color(palette.textTertiary),
                    maxLines = 1,
                )
            }
        }
        is CosmoRowLeading.Icon -> IconTile {
            Icon(leading.icon, contentDescription = null, tint = Color(palette.onAccentContainer), modifier = Modifier.size(20.dp))
        }
        is CosmoRowLeading.IconPainter -> IconTile {
            Icon(leading.painter, contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(20.dp))
        }
        CosmoRowLeading.None -> Unit
    }
}

/** `.row2 .lead`: a 40 dp superellipse tile (radius 14) in the tonal fill. */
@Composable
private fun IconTile(content: @Composable () -> Unit) {
    val colors = LocalCosmoColors.current
    Box(
        Modifier
            .size(40.dp)
            .clip(SuperellipseShape(14.dp))
            .drawBehind { drawRect(tonalFill(colors)) },
        contentAlignment = Alignment.Center,
    ) { content() }
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

/**
 * `--row-on`: a wash 8 dp in from each side with radius 18, `accentContainer` at 70 % fading to 20 % by 75 % of the width and to
 * nothing, from the start edge (the end edge in a right-to-left language). [alpha] is the fade in or out.
 */
private fun Modifier.rowWash(colors: CosmoColors, alpha: State<Float>): Modifier = drawBehind {
    val a = alpha.value
    if (a <= 0.001f) return@drawBehind
    val inset = BlockMetrics.ROW_WASH_INSET_DP.dp.toPx()
    val left = inset
    val right = size.width - inset
    val container = colors.live(CoverField.ACCENT_CONTAINER)
    val rtl = layoutDirection == LayoutDirection.Rtl
    val brush = Brush.horizontalGradient(
        0f to container.copy(alpha = BlockMetrics.ROW_WASH_START_ALPHA * a),
        BlockMetrics.ROW_WASH_MID_STOP to container.copy(alpha = BlockMetrics.ROW_WASH_MID_ALPHA * a),
        1f to container.copy(alpha = 0f),
        startX = if (rtl) right else left,
        endX = if (rtl) left else right,
    )
    drawRoundRect(brush, Offset(left, 0f), Size(right - left, size.height), CornerRadius(BlockMetrics.ROW_WASH_RADIUS_DP.dp.toPx()))
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
