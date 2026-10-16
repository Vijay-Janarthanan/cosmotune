package com.cosmotune.video.ui.shorts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.design.VideoText
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_more
import com.cosmotune.video.resources.video_views
import com.cosmotune.video.resources.video_views_none
import com.cosmotune.video.ui.blocks.ThumbnailFrame
import com.cosmotune.video.ui.blocks.ThumbnailShape
import com.cosmotune.video.ui.blocks.rememberMetaStyle
import com.cosmotune.video.ui.blocks.rememberTitleStyle
import com.cosmotune.video.ui.blocks.videoClickable
import com.cosmotune.video.ui.branding.rememberVideoThumbnail
import com.cosmotune.video.ui.branding.rememberVideoTitle
import org.jetbrains.compose.resources.stringResource

/*
 * The Shorts card (spec 5.0): the vertical twin of the shelf card. White text and a black scrim over the picture are the
 * documented exception to "colours come from the palette" (media).
 */

/** Inside the card, the text stands this far from the sides and the bottom. */
private val TEXT_INSET = 8.dp

/** The views line, under the title. */
private val VIEWS_GAP = 2.dp

/** The black of the scrim at the card's bottom edge (spec 5.0: from 0 to 60 %). */
private val SCRIM_BOTTOM = Color.Black.copy(alpha = VideoMetrics.SHORTS_SCRIM_ALPHA)

/** The views are white a little softer than the title. */
private const val VIEWS_ALPHA = 0.88f

/**
 * A Shorts card (spec 5.0): a 9:16 picture [width] wide ([VideoMetrics.shortsCardHeightDp]: 132 x 235 on a shelf, 106 x 188 in the
 * phone's grid), its corner by the cover rule keyed on the shorter side, the width (18 at 132 dp, 16 at 106). The picture is the
 * item's (DeArrow's when on, `rememberVideoThumbnail`); over its lower 40 % a black scrim from 0 to 60 %, drawn once per size in
 * `drawWithCache`; on it, at the bottom, the title (`rememberVideoTitle`, 13.5 sp weight 650, two lines) and the views (12 sp), in
 * white. A tap calls [onClick]; a hold, a right click and the screen reader's "More options" call [onMore] (the video sheet).
 * [downloaded] puts the downloaded badge 6 dp inside the top-right corner.
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
    val title = rememberVideoTitle(item)
    val picture = rememberVideoThumbnail(item)
    val views = viewsText(item)
    val titleStyle = rememberTitleStyle(VideoMetrics.SHORTS_TITLE_SP, title)
    val viewsStyle = rememberMetaStyle(VideoMetrics.SHORTS_VIEWS_SP, views.orEmpty())
    val more = stringResource(Res.string.video_more)
    val radius = VideoMetrics.thumbRadiusDp(width.value).dp
    Box(
        modifier
            .size(width, VideoMetrics.shortsCardHeightDp(width.value).dp)
            .semantics { customActions = listOf(CustomAccessibilityAction(more) { onMore(); true }) }
            .videoClickable(radius, onClick, onMore),
    ) {
        ThumbnailFrame(url = picture, modifier = Modifier.fillMaxSize(), shape = ThumbnailShape, downloaded = downloaded)
        Box(Modifier.fillMaxSize().clip(ThumbnailShape).shortsCardScrim())
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(TEXT_INSET)) {
            Text(text = title, style = titleStyle, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (views != null) {
                Text(
                    text = views,
                    style = viewsStyle,
                    color = Color.White.copy(alpha = VIEWS_ALPHA),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = VIEWS_GAP),
                )
            }
        }
    }
}

/** The views of a Short ("1.2M views", "No views"), or null when the source gave none. */
@Composable
internal fun viewsText(item: VideoItem): String? {
    val count = item.viewCount ?: return null
    return if (count <= 0L) stringResource(Res.string.video_views_none) else stringResource(Res.string.video_views, VideoText.compact(count))
}

/** The card's scrim (spec 5.0): black from 0 to 60 % over the lower 40 %, its brush made once per size. */
private fun Modifier.shortsCardScrim(): Modifier = drawWithCache {
    val top = size.height * (1f - VideoMetrics.SHORTS_SCRIM_FRACTION)
    val brush = Brush.verticalGradient(0f to Color.Transparent, 1f to SCRIM_BOTTOM, startY = top, endY = size.height)
    val from = Offset(0f, top)
    onDrawBehind { drawRect(brush, topLeft = from) }
}
