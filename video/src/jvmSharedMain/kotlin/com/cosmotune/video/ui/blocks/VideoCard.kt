package com.cosmotune.video.ui.blocks

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.music.ui.components.contextClick
import com.cosmotune.ui.kit.KitVideoCard
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_more
import org.jetbrains.compose.resources.stringResource


/**
 * The feed card (DESIGN_SYSTEM.md 4.7, 6.2): the kit's [KitVideoCard], the thumbnail across the page's width less its margins, 16:9, 12 dp
 * corners, then a 36 dp channel avatar, two lines of title, one line of meta ("Channel · 1.2M views · 3 days ago") and the more button.
 *
 * It is a full-width block with its own 16 dp side margin, so a list needs no padding of its own. A tap calls [onClick]; the more
 * button, a hold and a right click call [onMore] (the video sheet). [watchedFraction] draws the watched bar, [title] stands in for the
 * item's (a DeArrow title), [downloaded] marks a downloaded video, [onAvatarClick] opens the channel.
 *
 * On a wide page the grid ([videoGridItems]) draws the same card without the margin.
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
    onAvatarClick: (() -> Unit)? = null,
) {
    VideoCardBody(item, onClick, onMore, modifier, watchedFraction, title, downloaded, margin = CosmoSpacing.margin, onAvatarClick = onAvatarClick)
}

/** The one card anatomy: the phone's [VideoCard] stands it in the page's margin, the wide grid in a cell. */
@Composable
internal fun VideoCardBody(
    item: VideoItem,
    onClick: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier,
    watchedFraction: Float?,
    title: String,
    downloaded: Boolean,
    margin: Dp = 0.dp,
    onAvatarClick: (() -> Unit)? = null,
) {
    val meta = MetaLine(item, rememberNowMs())
    val more = stringResource(Res.string.video_more)
    val hover = rememberCardHover()
    KitVideoCard(
        title = title,
        meta = meta,
        thumbnailUrl = item.thumbnailUrl,
        onClick = onClick,
        modifier = with(hover) { modifier.track() }.contextClick(onMore),
        onLongClick = onMore,
        onMore = onMore,
        moreLabel = more,
        onAvatarClick = onAvatarClick,
        margin = margin,
        avatar = { ChannelAvatar(item.channelAvatarUrl, VideoMetrics.FEED_AVATAR_DP.dp, name = item.channelName) },
        thumbnail = {
            ThumbnailFrame(
                url = item.thumbnailUrl,
                modifier = with(hover) { Modifier.fillMaxSize().lifted() },
                shape = ThumbnailShape,
                durationSec = item.durationSec,
                live = item.isLive,
                upcoming = item.isUpcoming,
                watchedFraction = watchedFraction,
                downloaded = downloaded,
                hovered = hover.hovered,
            )
        },
    )
}
