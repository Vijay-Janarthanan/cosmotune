package com.cosmotune.video.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cosmotune.design.TypeScale
import com.cosmotune.ui.bridge.DropdownMenu
import com.cosmotune.ui.bridge.DropdownMenuItem
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIconButtonStyle
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.sheets.CosmoToast
import com.cosmotune.ui.states.CosmoEmptyState
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.LocalReducedMotion
import com.cosmotune.video.downloads.DownloadController
import com.cosmotune.video.downloads.DownloadEntry
import com.cosmotune.video.downloads.DownloadItemAction
import com.cosmotune.video.downloads.DownloadState
import com.cosmotune.video.downloads.DownloadsFilter
import com.cosmotune.video.downloads.DownloadsPlan
import com.cosmotune.video.downloads.DownloadsRow
import com.cosmotune.video.downloads.DownloadsSort
import com.cosmotune.video.downloads.readLocalBundle
import com.cosmotune.video.graph.LocalVideoGraph
import com.cosmotune.video.nav.VideoRoute
import com.cosmotune.video.player.VideoPlayback
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_dl_empty
import com.cosmotune.video.resources.video_dl_filter_all
import com.cosmotune.video.resources.video_dl_filter_audio
import com.cosmotune.video.resources.video_dl_filter_shorts
import com.cosmotune.video.resources.video_dl_filter_videos
import com.cosmotune.video.resources.video_dl_save_copy_failed
import com.cosmotune.video.resources.video_dl_saved_copy
import com.cosmotune.video.resources.video_dl_sort
import com.cosmotune.video.resources.video_dl_sort_newest
import com.cosmotune.video.resources.video_dl_sort_oldest
import com.cosmotune.video.resources.video_dl_sort_size
import com.cosmotune.video.resources.video_dl_sort_title
import com.cosmotune.video.resources.video_dl_storage
import com.cosmotune.video.resources.video_downloads
import com.cosmotune.video.ui.blocks.VideoChips
import com.cosmotune.video.ui.blocks.rememberMetaStyle
import com.cosmotune.video.ui.shell.LocalVideoNavigator
import com.cosmotune.video.ui.shell.VideoNavigator
import com.cosmotune.music.sharedui.resources.cosmo_state_empty
import com.cosmotune.music.ui.components.FLOATING_BAR_MAX_WIDTH
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import com.cosmotune.music.sharedui.resources.Res as SharedRes

/**
 * The Downloads page (spec 5.10): where everything downloaded is watched later. "Downloads" in `display(DISPLAY_M)` with the sort
 * button at its end (Newest, Oldest, Title, Size: the shell's top bar has no room for a page's menu, so the menu stands where
 * Library's heading keeps its button); the storage line ("Downloads use 1.4 GB · 21 GB free", 13 sp `textSecondary`); the filter
 * chips All, Videos, Shorts, Audio; then each batch as a group (its header, its downloads under it, collapsible) and the loose
 * downloads after, each a video row that says its state (see [DownloadItemRow]).
 *
 * A tap on a finished download watches it offline: its files, subtitles, chapters and SponsorBlock segments are read from its
 * sidecar off the main thread, `playback.playLocal` plays them and the watch page opens on it. A tap on a failed one tries again;
 * on any other, as the more button, a hold or a right click, it opens the item's sheet ([DownloadItemSheet]). On the phone a swipe
 * asks to delete. Nothing downloaded: the empty state "Nothing downloaded yet". Wide (840 dp and up, the desktop): the same list,
 * 840 dp at most, centred. Reduced motion: no item animations, the chevron turns at once.
 *
 * [contentPadding] is the shell's, on the list, so the rows pass under the bars and the last one scrolls clear of them.
 */
