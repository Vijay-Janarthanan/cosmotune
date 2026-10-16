package com.cosmotune.video.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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
import com.cosmotune.ui.blocks.CosmoOverline
import com.cosmotune.ui.blocks.CosmoSearchField
import com.cosmotune.ui.components.CosmoButton
import com.cosmotune.ui.components.CosmoButtonKind
import com.cosmotune.ui.components.CosmoButtonSize
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIconButtonStyle
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.CosmoSwitch
import com.cosmotune.ui.sheets.CosmoDialogCard
import com.cosmotune.ui.states.CosmoEmptyState
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.video.feed.FeedDay
import com.cosmotune.video.feed.FeedGroup
import com.cosmotune.video.feed.FeedMerge
import com.cosmotune.video.graph.LocalVideoGraph
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_clear
import com.cosmotune.video.resources.video_clear_history
import com.cosmotune.video.resources.video_clear_history_confirm
import com.cosmotune.video.resources.video_empty_history
import com.cosmotune.video.resources.video_history
import com.cosmotune.video.resources.video_history_paused
import com.cosmotune.video.resources.video_more
import com.cosmotune.video.resources.video_no_results
import com.cosmotune.video.resources.video_older
import com.cosmotune.video.resources.video_pause_history
import com.cosmotune.video.resources.video_search_history
import com.cosmotune.video.resources.video_this_month
import com.cosmotune.video.resources.video_this_week
import com.cosmotune.video.resources.video_today
import com.cosmotune.video.resources.video_yesterday
import com.cosmotune.video.ui.VideoIcons
import com.cosmotune.video.ui.actions.rememberDownloadedIds
import com.cosmotune.video.ui.actions.rememberVideoActions
import com.cosmotune.video.ui.blocks.VideoActionsSheet
import com.cosmotune.video.ui.blocks.VideoSheetContext
import com.cosmotune.video.ui.blocks.rememberNowMs
import com.music.bitchord.sharedui.resources.cancel
import org.jetbrains.compose.resources.stringResource
import com.music.bitchord.sharedui.resources.Res as SharedRes

/**
 * Watch history (spec 5.8): the heading with the page's menu (Pause history as a switch; Clear history, which asks first in a dialog
 * card), a line while history is paused, the search field that keeps the videos whose title or channel holds what is typed, and the
 * videos as rows with their watched bars under day overlines (Today, Yesterday, This week, This month, Older; by when each was last
 * watched). A row's sheet offers "Remove from history". Empty: the empty state; a search that finds nothing: "No results for ...".
 *
 * The menu stands at the end of the page's heading: the shell's top bar carries only Search and the profile button (Y-14), so the
 * page owns its actions, as Music's pages do.
 */
