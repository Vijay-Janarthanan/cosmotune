package com.cosmotune.video.ui.blocks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIconButtonStyle
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.SuperellipseShape
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_more
import org.jetbrains.compose.resources.stringResource

/**
 * The feed card of the phone (spec 5.0): the thumbnail across the content width (328 x 184.5 at 360 dp, radius 22), 10 dp under it
 * a 36 dp channel avatar, 12 dp, the title (15.5 sp, weight 650, two lines) over the meta line (13 sp, `textSecondary`, one line:
 * "Channel · 1.2M views · 3 days ago") and a 40 dp plain "more" button; 20 dp of room after the card.
 *
 * Like part two's rows it is a full-width block with its own 16 dp side margin, so a list needs no padding of its own. A tap calls
 * [onClick]; the more button, a hold and a right click call [onMore] (the video sheet). [watchedFraction] draws the watched bar,
 * [title] stands in for the item's (a DeArrow title), [downloaded] marks a downloaded video. One button for assistive technology
 * that reads the title, the meta line (channel, views, age) and the duration; the more button stays its own.
 *
 * On a wide page the grid ([videoGridItems]) draws the same card without the margin and the room after it.
 */
@Composable
fun VideoCard(
    item: VideoItem,
    onClick: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    watchedFraction: Float? = null,
    title: String = item.title,
    downloaded: Boolean = false,
) {
    Column(modifier.fillMaxWidth()) {
        VideoCardBody(item, onClick, onMore, Modifier, watchedFraction, title, downloaded, bleed = true)
        Spacer(Modifier.height(VideoMetrics.FEED_AFTER_DP.dp))
    }
}

/** The corner of a grid card's picture on a wide page: 12 dp. */
private val GRID_THUMB_SHAPE = SuperellipseShape(12.dp)

/** The card without its margin and the room after it: the phone's [VideoCard] stands it in a margin, the wide grid in a cell. */
@Composable
internal fun VideoCardBody(
    item: VideoItem,
    onClick: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier,
    watchedFraction: Float?,
    title: String,
    downloaded: Boolean,
    bleed: Boolean = false,
) {
    val palette = LocalCosmoColors.current.palette
    val meta = MetaLine(item, rememberNowMs())
    val titleStyle = rememberTitleStyle(VideoMetrics.FEED_TITLE_SP, title)
    val metaStyle = rememberMetaStyle(VideoMetrics.FEED_META_SP, meta)
    val more = stringResource(Res.string.video_more)
    val hover = rememberCardHover()
    Column(with(hover) { modifier.fillMaxWidth().track() }.videoClickable(CosmoRadii.lg, onClick, onMore)) {
        ThumbnailFrame(
            url = item.thumbnailUrl,
            modifier = with(hover) { Modifier.fillMaxWidth().aspectRatio(16f / 9f).let { if (bleed) it else it.lifted() } },
            shape = if (bleed) RectangleShape else GRID_THUMB_SHAPE,
            durationSec = item.durationSec,
            live = item.isLive,
            upcoming = item.isUpcoming,
            watchedFraction = watchedFraction,
            downloaded = downloaded,
            hovered = hover.hovered,
        )
        Row(
            Modifier.fillMaxWidth().padding(top = VideoMetrics.FEED_META_TOP_DP.dp).then(if (bleed) Modifier.padding(horizontal = CosmoSpacing.margin) else Modifier),
            verticalAlignment = Alignment.Top,
        ) {
            ChannelAvatar(item.channelAvatarUrl, VideoMetrics.FEED_AVATAR_DP.dp, name = item.channelName)
            Spacer(Modifier.width(VideoMetrics.FEED_GAP_DP.dp))
            Column(Modifier.weight(1f)) {
                Text(text = title, style = titleStyle, color = Color(palette.textPrimary), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (meta.isNotEmpty()) {
                    Text(
                        text = meta,
                        style = metaStyle,
                        color = Color(palette.textSecondary),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            CosmoIconButton(
                icon = CosmoIcons.MoreVertical,
                contentDescription = more,
                onClick = onMore,
                style = CosmoIconButtonStyle.PLAIN,
                size = VideoMetrics.MORE_BUTTON_DP.dp,
            )
        }
    }
}