@Composable
fun DownloadsPage(contentPadding: PaddingValues, wide: Boolean) {
    val graph = LocalVideoGraph.current
    val navigator = LocalVideoNavigator.current
    val downloads = graph.downloads
    val entries by downloads.downloads.collectAsState()
    val batches by downloads.batches.collectAsState()
    val page = rememberDownloadsPageState()
    val rows = remember(entries, batches, page.filter, page.sort, page.collapsed) {
        DownloadsPlan.build(entries, batches, page.filter, page.sort, page.collapsed)
    }
    val storage = rememberStorageLine(downloads, entries)
    val reduced = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    val saveCopy = rememberSaveCopy()
    var sheetFor by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var toast by remember { mutableStateOf<DownloadsToast?>(null) }
    var toastSerial by remember { mutableIntStateOf(0) }
    val showToast = { message: StringResource ->
        toastSerial += 1
        toast = DownloadsToast(message, toastSerial)
    }
    val playOffline = { entry: DownloadEntry -> scope.launch { playDownload(graph.playback, navigator, entry) }; Unit }

    val tap = { entry: DownloadEntry ->
        when (DownloadsPlan.tapAction(entry)) {
            DownloadItemAction.PLAY -> playOffline(entry)
            DownloadItemAction.RETRY -> downloads.retry(entry.id)
            else -> sheetFor = entry.id
        }
        Unit
    }
    val act = { entry: DownloadEntry, action: DownloadItemAction ->
        when (action) {
            DownloadItemAction.PLAY -> playOffline(entry)
            DownloadItemAction.PAUSE -> downloads.pause(entry.id)
            DownloadItemAction.RESUME -> downloads.resume(entry.id)
            DownloadItemAction.RETRY -> downloads.retry(entry.id)
            DownloadItemAction.CANCEL -> downloads.cancel(entry.id)
            DownloadItemAction.DELETE -> deleting = entry.id
            DownloadItemAction.SAVE_COPY -> saveCopy?.let { copier ->
                scope.launch {
                    val saved = runCatching { copier.save(entry) }.getOrDefault(false)
                    showToast(if (saved) Res.string.video_dl_saved_copy else Res.string.video_dl_save_copy_failed)
                }
            }
        }
        Unit
    }

    val filterLabels = listOf(
        stringResource(Res.string.video_dl_filter_all),
        stringResource(Res.string.video_dl_filter_videos),
        stringResource(Res.string.video_dl_filter_shorts),
        stringResource(Res.string.video_dl_filter_audio),
    )
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
            item(key = "heading", contentType = "heading") {
                DownloadsHeading(sort = page.sort, onSort = { page.sort = it }, showSort = entries.isNotEmpty(), modifier = Modifier.downloadsColumn(wide))
            }
            item(key = "storage", contentType = "storage") {
                StorageLine(storage, Modifier.downloadsColumn(wide))
            }
            if (entries.isNotEmpty()) {
                item(key = "filters", contentType = "filters") {
                    VideoChips(
                        labels = filterLabels,
                        selected = page.filter.ordinal,
                        onSelect = { page.filter = DownloadsFilter.entries[it] },
                        modifier = Modifier.downloadsColumn(wide),
                    )
                }
            }
            if (rows.isEmpty()) {
                item(key = "empty", contentType = "empty") {
                    CosmoEmptyState(
                        message = if (entries.isEmpty()) stringResource(Res.string.video_dl_empty) else stringResource(SharedRes.string.cosmo_state_empty),
                        icon = CosmoIcons.Download,
                        modifier = Modifier.downloadsColumn(wide),
                    )
                }
            }
            items(rows, key = { it.key }, contentType = { if (it is DownloadsRow.Batch) "batch" else "item" }) { row ->
                val motion = if (reduced) Modifier else Modifier.animateItem()
                when (row) {
                    is DownloadsRow.Batch -> DownloadBatchHeader(
                        batch = row,
                        onToggle = { page.toggle(row.info.id) },
                        onPause = { downloads.pauseBatch(row.info.id) },
                        onResume = { downloads.resumeBatch(row.info.id) },
                        onCancel = { cancelBatch(downloads, entries, row.info.id) },
                        modifier = motion.downloadsColumn(wide),
                    )
                    is DownloadsRow.Item -> {
                        // the lambdas hold the entry, which the engine keeps as it is until it changes: a row whose download did
                        // not move is skipped while another one's progress ticks
                        val entry = row.entry
                        DownloadItemRow(
                            entry = entry,
                            onClick = { tap(entry) },
                            onMore = { sheetFor = entry.id },
                            desktop = wide,
                            modifier = motion.downloadsColumn(wide),
                            onSwipeDelete = if (wide) null else ({ deleting = entry.id }),
                        )
                    }
                }
            }
        }
        PageToast(toast, contentPadding, onGone = { shown -> if (toast == shown) toast = null })
    }

    // The sheet and the question follow the entry as it changes; when it is gone (finished elsewhere, deleted) they close.
    val sheetEntry = sheetFor?.let { id -> entries.firstOrNull { it.id == id } }
    if (sheetFor != null && sheetEntry == null) LaunchedEffect(sheetFor) { sheetFor = null }
    if (sheetEntry != null) {
        DownloadItemSheet(
            entry = sheetEntry,
            actions = DownloadsPlan.actionsFor(sheetEntry, canSaveCopy = saveCopy != null),
            onAction = { action -> act(sheetEntry, action) },
            onDismiss = { sheetFor = null },
        )
    }
    val deletingEntry = deleting?.let { id -> entries.firstOrNull { it.id == id } }
    if (deleting != null && deletingEntry == null) LaunchedEffect(deleting) { deleting = null }
    if (deletingEntry != null) {
        DeleteDownloadDialog(
            title = deletingEntry.item.title,
            onConfirm = {
                if (DownloadsPlan.removes(deletingEntry)) downloads.remove(deletingEntry.id) else downloads.cancel(deletingEntry.id)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}

/**
 * Watches a finished download offline (spec 5.4 "Offline", 5.10): the bundle (the files, and from the sidecar the subtitles, the
 * chapters and the SponsorBlock segments) is read off the main thread, then [VideoPlayback.playLocal] plays it from where it was
 * left and the watch page is pushed on it (the watch route has no download id: the player already holds the files). False, and
 * nothing happens, when [entry] is not finished.
 */
internal suspend fun playDownload(playback: VideoPlayback, navigator: VideoNavigator, entry: DownloadEntry): Boolean {
    val bundle = withContext(Dispatchers.IO) { entry.readLocalBundle() } ?: return false
    playback.playLocal(entry.item, bundle)
    navigator.push(VideoRoute.Watch(entry.item.id))
    return true
}

/** A batch's Cancel: the whole batch while nothing of it is finished, else only what is not, so no downloaded video is deleted. */
internal fun cancelBatch(downloads: DownloadController, entries: List<DownloadEntry>, batchId: String) {
    val ids = DownloadsPlan.batchCancelIds(entries, batchId)
    if (ids == null) downloads.cancelBatch(batchId) else ids.forEach(downloads::cancel)
}

/**
 * The storage line's two sizes, measured off the main thread (the free space is a file system call) whenever a download finishes or
 * leaves, and once more a moment later, when a deletion has freed its space. Null until the first measure.
 */
@Composable
private fun rememberStorageLine(downloads: DownloadController, entries: List<DownloadEntry>): Pair<String, String>? {
    // what changes the line: a download added or gone, or one finished; a running download's progress does not
    val usedKey = remember(entries) { entries.size to entries.sumOf { (it.state as? DownloadState.Done)?.bytes ?: 0L } }
    var line by remember(downloads) { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(downloads, usedKey) {
        line = DownloadsPlan.storage(withContext(Dispatchers.IO) { downloads.storage() })
        delay(DownloadsMetrics.REMEASURE_MS)
        line = DownloadsPlan.storage(withContext(Dispatchers.IO) { downloads.storage() })
    }
    return line
}

/** "Downloads" in `display(DISPLAY_M)` (spec 5.10) and, at its end, the 40 dp TONAL sort button with its menu ([showSort]: not while there is nothing to sort). */
@Composable
private fun DownloadsHeading(sort: DownloadsSort, onSort: (DownloadsSort) -> Unit, showSort: Boolean, modifier: Modifier = Modifier) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val title = stringResource(Res.string.video_downloads)
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = CosmoSpacing.margin, end = DownloadsMetrics.HEADING_END_DP.dp, top = DownloadsMetrics.HEADING_TOP_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = type.forText(type.display(TypeScale.DISPLAY_M), title),
            color = Color(palette.textPrimary),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (showSort) SortButton(sort, onSort)
    }
}

/** The sort button (40 dp TONAL) and its menu: Newest, Oldest, Title, Size, the one in use ticked. */
@Composable
private fun SortButton(sort: DownloadsSort, onSort: (DownloadsSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val palette = LocalCosmoColors.current.palette
    Box {
        CosmoIconButton(
            icon = CosmoIcons.Sort,
            contentDescription = stringResource(Res.string.video_dl_sort),
            onClick = { open = true },
            style = CosmoIconButtonStyle.TONAL,
            size = 40.dp,
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (option in DownloadsSort.entries) {
                DropdownMenuItem(
                    text = { Text(stringResource(option.label())) },
                    onClick = {
                        open = false
                        onSort(option)
                    },
                    trailingIcon = if (option == sort) {
                        { Icon(CosmoIcons.Check, contentDescription = null, tint = Color(palette.accent)) }
                    } else null,
                )
            }
        }
    }
}

/** The words of each sort: `video_dl_sort_newest`, `_oldest`, `_title`, `_size`. */
internal fun DownloadsSort.label(): StringResource = when (this) {
    DownloadsSort.NEWEST -> Res.string.video_dl_sort_newest
    DownloadsSort.OLDEST -> Res.string.video_dl_sort_oldest
    DownloadsSort.TITLE -> Res.string.video_dl_sort_title
    DownloadsSort.SIZE -> Res.string.video_dl_sort_size
}

/** The storage line (spec 5.10): "Downloads use 1.4 GB · 21 GB free", 13 sp `textSecondary`; its place kept while it is measured. */
@Composable
private fun StorageLine(sizes: Pair<String, String>?, modifier: Modifier = Modifier) {
    val palette = LocalCosmoColors.current.palette
    val text = if (sizes == null) "" else stringResource(Res.string.video_dl_storage, sizes.first, sizes.second)
    val style = rememberMetaStyle(DownloadsMetrics.STORAGE_SP, text)
    Text(
        text = text,
        style = style,
        color = Color(palette.textSecondary),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.fillMaxWidth().padding(start = CosmoSpacing.margin, end = CosmoSpacing.margin, top = DownloadsMetrics.STORAGE_TOP_DP.dp),
    )
}

/** The page's toast ("Saved to Movies"): 4 s over the page, above the floating chrome, as the action host's. */
@Composable
private fun PageToast(toast: DownloadsToast?, contentPadding: PaddingValues, onGone: (DownloadsToast) -> Unit) {
    val shown = toast ?: return
    LaunchedEffect(shown) {
        delay(DownloadsMetrics.TOAST_MS)
        onGone(shown)
    }
    Box(Modifier.fillMaxSize().padding(bottom = contentPadding.calculateBottomPadding()), contentAlignment = Alignment.BottomCenter) {
        key(shown.serial) { CosmoToast(stringResource(shown.message), Modifier.widthIn(max = FLOATING_BAR_MAX_WIDTH)) }
    }
}
