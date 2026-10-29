package com.cosmotune.video.ui.blocks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIconButtonStyle
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_more
import org.jetbrains.compose.resources.stringResource

/*
 * The big card of the wide pages: the result card of Search. Like every card it is one button (`videoClickable`: a tap, a hold, a
 * right click, the focus ring), answers a mouse the way the grid's cards do, and takes the numbers it shares with them from VideoMetrics.
 */

/** The result card: the picture 360 dp wide (16:9), 16 dp from the words. */
private val RESULT_PICTURE_W = 360.dp
private val RESULT_GAP = 16.dp
private val RESULT_AVATAR = 24.dp
private const val RESULT_TITLE_SP = 18f
private const val RESULT_META_SP = 13f
private const val RESULT_SNIPPET_LINES = 2

/**
 * A search result on a wide page, laid out as the video apps lay it out: the picture 360 x 202.5 at the start; at its side the title
 * in 18 sp (two lines), the meta line ("1.2M views · 3 days ago"), the channel with its 24 dp picture, a snippet of the description
 * when the item has one (two lines), and the more button at the end of the title line. One button for assistive technology; a tap
 * calls [onClick], the more button, a hold and a right click call [onMore]. [watchedFraction] draws the watched bar, [title] stands in
 * for the item's (a DeArrow title), [downloaded] marks a downloaded video.
 */
@Composable
fun VideoResultCard(
    item: VideoItem,
    onClick: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    watchedFraction: Float? = null,
    title: String = item.title,
    downloaded: Boolean = false,
) {
    val palette = LocalCosmoColors.current.palette
    val meta = MetaLine(item, rememberNowMs(), showChannel = false)
    val titleStyle = rememberTitleStyle(RESULT_TITLE_SP, title)
    val metaStyle = rememberMetaStyle(RESULT_META_SP, meta)
    val channelStyle = rememberMetaStyle(RESULT_META_SP, item.channelName.orEmpty())
    val snippet = item.snippet?.takeIf { it.isNotBlank() }
    val snippetStyle = rememberMetaStyle(RESULT_META_SP, snippet.orEmpty())
    val more = stringResource(Res.string.video_more)
    val hover = rememberCardHover()
    Row(
        with(hover) { modifier.fillMaxWidth().track() }
            .padding(horizontal = CosmoSpacing.margin, vertical = VideoMetrics.ROW_PAD_V_DP.dp)
            .videoClickable(CosmoRadii.md, onClick, onMore),
        verticalAlignment = Alignment.Top,
    ) {
        ThumbnailFrame(
            url = item.thumbnailUrl,
            modifier = with(hover) { Modifier.width(RESULT_PICTURE_W).aspectRatio(16f / 9f).lifted() },
            shape = ThumbnailShape,
            durationSec = item.durationSec,
            live = item.isLive,
            upcoming = item.isUpcoming,
            watchedFraction = watchedFraction,
            downloaded = downloaded,
            hovered = hover.hovered,
        )
        Spacer(Modifier.width(RESULT_GAP))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = titleStyle,
                color = Color(palette.textPrimary),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (meta.isNotEmpty()) {
                Text(
                    text = meta,
                    style = metaStyle,
                    color = Color(palette.textSecondary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (!item.channelName.isNullOrBlank()) {
                Row(Modifier.padding(top = 12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ChannelAvatar(item.channelAvatarUrl, RESULT_AVATAR, name = item.channelName)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = item.channelName.orEmpty(),
                        style = channelStyle,
                        color = Color(palette.textSecondary),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (snippet != null) {
                Text(
                    text = snippet,
                    style = snippetStyle,
                    color = Color(palette.textSecondary),
                    maxLines = RESULT_SNIPPET_LINES,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 12.dp),
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
