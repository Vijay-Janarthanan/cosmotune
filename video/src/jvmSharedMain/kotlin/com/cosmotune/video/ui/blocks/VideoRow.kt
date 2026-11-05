package com.cosmotune.video.ui.blocks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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

/**
 * A video in a list (DESIGN_SYSTEM.md 4.6): the thumbnail 128 x 72 with 8 dp corners and the duration inside it ([desktop]: 168 x 94.5),
 * padding 8 16, 16 dp of gap, the title `rowTitle` up to two lines, one line of `meta`, and a 40 dp plain "more" button (88 dp tall).
 * [trailing] replaces the more button with whatever a page shows at the end of
 * its rows (a download's progress); a hold and a right click still call [onMore]. [watchedFraction] draws the watched bar,
 * [title] stands in for the item's (a DeArrow title), [downloaded] marks a downloaded video.
 *
 * One button for assistive technology that reads the title, the meta line and the duration.
 */
@Composable
fun VideoRow(
    item: VideoItem,
    onClick: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    watchedFraction: Float? = null,
    desktop: Boolean = false,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    title: String = item.title,
    downloaded: Boolean = false,
) {
    val meta = MetaLine(item, rememberNowMs())
    val more = stringResource(Res.string.video_more)
    val hover = rememberCardHover()
    VideoRowFrame(
        thumbnail = {
            ThumbnailFrame(
                url = item.thumbnailUrl,
                modifier = with(hover) { Modifier.rowThumbnailSize(desktop).lifted() },
                shape = ThumbnailShape,
                durationSec = item.durationSec,
                live = item.isLive,
                upcoming = item.isUpcoming,
                watchedFraction = watchedFraction,
                downloaded = downloaded,
                hovered = hover.hovered,
            )
        },
        title = title,
        meta = meta,
        onClick = onClick,
        onMore = onMore,
        modifier = with(hover) { modifier.track() },
        trailing = trailing ?: {
            CosmoIconButton(
                icon = CosmoIcons.MoreVertical,
                contentDescription = more,
                onClick = onMore,
                style = CosmoIconButtonStyle.PLAIN,
                size = VideoMetrics.MORE_BUTTON_DP.dp,
            )
        },
    )
}

/** The size of a row's thumbnail: 128 x 72, on the desktop 168 x 94.5. */
internal fun Modifier.rowThumbnailSize(desktop: Boolean): Modifier {
    val width = if (desktop) VideoMetrics.DESKTOP_ROW_THUMB_W_DP else VideoMetrics.ROW_THUMB_W_DP
    return size(width.dp, VideoMetrics.thumbHeightDp(width).dp)
}

/**
 * The shape of a row of videos and of playlists: [thumbnail], 16 dp, the [title] over the [meta] and [trailing]; padding 8 16. The
 * row is one button ([onClick]); a hold or a right click calls [onMore] when there is one.
 */
@Composable
internal fun VideoRowFrame(
    thumbnail: @Composable () -> Unit,
    title: String,
    meta: String,
    onClick: () -> Unit,
    onMore: (() -> Unit)?,
    modifier: Modifier,
    trailing: (@Composable RowScope.() -> Unit)?,
) {
    val palette = LocalCosmoColors.current.palette
    val titleStyle = rememberTitleStyle(title)
    val metaStyle = rememberMetaStyle(meta)
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = CosmoSpacing.margin, vertical = VideoMetrics.ROW_PAD_V_DP.dp)
            .videoClickable(CosmoRadii.rSm, onClick, onMore),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        thumbnail()
        Spacer(Modifier.width(ROW_THUMB_GAP))
        Column(Modifier.weight(1f)) {
            Text(text = title, style = titleStyle, color = Color(palette.textPrimary), maxLines = 2, overflow = TextOverflow.Ellipsis)
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
        if (trailing != null) trailing()
    }
}

/** A row's cover to its text (DESIGN_SYSTEM.md 3.3). */
private val ROW_THUMB_GAP = 16.dp
