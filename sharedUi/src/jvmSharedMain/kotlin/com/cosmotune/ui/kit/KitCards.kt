package com.cosmotune.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIconButtonStyle
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType

/**
 * A card of a shelf or a grid (DESIGN_SYSTEM.md 4.7): the art in [art]'s shape ([KitArt.SQUARE] albums and playlists, [KitArt.ROUND]
 * artists, [KitArt.WIDE] videos and episodes, [KitArt.POSTER] books and manga), then the title on up to two lines (one, centred, under
 * a round avatar) and one line of [subtitle], no container around them. [width] fixes it for a shelf; null fills the cell of a grid.
 * [badge] is drawn over the art's bottom end (a duration, a progress, a count); [progress] (0..1) is the 3 dp accent line along the
 * art's bottom edge; [current] draws the title in the accent; [overlay] stands over the art (the playing card's equaliser).
 */
@Composable
fun KitCard(
    title: String,
    subtitle: String?,
    artUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    art: KitArt = KitArt.SQUARE,
    width: Dp? = 148.dp,
    badge: String? = null,
    onLongClick: (() -> Unit)? = null,
    progress: Float? = null,
    current: Boolean = false,
    overlay: (@Composable () -> Unit)? = null,
) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val round = art == KitArt.ROUND
    val shape = when (art) {
        KitArt.ROUND -> CircleShape
        KitArt.POSTER -> RoundedCornerShape(CosmoRadii.rSm)
        else -> RoundedCornerShape(CosmoRadii.rMd)
    }
    Column(
        (if (width != null) modifier.width(width) else modifier.fillMaxWidth())
            .combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongClick),
        horizontalAlignment = if (round) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(art.ratio).clip(shape).background(Color(palette.surfaceVariant))) {
            if (artUrl != null) AsyncImage(model = artUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            if (badge != null) MediaBadge(badge, Modifier.align(Alignment.BottomEnd).padding(6.dp))
            if (progress != null) KitProgressLine(progress, Modifier.align(Alignment.BottomStart))
            overlay?.invoke()
        }
        Spacer(Modifier.height(8.dp))
        Text(
            title,
            style = type.forText(type.rowTitle, title),
            color = Color(if (current) palette.accentStrong else palette.textPrimary),
            maxLines = if (round) 1 else 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (round) TextAlign.Center else TextAlign.Start,
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                subtitle,
                style = type.forText(type.meta, subtitle),
                color = Color(palette.textSecondary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (round) TextAlign.Center else TextAlign.Start,
            )
        }
    }
}

/** A duration or a count on a picture: white `caption` on black at 72 %, 4 dp corners. */
@Composable
fun MediaBadge(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = LocalCosmoType.current.caption,
        color = Color.White,
        maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(CosmoRadii.rXs)).background(Color.Black.copy(alpha = 0.72f)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** How far through something is: a 3 dp accent line along the bottom edge of its picture (4.7), on a dark track. */
@Composable
fun KitProgressLine(fraction: Float, modifier: Modifier = Modifier) {
    val palette = LocalCosmoColors.current.palette
    Box(modifier.fillMaxWidth().height(3.dp).background(Color.Black.copy(alpha = 0.4f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(Color(palette.accent)))
    }
}

/**
 * A full-width video card (DESIGN_SYSTEM.md 4.7, 6.2): the 16:9 thumbnail, the page's width less its margins, 12 dp corners, then the
 * channel's avatar beside two lines of title and one line of metadata ("Channel · 5.6M views · 4 years ago"), and the more button
 * when [onMore] is given. [progress] is the resume line along the thumbnail's bottom edge. [thumbnail] replaces the plain picture
 * (Video's own box, with its hover preview and its branding); [onAvatarClick] opens the channel. [avatar] replaces the plain avatar
 * (a mode's own, with its fallback). [margin] is the card's side margin: the page's by default, 0 in a grid's cell.
 */
@Composable
fun KitVideoCard(
    title: String,
    meta: String,
    thumbnailUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    avatarUrl: String? = null,
    duration: String? = null,
    onLongClick: (() -> Unit)? = null,
    progress: Float? = null,
    onMore: (() -> Unit)? = null,
    moreLabel: String = "More",
    onAvatarClick: (() -> Unit)? = null,
    thumbnail: (@Composable () -> Unit)? = null,
    avatar: (@Composable () -> Unit)? = null,
    margin: Dp = CosmoSpacing.margin,
) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    Column(modifier.fillMaxWidth().combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongClick).padding(horizontal = margin)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(CosmoRadii.rMd)).background(Color(palette.surfaceVariant))) {
            if (thumbnail != null) {
                thumbnail()
            } else {
                if (thumbnailUrl != null) AsyncImage(model = thumbnailUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                if (duration != null) MediaBadge(duration, Modifier.align(Alignment.BottomEnd).padding(8.dp))
                if (progress != null) KitProgressLine(progress, Modifier.align(Alignment.BottomStart))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp)) {
            val avatarTap = if (onAvatarClick != null) Modifier.clip(CircleShape).combinedClickable(role = Role.Button, onClick = onAvatarClick) else Modifier
            if (avatar != null) Box(avatarTap) { avatar() } else KitArtwork(avatarUrl, KitArt.ROUND, 36.dp, modifier = avatarTap)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = type.forText(type.rowTitle, title), color = Color(palette.textPrimary), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(meta, style = type.forText(type.meta, meta), color = Color(palette.textSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (onMore != null) CosmoIconButton(CosmoIcons.MoreVertical, moreLabel, onMore, style = CosmoIconButtonStyle.PLAIN, size = 40.dp)
        }
    }
}

/** A horizontal shelf of [KitCard]s under a [KitSectionHeader]: the page's margin at both ends, 12 dp between the cards. */
@Composable
fun KitShelf(
    title: String,
    modifier: Modifier = Modifier,
    overline: String? = null,
    onSeeAll: (() -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        KitSectionHeader(title, overline = overline, onSeeAll = onSeeAll)
        LazyRow(
            contentPadding = PaddingValues(horizontal = CosmoSpacing.margin),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/**
 * A quick-access tile (DESIGN_SYSTEM.md 4.9, the home's two-column grid): 56 dp, the art flush with its start edge ([icon] on
 * `surface3` when there is no art), the title beside it, on `surface1`.
 */
@Composable
fun KitQuickTile(title: String, artUrl: String?, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, onLongClick: (() -> Unit)? = null) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    Row(
        modifier.height(56.dp).clip(RoundedCornerShape(CosmoRadii.rMd)).background(Color(palette.surface)).combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (artUrl == null && icon != null) {
            Box(Modifier.size(56.dp).background(Color(palette.surfaceVariant)), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = Color(palette.textSecondary), modifier = Modifier.size(24.dp))
            }
        } else {
            KitArtwork(artUrl, KitArt.SQUARE, 56.dp, radius = CosmoRadii.r0)
        }
        Spacer(Modifier.width(12.dp))
        Text(title, style = type.forText(type.rowTitle, title), color = Color(palette.textPrimary), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 8.dp))
    }
}
