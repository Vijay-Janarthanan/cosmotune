package com.cosmotune.video.ui.blocks

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.cosmotune.video.design.VideoText
import com.cosmotune.video.model.PlaylistItem
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_count_videos
import com.cosmotune.video.resources.video_meta_join
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * A playlist in a list (spec 5.3): the Orbit row of a [VideoRow] (its picture with nothing on it, the title over the meta line), and
 * the owner and the count as the meta line ("Owner · 30 videos"; either alone when the other is unknown). No more button: a playlist has no sheet.
 * [desktop] gives it the desktop's thumbnail (168 x 94.5), as [VideoRow] has, for a wide page where video rows stand beside it.
 */
@Composable
fun PlaylistRow(item: PlaylistItem, onClick: () -> Unit, modifier: Modifier = Modifier, desktop: Boolean = false) {
    val count = item.videoCount
    val owner = item.ownerName?.takeIf { it.isNotBlank() }
    val countText = count?.let {
        val quantity = it.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        pluralStringResource(Res.plurals.video_count_videos, quantity, quantity)
    }
    val meta = when {
        owner != null && countText != null -> stringResource(Res.string.video_meta_join, owner, countText)
        owner != null -> owner
        else -> countText.orEmpty()
    }
    VideoRowFrame(
        thumbnail = {
            ThumbnailFrame(url = item.thumbnailUrl, modifier = Modifier.rowThumbnailSize(desktop), shape = RowPictureShape)
        },
        title = item.title,
        meta = meta,
        onClick = onClick,
        onMore = null,
        modifier = modifier,
        trailing = null,
    )
}
