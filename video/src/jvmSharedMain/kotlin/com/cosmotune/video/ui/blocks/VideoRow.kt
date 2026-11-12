package com.cosmotune.video.ui.blocks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitRoundButton
import com.cosmotune.ui.orbit.WatchProgressLine
import com.cosmotune.ui.orbit.liveRed
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_more
import org.jetbrains.compose.resources.stringResource

/**
 * A video in a list (Orbit rule 8, the row): radius 22, the page's ink at 5 %, the picture as a small 16:9 rectangle (128 dp, the
 * desktop's 168) with nothing on it, the title up to two lines over the meta line ("Channel · 12:04 · 2 days ago"), and the round
 * "more" button at the end. [trailing] replaces the more button with what a page shows at the end of its rows (a download's progress);
 * a hold and a right click still call [onMore]. [watchedFraction] draws the watched line under the picture, [title] stands in for the
 * item's (a DeArrow title), [downloaded] says so in the meta line. One button for assistive technology.
 */
@Composable
fun VideoRow(
    item: VideoItem,
    onClick: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    watchedFraction: Float? = null,
    desktop: Boolean = false,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    title: String = item.title,
    downloaded: Boolean = false,
) {
    val meta = CardMetaLine(item, rememberNowMs(), downloaded = downloaded)
    val more = stringResource(Res.string.video_more)
    val hover = rememberCardHover()
    PreResolveOnHover(hover, item)
    VideoRowFrame(
        thumbnail = {
            Column {
                ThumbnailFrame(item.thumbnailUrl, with(hover) { Modifier.rowThumbnailSize(desktop).lifted() }, RowPictureShape, badge = badgeText(item), badgeLive = item.isLive)
                if (watchedFraction != null) WatchProgressLine(watchedFraction, orbitLook(), Modifier.width(rowThumbnailWidth(desktop)).padding(top = 6.dp))
            }
        },
        title = title,
        meta = meta,
        live = item.isLive,
        onClick = onClick,
        onMore = onMore,
        modifier = with(hover) { modifier.track() },
        trailing = trailing ?: {
            val look = orbitLook()
            OrbitRoundButton(CosmoIcons.MoreVertical, more, look.ink, onMore, size = 40.dp, modifier = Modifier.padding(start = 8.dp))
        },
    )
}

/** The width of a row's picture: 128 dp, on the desktop 168. */
internal fun rowThumbnailWidth(desktop: Boolean) = (if (desktop) VideoMetrics.DESKTOP_ROW_THUMB_W_DP else VideoMetrics.ROW_THUMB_W_DP).dp

/** The size of a row's picture: 128 x 72, on the desktop 168 x 94.5. */
internal fun Modifier.rowThumbnailSize(desktop: Boolean): Modifier {
    val width = if (desktop) VideoMetrics.DESKTOP_ROW_THUMB_W_DP else VideoMetrics.ROW_THUMB_W_DP
    return size(width.dp, VideoMetrics.thumbHeightDp(width).dp)
}

/**
 * The Orbit row of videos and of playlists: [thumbnail], 14 dp, the [title] over the [meta] (led by a red "● LIVE" when [live]) and
 * [trailing], on the row's 5 % fill with radius 22, standing in the page's 16 dp margin with 8 dp between rows. The row is one
 * button ([onClick]); a hold or a right click calls [onMore] when there is one.
 */
@Composable
internal fun VideoRowFrame(
    thumbnail: @Composable () -> Unit,
    title: String,
    meta: String,
    onClick: () -> Unit,
    onMore: (() -> Unit)?,
    modifier: Modifier,
    trailing: (@Composable RowScope.() -> Unit)?,
    live: Boolean = false,
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    Box(modifier.fillMaxWidth().padding(horizontal = CosmoSpacing.margin, vertical = ROW_GAP / 2)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(ROW_SHAPE)
                .background(look.row)
                .videoClickable(ROW_RADIUS, onClick, onMore)
                .padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            thumbnail()
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = fonts.title.copy(fontSize = 14.sp), color = look.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val line = buildAnnotatedString {
                    if (live) {
                        withStyle(SpanStyle(color = liveRed(look), fontFamily = fonts.label.fontFamily, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, fontSize = 10.5.sp)) { append("● LIVE") }
                        if (meta.isNotBlank()) append(" · ")
                    }
                    append(meta)
                }
                if (line.isNotEmpty()) {
                    Text(line, style = fonts.body.copy(fontSize = 12.sp), color = look.dim, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                }
            }
            if (trailing != null) trailing()
        }
    }
}

/** The row's corner and the room between two rows (Orbit rule 8). */
private val ROW_RADIUS = 22.dp
private val ROW_SHAPE = RoundedCornerShape(ROW_RADIUS)
private val ROW_GAP = 8.dp
