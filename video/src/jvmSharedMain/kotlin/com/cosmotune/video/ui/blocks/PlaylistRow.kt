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
 * A playlist in a list (spec 5.3): the shape of a [VideoRow] (160 x 90, padding 8 16, the title over the meta line), its thumbnail
 * carrying the count strip (the right 40 % in black at 60 % with the video count over `CosmoIcons.List`), and the owner and the
 * count as the meta line ("Owner · 30 videos"; either alone when the other is unknown). No more button: a playlist has no sheet.
 */
@Composable
fun PlaylistRow(item: PlaylistItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
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
            ThumbnailFrame(
                url = item.thumbnailUrl,
                modifier = Modifier.rowThumbnailSize(desktop = false),
                shape = ThumbnailShape,
                countStrip = count?.let { VideoText.compact(it) },
            )
        },
        title = item.title,
        meta = meta,
        onClick = onClick,
        onMore = null,
        modifier = modifier,
        trailing = null,
    )
}
