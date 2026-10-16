package com.cosmotune.video.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.cosmotune.design.TypeScale
import com.cosmotune.ui.components.CosmoButton
import com.cosmotune.ui.components.CosmoButtonKind
import com.cosmotune.ui.components.CosmoButtonSize
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.SuperellipseShape
import com.cosmotune.ui.sheets.CosmoActionRow
import com.cosmotune.ui.sheets.CosmoDialogCard
import com.cosmotune.ui.sheets.CosmoSheetGap
import com.cosmotune.ui.sheets.SurfaceSheetSwitch
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.video.downloads.DownloadEntry
import com.cosmotune.video.downloads.DownloadItemAction
import com.cosmotune.video.downloads.DownloadState
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_dl_cancel
import com.cosmotune.video.resources.video_dl_delete
import com.cosmotune.video.resources.video_dl_delete_confirm
import com.cosmotune.video.resources.video_dl_pause
import com.cosmotune.video.resources.video_dl_resume
import com.cosmotune.video.resources.video_dl_save_copy
import com.cosmotune.video.resources.video_play
import com.cosmotune.video.resources.video_retry
import com.cosmotune.video.ui.blocks.ThumbnailFrame
import com.cosmotune.video.ui.blocks.rememberMetaStyle
import com.music.bitchord.sharedui.resources.cancel
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import com.music.bitchord.sharedui.resources.Res as SharedRes

/**
 * A download's sheet (spec 5.10), opened from its row's more button, a hold or a right click, or a tap on a download under way: a
 * [CosmoSheetSurface][com.cosmotune.ui.sheets.CosmoSheetSurface] through the host part two's sheets use (it dims the page and handles
 * the drag), with the head (the 96 x 54 thumbnail, the title, the state in words) and one 54 dp [CosmoActionRow] for each of
 * [actions] (the plan's `actionsFor`: Play, Save a copy to Movies on Android, Pause or Resume, Try again, Cancel, and Delete apart,
 * last).
 *
 * A tap on a row calls [onAction] and then [onDismiss], so whatever comes next (the delete question, the watch page) has a clear
 * page. A drag, the scrim and Back call [onDismiss] only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DownloadItemSheet(
    entry: DownloadEntry,
    actions: List<DownloadItemAction>,
    onAction: (DownloadItemAction) -> Unit,
    onDismiss: () -> Unit,
) {
    SurfaceSheetSwitch(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        DownloadItemSheetBody(entry, actions, onAction, onDismiss)
    }
}

/** What the sheet holds, without its host: the head and the rows (a render lays it on a sheet surface directly). */
@Composable
internal fun ColumnScope.DownloadItemSheetBody(
    entry: DownloadEntry,
    actions: List<DownloadItemAction>,
    onAction: (DownloadItemAction) -> Unit,
    onDismiss: () -> Unit,
) {
    SheetHead(entry)
    for (action in actions) {
        // the delete stands apart from the rest, as the song sheet's destructive row does
        if (action == DownloadItemAction.DELETE) CosmoSheetGap(menu = false)
        CosmoActionRow(
            icon = action.icon(),
            label = stringResource(action.label()),
            onClick = {
                onAction(action)
                onDismiss()
            },
        )
    }
    Spacer(Modifier.height(SHEET_END))
}

/** The words of each action of the sheet: `video_play`, `video_dl_pause`, `video_dl_resume`, `video_retry`, `video_dl_cancel`, `video_dl_delete`, `video_dl_save_copy`. */
internal fun DownloadItemAction.label(): StringResource = when (this) {
    DownloadItemAction.PLAY -> Res.string.video_play
    DownloadItemAction.PAUSE -> Res.string.video_dl_pause
    DownloadItemAction.RESUME -> Res.string.video_dl_resume
    DownloadItemAction.RETRY -> Res.string.video_retry
    DownloadItemAction.CANCEL -> Res.string.video_dl_cancel
    DownloadItemAction.DELETE -> Res.string.video_dl_delete
    DownloadItemAction.SAVE_COPY -> Res.string.video_dl_save_copy
}

/** Cancel and Delete share the close mark: a sheet never offers both. */
private fun DownloadItemAction.icon(): ImageVector = when (this) {
    DownloadItemAction.PLAY -> CosmoIcons.Play
    DownloadItemAction.PAUSE -> CosmoIcons.Pause
    DownloadItemAction.RESUME -> CosmoIcons.Play
    DownloadItemAction.RETRY -> CosmoIcons.Replay
    DownloadItemAction.CANCEL -> CosmoIcons.Close
    DownloadItemAction.DELETE -> CosmoIcons.Close
    DownloadItemAction.SAVE_COPY -> CosmoIcons.Download
}

