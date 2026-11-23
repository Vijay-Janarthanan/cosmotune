package com.cosmotune.video.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitBarAction
import com.cosmotune.ui.orbit.OrbitCheckBox
import com.cosmotune.ui.orbit.OrbitSelectionBar
import com.cosmotune.ui.orbit.OrbitSelectionHead
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.video.graph.LocalVideoGraph
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_add_to_queue
import com.cosmotune.video.resources.video_download
import com.cosmotune.video.resources.video_select
import com.cosmotune.video.resources.video_watch_later
import com.cosmotune.video.ui.actions.VideoActionHandler
import com.cosmotune.video.ui.shell.VideoBackHandler
import com.cosmotune.video.downloads.BatchKind
import com.cosmotune.video.downloads.DownloadBatchInfo
import org.jetbrains.compose.resources.stringResource

/**
 * Select several videos of a list (History, Watch later, Liked, your playlists): "Select" in the page's menu starts it; each row
 * then shows a check and a tap ticks it; the head says how many with Select all, and the bar at the foot acts on them all.
 */
@Stable
internal class VideoSelection {
    var active by mutableStateOf(false)
        private set
    var ids by mutableStateOf<Set<String>>(emptySet())
        private set

    fun start() {
        active = true
    }

    fun close() {
        active = false
        ids = emptySet()
    }

    fun toggle(id: String) {
        ids = if (id in ids) ids - id else ids + id
    }

    fun selectAll(all: List<VideoItem>) {
        ids = all.mapTo(LinkedHashSet()) { it.id }
    }

    fun chosen(all: List<VideoItem>): List<VideoItem> = all.filter { it.id in ids }
}

/** The check a row shows at its end while selecting. */
@Composable
internal fun SelectionCheck(selection: VideoSelection, item: VideoItem) {
    OrbitCheckBox(checked = item.id in selection.ids, onChange = { selection.toggle(item.id) }, label = item.title)
}

/** The head of select mode, as an item at the top of the list. */
@Composable
internal fun VideoSelectionHead(selection: VideoSelection, all: List<VideoItem>, modifier: Modifier = Modifier) {
    OrbitSelectionHead(
        count = selection.ids.size,
        onClose = selection::close,
        onSelectAll = if (selection.ids.size < all.size) ({ selection.selectAll(all) }) else null,
        modifier = modifier.padding(horizontal = CosmoSpacing.margin),
    )
}

/**
 * The bar over the foot of the page while selecting: Add to queue, Watch later, Download, and [remove] (the page's own: from the
 * history, from this playlist) when it has one. Each acts on what is ticked and ends select mode. Back ends it too.
 */
@Composable
internal fun BoxScope.VideoSelectionBar(
    selection: VideoSelection,
    all: List<VideoItem>,
    actions: VideoActionHandler,
    contentPadding: PaddingValues,
    batchTitle: String,
    remove: OrbitBarAction? = null,
) {
    VideoBackHandler(enabled = selection.active) { selection.close() }
    if (!selection.active) return
    val graph = LocalVideoGraph.current
    val chosen = selection.chosen(all)
    val any = chosen.isNotEmpty()
    val bar = buildList {
        add(OrbitBarAction(CosmoIcons.Queue, stringResource(Res.string.video_add_to_queue), any) {
            chosen.forEach { graph.playback.addToQueue(it) }
            selection.close()
        })
        add(OrbitBarAction(CosmoIcons.Clock, stringResource(Res.string.video_watch_later), any) {
            val saved = graph.library.watchLater.value.mapTo(HashSet()) { it.id }
            chosen.filter { it.id !in saved }.forEach { graph.library.addToWatchLater(it) }
            selection.close()
        })
        add(OrbitBarAction(CosmoIcons.Download, stringResource(Res.string.video_download), any) {
            actions.downloadBatch(DownloadBatchInfo("select:${chosen.hashCode()}", BatchKind.FEED, batchTitle), chosen.filter { !it.isLive && !it.isUpcoming }, latestPicker = false)
            selection.close()
        })
        if (remove != null) add(OrbitBarAction(remove.icon, remove.label, any) {
            remove.onClick()
            selection.close()
        })
    }
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = contentPadding.calculateBottomPadding() + 12.dp, start = CosmoSpacing.margin, end = CosmoSpacing.margin)
            .widthIn(max = 560.dp),
    ) { OrbitSelectionBar(bar) }
}

/** The "Select" entry the pages put in their menus. */
@Composable
internal fun selectLabel(): String = stringResource(Res.string.video_select)
