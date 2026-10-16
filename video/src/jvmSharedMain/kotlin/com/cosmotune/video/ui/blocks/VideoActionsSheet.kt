package com.cosmotune.video.ui.blocks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.SuperellipseShape
import com.cosmotune.ui.sheets.CosmoActionRow
import com.cosmotune.ui.sheets.CosmoSheetGap
import com.cosmotune.ui.sheets.SurfaceSheetSwitch
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_add_to_queue
import com.cosmotune.video.resources.video_dl_done_cd
import com.cosmotune.video.resources.video_download
import com.cosmotune.video.resources.video_open_channel
import com.cosmotune.video.resources.video_play
import com.cosmotune.video.resources.video_play_as_song
import com.cosmotune.video.resources.video_remove_from_history
import com.cosmotune.video.resources.video_remove_from_playlist
import com.cosmotune.video.resources.video_save_to_playlist
import com.cosmotune.video.resources.video_share
import com.cosmotune.video.resources.video_watch_later
import com.cosmotune.video.ui.VideoIcons
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Where a video's sheet was opened from: what it offers besides the common actions (spec 5.12). */
enum class VideoSheetContext {
    /** Any card, row or Short: the common actions. */
    DEFAULT,

    /** A row of History: also "Remove from history". */
    HISTORY,

    /** A row of one of your own playlists: also "Remove from this playlist". */
    YOUR_PLAYLIST,
}

/** What a row of the video sheet asks for. The screen's handler does the work; the sheet only says which one was tapped. */
sealed interface VideoAction {
    data object Play : VideoAction
    data object AddToQueue : VideoAction
    data object PlayAsSong : VideoAction
    data object SaveToPlaylist : VideoAction
    data object WatchLater : VideoAction
    data object Download : VideoAction
    data object Share : VideoAction
    data object OpenChannel : VideoAction
    data object RemoveFromHistory : VideoAction
    data object RemoveFromPlaylist : VideoAction
}

/**
 * The actions the sheet of [item] offers in [context], in the order of spec 5.12: Play, Add to queue, Play as a song, Save to a
 * playlist, Watch later, Download, Share, Open the channel; then Remove from history in the history, Remove from this playlist in
 * your own playlist. A live stream or a premiere that has not started has no stream to take apart: no "Play as a song" and no
 * "Download" (spec 5.13). An item with no channel id has no channel to open.
 */
internal fun videoActionsFor(item: VideoItem, context: VideoSheetContext): List<VideoAction> = buildList {
    val streamable = !item.isLive && !item.isUpcoming
    add(VideoAction.Play)
    add(VideoAction.AddToQueue)
    if (streamable) add(VideoAction.PlayAsSong)
    add(VideoAction.SaveToPlaylist)
    add(VideoAction.WatchLater)
    if (streamable) add(VideoAction.Download)
    add(VideoAction.Share)
    if (item.channelId != null) add(VideoAction.OpenChannel)
    when (context) {
        VideoSheetContext.DEFAULT -> Unit
        VideoSheetContext.HISTORY -> add(VideoAction.RemoveFromHistory)
        VideoSheetContext.YOUR_PLAYLIST -> add(VideoAction.RemoveFromPlaylist)
    }
}

private fun VideoAction.label(): StringResource = when (this) {
    VideoAction.Play -> Res.string.video_play
    VideoAction.AddToQueue -> Res.string.video_add_to_queue
    VideoAction.PlayAsSong -> Res.string.video_play_as_song
    VideoAction.SaveToPlaylist -> Res.string.video_save_to_playlist
    VideoAction.WatchLater -> Res.string.video_watch_later
    VideoAction.Download -> Res.string.video_download
    VideoAction.Share -> Res.string.video_share
    VideoAction.OpenChannel -> Res.string.video_open_channel
    VideoAction.RemoveFromHistory -> Res.string.video_remove_from_history
    VideoAction.RemoveFromPlaylist -> Res.string.video_remove_from_playlist
}

private fun VideoAction.icon(downloaded: Boolean): ImageVector = when (this) {
    VideoAction.Play -> CosmoIcons.Play
    VideoAction.AddToQueue -> CosmoIcons.Queue
    VideoAction.PlayAsSong -> CosmoIcons.Waveform
    VideoAction.SaveToPlaylist -> VideoIcons.PlaylistAdd
    VideoAction.WatchLater -> CosmoIcons.Clock
    VideoAction.Download -> if (downloaded) CosmoIcons.Downloaded else CosmoIcons.Download
    VideoAction.Share -> CosmoIcons.Share
    VideoAction.OpenChannel -> CosmoIcons.User
    VideoAction.RemoveFromHistory -> CosmoIcons.Close
    VideoAction.RemoveFromPlaylist -> CosmoIcons.Close
}

