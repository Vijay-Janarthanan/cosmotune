package com.cosmotune.video.ui.blocks

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.model.VideoItem

/**
 * The card of a shelf (Orbit board B): [VideoCardBody] at [width] (280 dp), the picture with the play moon off its corner and the
 * title and meta line under it, the channel in the meta rather than as a disc. A tap calls [onClick]; a hold, a right click and the
 * screen reader's "More options" action call [onMore]. [watchedFraction] draws the watched line of "Continue watching"; [downloaded]
 * says so in the meta line.
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
    VideoCardBody(item, onClick, onMore, modifier, watchedFraction = watchedFraction, title = item.title, downloaded = downloaded, width = width, avatar = false)
}
