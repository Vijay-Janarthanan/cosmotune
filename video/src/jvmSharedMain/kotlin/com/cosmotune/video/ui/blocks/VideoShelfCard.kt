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
 * The card of a shelf (DESIGN_SYSTEM.md 4.7): 280 dp wide, 16:9, 12 dp corners, the title under it on up to two lines and one line of
 * meta. A tap calls [onClick]; a hold, a right click and the screen reader's "More options" action call [onMore] (there is no visible
 * button on a shelf card). [watchedFraction] draws the 3 dp watched bar of "Continue watching"; [downloaded] marks a downloaded video.
 */
@Composable
fun VideoShelfCard(
    item: VideoItem,
    onClick: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    watchedFraction: Float? = null,
    width: Dp = VideoMetrics.SHELF_CARD_W_DP.dp,
    downloaded: Boolean = false,
) {
    val palette = LocalCosmoColors.current.palette
    val meta = MetaLine(item, rememberNowMs())
    val titleStyle = rememberTitleStyle(item.title)
    val metaStyle = rememberMetaStyle(meta)
    val more = stringResource(Res.string.video_more)
    val hover = rememberCardHover()
    Column(
        with(hover) { modifier.track() }
            .width(width)
            .semantics { customActions = listOf(CustomAccessibilityAction(more) { onMore(); true }) }
            .videoClickable(CosmoRadii.rMd, onClick, onMore),
    ) {
        ThumbnailFrame(
            url = item.thumbnailUrl,
            modifier = with(hover) { Modifier.size(width, VideoMetrics.thumbHeightDp(width.value).dp).lifted() },
            shape = ThumbnailShape,
            durationSec = item.durationSec,
            live = item.isLive,
            upcoming = item.isUpcoming,
            watchedFraction = watchedFraction,
            downloaded = downloaded,
            hovered = hover.hovered,
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