@Composable
fun HistoryPage(contentPadding: PaddingValues, wide: Boolean) {
    val library = LocalVideoGraph.current.library
    val actions = rememberVideoActions()
    val history by library.history.collectAsState()
    val paused by library.historyPaused.collectAsState()
    val downloadedIds by rememberDownloadedIds()
    var query by rememberSaveable { mutableStateOf("") }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var sheetFor by remember { mutableStateOf<VideoItem?>(null) }

    val now = rememberNowMs()
    val startOfToday = remember(now) { FeedDay.startOfToday(now) }
    val shown = remember(history, query) { LibraryPlan.filterHistory(history, query) }
    val groups = remember(shown, startOfToday) { FeedMerge.groups(shown, startOfToday) { it.lastWatchedMs } }
    val shownItems = remember(shown) { shown.map { it.item } }

    LibraryList(contentPadding, wide) { cell ->
        item(key = "heading") {
            LibraryHeader(stringResource(Res.string.video_history), wide, cell) {
                HistoryMenu(
                    paused = paused,
                    canClear = history.isNotEmpty(),
                    onPause = { library.setHistoryPaused(it) },
                    onClear = { confirmClear = true },
                )
            }
        }
        if (paused) {
            item(key = "paused") { PausedLine(cell) }
        }
        if (history.isNotEmpty()) {
            item(key = "search") {
                CosmoSearchField(
                    query = query,
                    onQueryChange = { query = it },
                    onSubmit = {},
                    placeholder = stringResource(Res.string.video_search_history),
                    modifier = cell.fillMaxWidth().padding(start = CosmoSpacing.margin, end = CosmoSpacing.margin, top = SEARCH_TOP, bottom = SEARCH_BOTTOM),
                )
            }
        }
        when {
            history.isEmpty() -> item(key = "empty") {
                CosmoEmptyState(message = stringResource(Res.string.video_empty_history), modifier = cell, icon = VideoIcons.History)
            }
            shown.isEmpty() -> item(key = "no-results") {
                CosmoEmptyState(message = stringResource(Res.string.video_no_results, query.trim()), modifier = cell, icon = CosmoIcons.Search)
            }
            else -> for ((group, entries) in groups) {
                item(key = "group:" + group.name) { CosmoOverline(groupLabel(group), cell) }
                items(entries, key = { "video:" + it.item.id }) { entry ->
                    LibraryVideoRow(
                        item = entry.item,
                        onClick = { actions.play(entry.item, shownItems) },
                        onMore = { sheetFor = entry.item },
                        wide = wide,
                        modifier = cell,
                        watchedFraction = entry.item.watchedFraction(entry.positionMs, entry.durationMs),
                        downloaded = entry.item.id in downloadedIds,
                    )
                }
            }
        }
    }

    if (confirmClear) {
        ClearHistoryDialog(
            onConfirm = {
                library.clearHistory()
                confirmClear = false
            },
            onDismiss = { confirmClear = false },
        )
    }
    sheetFor?.let { video ->
        VideoActionsSheet(
            item = video,
            context = VideoSheetContext.HISTORY,
            onAction = { action -> actions.handle(video, action, VideoSheetContext.HISTORY, list = shownItems) },
            onDismiss = { sheetFor = null },
            downloaded = video.id in downloadedIds,
        )
    }
}

/** The day overline of [group] (spec 5.7, 5.8). */
@Composable
private fun groupLabel(group: FeedGroup): String = stringResource(
    when (group) {
        FeedGroup.TODAY -> Res.string.video_today
        FeedGroup.YESTERDAY -> Res.string.video_yesterday
        FeedGroup.THIS_WEEK -> Res.string.video_this_week
        FeedGroup.THIS_MONTH -> Res.string.video_this_month
        FeedGroup.OLDER -> Res.string.video_older
    },
)

/**
 * The page's menu (spec 5.8: "Clear history" and "Pause history"): a 40 dp tonal more button at the end of the heading that opens the
 * bridge's menu (part two's menu card) with "Pause history" and its switch, and "Clear history", which only asks ([onClear]); it is
 * dimmed while there is nothing to clear.
 */
@Composable
private fun HistoryMenu(paused: Boolean, canClear: Boolean, onPause: (Boolean) -> Unit, onClear: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val pauseLabel = stringResource(Res.string.video_pause_history)
    val clearLabel = stringResource(Res.string.video_clear_history)
    Box {
        CosmoIconButton(CosmoIcons.MoreVertical, stringResource(Res.string.video_more), { open = true }, style = CosmoIconButtonStyle.TONAL, size = 40.dp)
        com.cosmotune.ui.bridge.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            com.cosmotune.ui.bridge.DropdownMenuItem(
                text = { Text(pauseLabel) },
                onClick = { onPause(!paused) },
                modifier = Modifier.semantics {
                    role = Role.Switch
                    toggleableState = ToggleableState(paused)
                },
                leadingIcon = { Icon(CosmoIcons.Pause, contentDescription = null) },
                trailingIcon = {
                    CosmoSwitch(checked = paused, onCheckedChange = onPause, modifier = Modifier.clearAndSetSemantics { }, floating = true)
                },
            )
            com.cosmotune.ui.bridge.DropdownMenuItem(
                text = { Text(clearLabel) },
                onClick = {
                    open = false
                    onClear()
                },
                leadingIcon = { Icon(CosmoIcons.Close, contentDescription = null) },
                enabled = canClear,
            )
        }
    }
}