/** The sheet's head (as the video sheet's, spec 5.12): the 96 x 54 thumbnail (radius 12), the title (17 sp 700, two lines), the state in words. */
@Composable
private fun SheetHead(entry: DownloadEntry) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val title = entry.item.title
    val titleStyle = remember(type, title) {
        type.forText(
            TextStyle(fontFamily = type.textFamily, fontWeight = FontWeight.Bold, fontSize = HEAD_TITLE_SP.sp, lineHeight = HEAD_TITLE_SP.sp * 1.25f),
            title,
        )
    }
    val state = downloadStateText(entry)
    val stateStyle = rememberMetaStyle(HEAD_STATE_SP, state)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = HEAD_PADDING_H, end = HEAD_PADDING_H, bottom = HEAD_PADDING_BOTTOM)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThumbnailFrame(
            url = entry.item.thumbnailUrl,
            modifier = Modifier.size(HEAD_THUMB_W, HEAD_THUMB_H),
            shape = HEAD_THUMB_SHAPE,
            downloaded = entry.state is DownloadState.Done,
        )
        Spacer(Modifier.width(HEAD_GAP))
        Column(Modifier.weight(1f)) {
            Text(text = title, style = titleStyle, color = Color(palette.textPrimary), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(text = state, style = stateStyle, color = Color(palette.textSecondary), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---- The delete question -------------------------------------------------------------------------------------------------

/**
 * "Delete this download?" (spec 5.10: the swipe removes after a confirm; the sheet's Delete asks the same): part two's dialog card
 * (layer C: the 40 % scrim, the glass card, no blur) over the whole window, with the question, the video's [title], and Cancel and
 * Delete. [onConfirm] deletes; [onDismiss] closes it (Cancel, the scrim, Back or Escape).
 */
@Composable
internal fun DeleteDownloadDialog(title: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Popup(
        popupPositionProvider = DeleteDialogOrigin,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = false, clippingEnabled = false),
    ) {
        DeleteDownloadCard(title, onConfirm, onDismiss)
    }
}

/** The question's card without its window (a render draws it in place). */
@Composable
internal fun DeleteDownloadCard(title: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val question = stringResource(Res.string.video_dl_delete_confirm)
    val titleStyle = rememberMetaStyle(DIALOG_TEXT_SP, title)
    // The card fills the width it is given up to 440 dp: on a phone it keeps the page margin on both sides.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cardWidth = minOf(DIALOG_MAX_WIDTH, maxWidth - CosmoSpacing.margin * 2)
        CosmoDialogCard(onDismiss = onDismiss, maxWidth = cardWidth) {
            Column {
                Text(
                    text = question,
                    style = type.forText(type.title(TypeScale.TITLE_S), question),
                    color = Color(palette.textPrimary),
                    modifier = Modifier.padding(start = DIALOG_PADDING, end = DIALOG_PADDING, top = DIALOG_PADDING, bottom = DIALOG_TITLE_GAP).semantics { heading() },
                )
                Text(
                    text = title,
                    style = titleStyle,
                    color = Color(palette.textSecondary),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = DIALOG_PADDING),
                )
                Row(
                    Modifier.fillMaxWidth().padding(start = DIALOG_PADDING, end = DIALOG_BUTTONS_END, top = DIALOG_TEXT_GAP, bottom = DIALOG_BUTTONS_BOTTOM),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CosmoButton(stringResource(SharedRes.string.cancel), onDismiss, kind = CosmoButtonKind.TEXT, size = CosmoButtonSize.SMALL, floating = true)
                    Spacer(Modifier.width(DIALOG_BUTTON_GAP))
                    CosmoButton(stringResource(Res.string.video_dl_delete), onConfirm, kind = CosmoButtonKind.PRIMARY, size = CosmoButtonSize.SMALL, floating = true)
                }
            }
        }
    }
}

/** The popup stands at the window's top left corner; the card's scrim fills the window from there. */
private object DeleteDialogOrigin : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset =
        IntOffset.Zero
}

// ---- Save a copy to Movies (Android) -------------------------------------------------------------------------------------

/** "Save a copy to Movies" (spec 5.10, Q11): copies a finished download into the shared Movies folder; true when the copy is there. */
internal fun interface SaveCopy {
    suspend fun save(entry: DownloadEntry): Boolean
}

/** The platform's copy to Movies: Android's `SaveCopyToMovies` (Y-24); null on the desktop, where the sheet has no such row. */
@Composable
internal expect fun rememberSaveCopy(): SaveCopy?

/** The head (`.sh-head`, as the video sheet's): padding 0 20 16, 14 dp of gap, the title 17 sp 700, the state 13.5 sp. */
private val HEAD_PADDING_H = 20.dp
private val HEAD_PADDING_BOTTOM = 16.dp
private val HEAD_GAP = 14.dp
private const val HEAD_TITLE_SP = 17f
private const val HEAD_STATE_SP = 13.5f

/** The head's thumbnail (spec 5.12): 96 x 54, radius 12. */
private val HEAD_THUMB_W = 96.dp
private val HEAD_THUMB_H = 54.dp
private val HEAD_THUMB_SHAPE = SuperellipseShape(12.dp)

/** Room under the last row. */
private val SHEET_END = 8.dp

/** The question's card (as the new-playlist dialog's): at most 440 dp wide, padding 24, the question 12 dp above the video's title in 14 sp. */
private val DIALOG_MAX_WIDTH = 440.dp
private val DIALOG_PADDING = 24.dp
private val DIALOG_TITLE_GAP = 12.dp
private val DIALOG_TEXT_GAP = 20.dp
private val DIALOG_BUTTONS_END = 16.dp
private val DIALOG_BUTTONS_BOTTOM = 12.dp
private val DIALOG_BUTTON_GAP = 8.dp
private const val DIALOG_TEXT_SP = 14f
