package com.cosmotune.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIconButtonStyle
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.nowplaying.QualityBadge
import com.cosmotune.ui.nowplaying.QualityPill
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.tabular

/** The shape of the art a row or a card leads with: a square cover, a round avatar, a 16:9 thumbnail, a 2:3 poster (a book, a manga). */
enum class KitArt(val ratio: Float) { SQUARE(1f), ROUND(1f), WIDE(16f / 9f), POSTER(2f / 3f) }

/**
 * The art itself: [url] in [art]'s shape, [height] tall (the width follows the ratio), 8 dp corners (a circle for [KitArt.ROUND]),
 * a quiet `surfaceVariant` while it loads or when there is none.
 */
@Composable
fun KitArtwork(url: String?, art: KitArt, height: Dp, modifier: Modifier = Modifier, radius: Dp = CosmoRadii.rSm, contentDescription: String? = null) {
    val palette = LocalCosmoColors.current.palette
    val shape = if (art == KitArt.ROUND) CircleShape else RoundedCornerShape(radius)
    Box(modifier.size(height * art.ratio, height).clip(shape).background(Color(palette.surfaceVariant))) {
        if (url != null) AsyncImage(model = url, contentDescription = contentDescription, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
    }
}

/**
 * A media row (Lokal's dense rows, collapsed for the phone): [number] or art, [title] over [subtitle] (artist, channel, author),
 * an optional [quality] pill, the [trailingText] (a duration, a count) and a more button; [artHeight] replaces the art's usual height. Sits directly on the page; [current]
 * draws the title in the accent. Works for songs, episodes, videos (with [KitArt.WIDE]), chapters and books ([KitArt.POSTER]).
 */
@Composable
fun KitMediaRow(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    artUrl: String? = null,
    art: KitArt = KitArt.SQUARE,
    number: Int? = null,
    quality: QualityBadge? = null,
    trailingText: String? = null,
    current: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onMore: (() -> Unit)? = null,
    moreLabel: String = "More",
    artHeight: Dp? = null,
) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val artHeight = artHeight ?: if (art == KitArt.WIDE) 56.dp else if (art == KitArt.POSTER) 64.dp else 48.dp
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongClick)
            .padding(start = CosmoSpacing.margin, end = if (onMore != null) 4.dp else CosmoSpacing.margin, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (number != null) {
            Text(
                number.toString(),
                style = type.meta.tabular(),
                color = Color(if (current) palette.accentStrong else palette.textTertiary),
                modifier = Modifier.width(28.dp),
                maxLines = 1,
            )
        } else if (artUrl != null || art != KitArt.SQUARE) {
            KitArtwork(artUrl, art, artHeight)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = type.forText(type.rowTitle, title),
                color = Color(if (current) palette.accentStrong else palette.textPrimary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = type.forText(type.meta, subtitle), color = Color(palette.textSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (quality != null) {
            Spacer(Modifier.width(8.dp))
            QualityPill(quality, compact = true)
        }
        if (trailingText != null) {
            Spacer(Modifier.width(10.dp))
            Text(trailingText, style = type.meta.tabular(), color = Color(palette.textTertiary), maxLines = 1)
        }
        if (onMore != null) CosmoIconButton(CosmoIcons.MoreVertical, moreLabel, onMore, style = CosmoIconButtonStyle.PLAIN, size = 40.dp)
    }
}

/**
 * A real group in one card (Prism's settings): `surface`, 20 dp corners, the page's margin at the sides, the rows inside separated by
 * [KitDivider]s. Only groups get a card; single rows sit on the page.
 */
@Composable
fun KitGroup(modifier: Modifier = Modifier, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    Column(modifier.fillMaxWidth().padding(horizontal = CosmoSpacing.margin)) {
        if (!title.isNullOrBlank()) {
            Text(title.uppercase(), style = type.overline, color = Color(palette.textSecondary), modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp))
        }
        Column(Modifier.fillMaxWidth().clip(GROUP_SHAPE).background(Color(palette.surface)), content = content)
    }
}

/** A hairline between the rows of a [KitGroup] (ink at 8 %), inset by [start] to line up with the rows' text. */
@Composable
fun KitDivider(modifier: Modifier = Modifier, start: Dp = 56.dp) {
    val palette = LocalCosmoColors.current.palette
    Box(modifier.fillMaxWidth().padding(start = start).heightIn(min = 1.dp, max = 1.dp).background(Color(palette.textPrimary).copy(alpha = 0.08f)))
}

/**
 * A settings row (DESIGN_SYSTEM.md 4.10, with the owner's squircle tiles): a small squircle with its [icon] in a [tile] tint of the
 * theme (or the caller's [tint]; with neither, a quiet `surface3` tile and a secondary glyph), the [title] over an optional
 * [summary] (one line with [singleLineSummary], the current value), and [trailing] (a switch, a value, a chevron by default when
 * [onClick] is given). [selected] marks the chosen row of a two-pane settings page (`surface3`, and selected for screen readers).
 */
@Composable
fun KitSettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tint: Color? = null,
    summary: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    selected: Boolean = false,
    singleLineSummary: Boolean = false,
    tile: KitTileTint? = null,
) {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val type = LocalCosmoType.current
    // The squircle's colour: the caller's, a tile tint of the theme, or, with neither, the quiet surface3 with a secondary glyph.
    val tileFill = tint ?: tile?.fill(colors.dark)?.let { Color(it) }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = if (summary.isNullOrBlank()) 56.dp else 72.dp)
            .then(if (selected) Modifier.background(Color(palette.surfaceVariant)) else Modifier)
            .then(if (onClick != null) Modifier.combinedClickable(role = Role.Button, onClick = onClick) else Modifier)
            .then(if (selected) Modifier.semantics { this.selected = true } else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(CosmoRadii.rSm)).background(tileFill ?: Color(palette.surfaceVariant)), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = if (tileFill != null) Color.White else Color(palette.textSecondary), modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = type.rowTitle, color = Color(palette.textPrimary), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!summary.isNullOrBlank()) {
                Text(summary, style = type.meta, color = Color(palette.textSecondary), maxLines = if (singleLineSummary) 1 else 3, overflow = TextOverflow.Ellipsis)
            }
        }
        when {
            trailing != null -> { Spacer(Modifier.width(8.dp)); trailing() }
            onClick != null -> Icon(CosmoIcons.ChevronRight, contentDescription = null, tint = Color(palette.textTertiary), modifier = Modifier.size(20.dp))
        }
    }
}

private val GROUP_SHAPE = RoundedCornerShape(CosmoRadii.rMd)

/**
 * The small palette of settings category tiles (the owner's Prism-style squircles): muted, one step darker in the light theme so
 * the white glyph keeps 4.5:1 on every tile.
 */
enum class KitTileTint(private val dark: Long, private val light: Long) {
    BLUE(0xFF3D6FD8, 0xFF2F5FBF),
    GREEN(0xFF2E8B57, 0xFF1E7B34),
    ORANGE(0xFFC2611F, 0xFFA24B17),
    PURPLE(0xFF7A55C7, 0xFF6440B0),
    RED(0xFFC94040, 0xFFB03030),
    TEAL(0xFF1F8A8A, 0xFF16706F),
    PINK(0xFFC2457E, 0xFFA8326A),
    GREY(0xFF5F5F68, 0xFF55555D);

    /** The tile's colour as ARGB in the dark or the light theme. */
    fun fill(dark: Boolean): Int = (if (dark) this.dark else light).toInt()
}
