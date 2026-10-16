package com.cosmotune.video.ui.blocks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_more
import org.jetbrains.compose.resources.stringResource

/**
 * The card of a shelf (spec 5.0): 240 x 135 by default (two and a half cards show at 411 dp), radius 18 by the cover rule, the title
 * under it in 14 sp, weight 650, up to two lines, and the meta line in 12.5 sp `textSecondary`. A tap calls [onClick]; a hold, a right
 * click and the screen reader's "More options" action call [onMore] (there is no visible button on a shelf card). [width] sets the
 * card (the wide pages use 280); [watchedFraction] draws the watched bar of "Continue watching"; [downloaded] marks a downloaded
 * video.
 */
@Composable
fun VideoShelfCard(
    item: VideoItem,
    onClick: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    watchedFraction: Float? = null,
    width: Dp = 240.dp,
    downloaded: Boolean = false,
) {
    val palette = LocalCosmoColors.current.palette
    val meta = MetaLine(item, rememberNowMs())
    val titleStyle = rememberTitleStyle(VideoMetrics.SHELF_TITLE_SP, item.title)
    val metaStyle = rememberMetaStyle(VideoMetrics.SHELF_META_SP, meta)
    val more = stringResource(Res.string.video_more)
    Column(
        modifier
            .width(width)
            .semantics { customActions = listOf(CustomAccessibilityAction(more) { onMore(); true }) }
            .videoClickable(CosmoRadii.md, onClick, onMore),
    ) {
        ThumbnailFrame(
            url = item.thumbnailUrl,
            modifier = Modifier.size(width, VideoMetrics.thumbHeightDp(width.value).dp),
            shape = ThumbnailShape,
            durationSec = item.durationSec,
            live = item.isLive,
            upcoming = item.isUpcoming,
            watchedFraction = watchedFraction,
            downloaded = downloaded,
        )
        Spacer(Modifier.height(SHELF_TEXT_GAP))
        Text(text = item.title, style = titleStyle, color = Color(palette.textPrimary), maxLines = 2, overflow = TextOverflow.Ellipsis)
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
}

/** Between a shelf card's picture and its title (part two's cards: 8 dp). */
private val SHELF_TEXT_GAP = 8.dp
