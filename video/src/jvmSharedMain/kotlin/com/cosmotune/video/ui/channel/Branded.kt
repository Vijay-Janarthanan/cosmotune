package com.cosmotune.video.ui.channel

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.ui.blocks.VideoCard
import com.cosmotune.video.ui.blocks.VideoCardBody
import com.cosmotune.video.ui.blocks.VideoRow
import com.cosmotune.video.ui.branding.rememberChannelAvatar
import com.cosmotune.video.ui.branding.rememberVideoThumbnail
import com.cosmotune.video.ui.branding.rememberVideoTitle

/*
 * The cards and rows of the channel and playlist pages with the title and the picture DeArrow gives them (spec 7.2). The lookups are
 * `rememberVideoTitle` and `rememberVideoThumbnail`, which batch and limit them; a screen never calls the DeArrow client itself.
 */

/** [item] as lists draw it: its own picture, or DeArrow's when that is on and loads. */
@Immutable
internal class Branded(val item: VideoItem, val title: String)

/** The title and the picture to show for [item] now. */
@Composable
internal fun rememberBranded(item: VideoItem): Branded {
    val title = rememberVideoTitle(item)
    val picture = rememberVideoThumbnail(item)
    val avatar = rememberChannelAvatar(item)
    return remember(item, title, picture, avatar) {
        Branded(if (picture == item.thumbnailUrl && avatar == item.channelAvatarUrl) item else item.copy(thumbnailUrl = picture, channelAvatarUrl = avatar), title)
    }
}

/** The phone's feed card with its margin and the room after it. */
@Composable
internal fun BrandedVideoCard(item: VideoItem, downloaded: Boolean, onClick: () -> Unit, onMore: () -> Unit, modifier: Modifier = Modifier) {
    val branded = rememberBranded(item)
    VideoCard(item = branded.item, onClick = onClick, onMore = onMore, modifier = modifier, title = branded.title, downloaded = downloaded)
}

/** The wide page's card in its grid cell. */
@Composable
internal fun BrandedVideoCell(item: VideoItem, downloaded: Boolean, onClick: () -> Unit, onMore: () -> Unit, modifier: Modifier = Modifier) {
    val branded = rememberBranded(item)
    VideoCardBody(
        item = branded.item,
        onClick = onClick,
        onMore = onMore,
        modifier = modifier,
        watchedFraction = null,
        title = branded.title,
        downloaded = downloaded,
    )
}

/** A video row. */
@Composable
internal fun BrandedVideoRow(
    item: VideoItem,
    downloaded: Boolean,
    onClick: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    desktop: Boolean = false,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val branded = rememberBranded(item)
    VideoRow(
        item = branded.item,
        onClick = onClick,
        onMore = onMore,
        modifier = modifier,
        desktop = desktop,
        trailing = trailing,
        title = branded.title,
        downloaded = downloaded,
    )
}