/** The sheet's head (`.sh-head`, as part two's song sheet has it): padding 0 20 16, 14 dp of gap, the title 17 sp weight 700. */
private val HEAD_PADDING_H = 20.dp
private val HEAD_PADDING_BOTTOM = 16.dp
private val HEAD_GAP = 14.dp
private const val HEAD_TITLE_SP = 17f
private const val HEAD_CHANNEL_SP = 13.5f

/** The head's thumbnail (spec 5.12): 96 x 54, radius 12. */
private val HEAD_THUMB_W = 96.dp
private val HEAD_THUMB_H = 54.dp
private val HEAD_THUMB_SHAPE = SuperellipseShape(12.dp)

/** Room under the last row. */
private val SHEET_END = 8.dp

/**
 * The video sheet (spec 5.12), opened from a card's "more", a hold or a right click: a [CosmoSheetSurface][com.cosmotune.ui.sheets.CosmoSheetSurface]
 * (through the host part two's album sheet uses, which also dims the page and handles the drag) with the head (a 96 x 54 thumbnail
 * with radius 12, the title up to two lines, the channel) and one 54 dp [CosmoActionRow] for each of [videoActionsFor].
 *
 * A tap on a row calls [onAction] with its action and then [onDismiss]: the sheet closes itself, so a handler that opens another
 * sheet (Save to a playlist) shows it on a clear page. A drag, the scrim and Back call [onDismiss] only. [downloaded] marks the
 * Download row of a video that is already downloaded (its icon and "Downloaded" at the end of the row).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoActionsSheet(
    item: VideoItem,
    context: VideoSheetContext,
    onAction: (VideoAction) -> Unit,
    onDismiss: () -> Unit,
    downloaded: Boolean = false,
) {
    SurfaceSheetSwitch(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        VideoActionsSheetBody(item, context, downloaded, onAction, onDismiss)
    }
}

/** What the sheet holds, without its host: the head and the rows. The host lays it on the sheet's surface; a render lays it on one directly. */
@Composable
internal fun ColumnScope.VideoActionsSheetBody(
    item: VideoItem,
    context: VideoSheetContext,
    downloaded: Boolean,
    onAction: (VideoAction) -> Unit,
    onDismiss: () -> Unit,
) {
    val actions = remember(item.id, item.isLive, item.isUpcoming, item.channelId, context) { videoActionsFor(item, context) }
    val doneLabel = stringResource(Res.string.video_dl_done_cd)
    SheetHead(item)
    for (action in actions) {
        // the removals stand apart from the rest, as the song sheet's destructive row does
        if (action == VideoAction.RemoveFromHistory || action == VideoAction.RemoveFromPlaylist) CosmoSheetGap(menu = false)
        CosmoActionRow(
            icon = action.icon(downloaded),
            label = stringResource(action.label()),
            value = if (downloaded && action == VideoAction.Download) doneLabel else null,
            onClick = {
                onAction(action)
                onDismiss()
            },
        )
    }
    Spacer(Modifier.height(SHEET_END))
}

/** The head of the sheet: the thumbnail and the two lines, one block for a screen reader. */
@Composable
private fun SheetHead(item: VideoItem) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val titleStyle = remember(type, item.title) {
        type.forText(
            TextStyle(fontFamily = type.textFamily, fontWeight = FontWeight.Bold, fontSize = HEAD_TITLE_SP.sp, lineHeight = HEAD_TITLE_SP.sp * 1.25f),
            item.title,
        )
    }
    val channel = item.channelName?.takeIf { it.isNotBlank() }
    val channelStyle = rememberMetaStyle(HEAD_CHANNEL_SP, channel.orEmpty())
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = HEAD_PADDING_H, end = HEAD_PADDING_H, bottom = HEAD_PADDING_BOTTOM)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThumbnailFrame(url = item.thumbnailUrl, modifier = Modifier.size(HEAD_THUMB_W, HEAD_THUMB_H), shape = HEAD_THUMB_SHAPE)
        Spacer(Modifier.width(HEAD_GAP))
        Column(Modifier.weight(1f)) {
            Text(text = item.title, style = titleStyle, color = Color(palette.textPrimary), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (channel != null) {
                Text(text = channel, style = channelStyle, color = Color(palette.textSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