/** While history is paused (spec 5.8, 5.11): "History is paused" in 13 sp `textSecondary` with the pause mark, under the heading. */
@Composable
private fun PausedLine(modifier: Modifier) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val text = stringResource(Res.string.video_history_paused)
    val style = remember(type, text) { type.forText(TextStyle(fontFamily = type.textFamily, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 13.sp * 1.35f), text) }
    Row(
        modifier.fillMaxWidth().padding(start = CosmoSpacing.margin, end = CosmoSpacing.margin, top = PAUSED_TOP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(CosmoIcons.Pause, contentDescription = null, tint = Color(palette.textSecondary), modifier = Modifier.size(PAUSED_ICON))
        Spacer(Modifier.width(PAUSED_GAP))
        Text(text = text, style = style, color = Color(palette.textSecondary))
    }
}

/**
 * "Clear history" asks first (spec 5.8): part two's dialog card (the 40 % scrim, the glass card, at most 440 dp wide, the page margin
 * kept on a phone) over the whole window with the title, "Clear your whole watch history on this device?", Cancel and Clear.
 * [onConfirm] clears; Cancel, the scrim, Back and Escape only close it.
 */
@Composable
private fun ClearHistoryDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Popup(
        popupPositionProvider = WindowOrigin,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = false, clippingEnabled = false),
    ) {
        ClearHistoryCard(onConfirm, onDismiss)
    }
}

/** The dialog's card without its window (a render draws it in place). */
@Composable
internal fun ClearHistoryCard(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val title = stringResource(Res.string.video_clear_history)
    val message = stringResource(Res.string.video_clear_history_confirm)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        CosmoDialogCard(onDismiss = onDismiss, maxWidth = minOf(DIALOG_MAX_WIDTH, maxWidth - CosmoSpacing.margin * 2)) {
            Column(Modifier.padding(start = DIALOG_PADDING, end = DIALOG_PADDING, top = DIALOG_PADDING)) {
                Text(
                    text = title,
                    style = type.forText(type.title(TypeScale.TITLE_S), title),
                    color = Color(palette.textPrimary),
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.size(DIALOG_TEXT_GAP))
                Text(
                    text = message,
                    style = type.forText(TextStyle(fontFamily = type.textFamily, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 15.sp * 1.45f), message),
                    color = Color(palette.textSecondary),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(start = DIALOG_PADDING, end = DIALOG_BUTTONS_END, top = DIALOG_BUTTONS_TOP, bottom = DIALOG_BUTTONS_BOTTOM),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CosmoButton(stringResource(SharedRes.string.cancel), onDismiss, kind = CosmoButtonKind.TEXT, size = CosmoButtonSize.SMALL, floating = true)
                Spacer(Modifier.width(DIALOG_BUTTON_GAP))
                CosmoButton(stringResource(Res.string.video_clear), onConfirm, kind = CosmoButtonKind.PRIMARY, size = CosmoButtonSize.SMALL, floating = true)
            }
        }
    }
}

/** The dialog's popup stands at the window's top left corner; the card's scrim fills the window from there. */
private object WindowOrigin : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset =
        IntOffset.Zero
}

/** Around the search field. */
private val SEARCH_TOP = 12.dp
private val SEARCH_BOTTOM = 4.dp

/** The paused line: 10 dp under the heading, a 16 dp mark 8 dp before the words. */
private val PAUSED_TOP = 10.dp
private val PAUSED_ICON = 16.dp
private val PAUSED_GAP = 8.dp

/** The dialog card, as the new-playlist dialog has it (Y-16): 440 dp at most, 24 dp inside, the buttons 16 dp from the end. */
private val DIALOG_MAX_WIDTH = 440.dp
private val DIALOG_PADDING = 24.dp
private val DIALOG_TEXT_GAP = 10.dp
private val DIALOG_BUTTONS_TOP = 20.dp
private val DIALOG_BUTTONS_END = 16.dp
private val DIALOG_BUTTONS_BOTTOM = 12.dp
private val DIALOG_BUTTON_GAP = 8.dp
