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
import com.cosmotune.ui.orbit.OrbitRoundButton
import com.cosmotune.ui.orbit.OrbitSongRow
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.settings.CosmoSettingsRow
import com.cosmotune.ui.settings.LocalSettingsTileColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.sp

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
 * A media row in Orbit: the one Orbit row ([OrbitSongRow]), radius 22 on the ink at 5 %, inset by the page's margin and 3 dp from its
 * neighbours. In front: [number] in the narrow face, or the art ([KitArt.SQUARE] and [KitArt.ROUND] as a disc, [KitArt.WIDE] a rounded
 * 16:9 picture, [KitArt.POSTER] a rounded poster; [artHeight] replaces its usual size). Then [title] over [subtitle] (artist,
 * channel, author), led by the [quality] pill, then [trailingText] (a duration, a count) and a round more button when [onMore] is
 * given. The [current] row has the peach wash, a peach title and the orbit round its disc. Works for songs, episodes, videos,
 * chapters and books.
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
    val fonts = orbitFonts()
    val look = orbitLook()
    val showsArt = number == null && (artUrl != null || art != KitArt.SQUARE)
    val picture = showsArt && (art == KitArt.WIDE || art == KitArt.POSTER)
    val disc = when {
        number != null -> 36.dp
        !showsArt -> 0.dp
        art == KitArt.WIDE -> 56.dp
        picture -> 44.dp
        else -> artHeight ?: 44.dp
    }
    OrbitSongRow(
        title = title,
        subtitle = subtitle.orEmpty(),
        cover = artUrl,
        fonts = fonts,
        look = look,
        onClick = onClick,
        modifier = modifier.fillMaxWidth().padding(horizontal = CosmoSpacing.margin, vertical = 3.dp),
        now = current,
        onLongClick = onLongClick,
        endPadding = if (onMore != null) 6.dp else 16.dp,
        discSize = disc,
        gap = if (showsArt || number != null) 12.dp else 6.dp,
        subtitleLeading = quality?.let { badge -> { QualityPill(badge, compact = true) } },
        discContent = when {
            number != null -> {
                {
                    Text(
                        number.toString(), style = fonts.number.copy(fontSize = 26.sp, lineHeight = 26.sp),
                        color = if (current) look.accent else look.ink.copy(alpha = 0.4f), maxLines = 1,
                    )
                }
            }
            !showsArt -> { {} }
            art == KitArt.WIDE -> { { KitArtwork(artUrl, art, artHeight ?: 36.dp, radius = 10.dp) } }
            art == KitArt.POSTER -> { { KitArtwork(artUrl, art, artHeight ?: 52.dp, radius = 8.dp) } }
            else -> null
        },
        trailingText = trailingText,
        trailing = if (onMore != null) {
            { OrbitRoundButton(CosmoIcons.MoreVertical, moreLabel, look.ink, onMore, Modifier.padding(start = 6.dp), size = 36.dp) }
        } else null,
    )
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
 * A settings row in Orbit: the settings pages' own row ([CosmoSettingsRow]: the lead tile with its [icon], the [title] over an optional
 * [summary], and [trailing] or a chevron when [onClick] is given), on a round 22 dp ground. The lead tile is the [tile] tint of the
 * theme or the caller's [tint] with a white glyph, or the tonal tile with neither. [singleLineSummary] keeps the summary (the current
 * value) to one line. [selected] marks the chosen row of a two-pane page (the ink at 8 % under it, and selected for screen readers).
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
    val look = orbitLook()
    val fonts = orbitFonts()
    // The tile's colour: the caller's, a tile tint of the theme, or, with neither, the settings pages' tonal tile.
    val tileFill = tint ?: tile?.fill(colors.dark)?.let { Color(it) }
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .then(if (selected) Modifier.background(look.chip).semantics { this.selected = true } else Modifier),
    ) {
        CompositionLocalProvider(LocalSettingsTileColor provides tileFill) {
            CosmoSettingsRow(
                icon = icon,
                title = title,
                subtitle = summary.takeUnless { singleLineSummary }?.takeIf { it.isNotBlank() },
                subtitleContent = if (singleLineSummary && !summary.isNullOrBlank()) {
                    {
                        Text(
                            summary, style = fonts.body.copy(fontSize = 12.5.sp, lineHeight = 17.sp), color = look.dim, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                } else null,
                onClick = onClick,
                trailing = trailing,
            )
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
