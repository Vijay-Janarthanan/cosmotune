package com.cosmotune.video.ui.shorts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.ui.orbit.DiscPlayMoon
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.design.VideoText
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_dl_done_cd
import com.cosmotune.video.resources.video_more
import com.cosmotune.video.resources.video_views
import com.cosmotune.video.resources.video_views_none
import com.cosmotune.video.ui.blocks.ThumbnailFrame
import com.cosmotune.video.ui.blocks.videoClickable
import com.cosmotune.video.ui.branding.rememberVideoThumbnail
import com.cosmotune.video.ui.branding.rememberVideoTitle
import org.jetbrains.compose.resources.stringResource

/*
 * The Shorts card (Orbit board B: "Shorts as tall cards"): the vertical twin of the shelf card, the picture with nothing on it and
 * the play moon off its corner, the words under it.
 */

/**
 * A Shorts card: a 9:16 picture [width] wide less the moon's room ([VideoMetrics.shortsCardHeightDp] tall for its width), radius 20,
 * the play moon hanging off its bottom-end corner; under it the title (`rememberVideoTitle`, two lines) and the views, and
 * "Downloaded" for a [downloaded] Short. The picture is the item's (DeArrow's when on, `rememberVideoThumbnail`). A tap (or the moon)
 * calls [onClick]; a hold, a right click and the screen reader's "More options" call [onMore] (the video sheet).
 */
@Composable
fun ShortsCard(
    item: VideoItem,
    width: Dp,
    onClick: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    downloaded: Boolean = false,
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val title = rememberVideoTitle(item)
    val picture = rememberVideoThumbnail(item)
    val views = viewsText(item)
    val done = if (downloaded) stringResource(Res.string.video_dl_done_cd) else null
    val line = listOfNotNull(views, done).joinToString(" · ")
    val more = stringResource(Res.string.video_more)
    val pictureWidth = width - MOON_ROOM
    Column(
        modifier
            .width(width)
            .semantics { customActions = listOf(CustomAccessibilityAction(more) { onMore(); true }) }
            .videoClickable(20.dp, onClick, onMore),
    ) {
        Box(Modifier.padding(end = MOON_ROOM, bottom = MOON_ROOM)) {
            ThumbnailFrame(url = picture, modifier = Modifier.size(pictureWidth, VideoMetrics.shortsCardHeightDp(pictureWidth.value).dp), shape = SHAPE)
            Box(Modifier.align(Alignment.BottomEnd).offset(x = MOON_ROOM, y = MOON_ROOM).size(38.dp)) { DiscPlayMoon(look, onClick, size = 38.dp) }
        }
        Text(text = title, style = fonts.title.copy(fontSize = 13.5.sp, lineHeight = 17.sp), color = look.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 6.dp))
        if (line.isNotEmpty()) Text(text = line, style = fonts.body.copy(fontSize = 12.sp), color = look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** How far the moon hangs past the picture's corner. */
private val MOON_ROOM = 10.dp
private val SHAPE = RoundedCornerShape(20.dp)

/** The views of a Short ("1.2M views", "No views"), or null when the source gave none. */
@Composable
internal fun viewsText(item: VideoItem): String? {
    val count = item.viewCount ?: return null
    return if (count <= 0L) stringResource(Res.string.video_views_none) else stringResource(Res.string.video_views, VideoText.compact(count))
}

