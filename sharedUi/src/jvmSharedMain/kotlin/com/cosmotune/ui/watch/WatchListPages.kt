package com.cosmotune.ui.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.WatchCardChip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitPageHeading
import com.cosmotune.ui.orbit.WatchCard
import com.cosmotune.ui.orbit.WatchMessage
import com.cosmotune.watch.model.TitleSummary
import kotlinx.coroutines.launch

/** How My list is sorted. */
private enum class MyListSort(val label: String) { ADDED("Recently added"), NAME("A to Z"), YEAR("Year") }

/**
 * My list (board frame 10): the heading with how many are saved and in progress, a row of tabs with counts (My list, Playlists,
 * Downloads; the last two open their pages), the sort and Select, then the posters in a grid with how far each is watched. Select
 * picks several to remove at once; removing says so on the window's "Removed · Undo" snackbar instead of asking first.
 */
@Composable
internal fun WatchMyListPage(actions: WatchActions, fonts: OrbitFonts, look: OrbitLook, frame: WatchFrame) {
    val list by actions.repository.myList.collectAsState()
    val going by actions.repository.continueWatching.collectAsState()
    val playlists by actions.repository.playlists.collectAsState()
    val kept = actions.downloads?.items?.collectAsState()?.value.orEmpty()
    val side = frame.side
    var sort by rememberSaveable { mutableStateOf(MyListSort.ADDED) }
    var sortMenu by remember { mutableStateOf(false) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }
    val undo = com.cosmotune.ui.orbit.LocalOrbitUndo.current
    val watched = remember(going) { going.associate { it.summary.key to it.progress } }
    val shown = remember(list, sort) {
        when (sort) {
            MyListSort.ADDED -> list.sortedByDescending { it.addedAtMs }
            MyListSort.NAME -> list.sortedBy { it.summary.name.lowercase() }
            MyListSort.YEAR -> list.sortedByDescending { it.summary.year ?: 0 }
        }
    }
    Box(Modifier.fillMaxSize()) {
        WatchWash(Color(0xFF1C4C49), look)
        LazyVerticalGrid(
            GridCells.Adaptive((if (frame.wide) 150.dp else 104.dp) * com.cosmotune.ui.orbit.LocalWatchCardScale.current), Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = side, end = side, top = frame.padding.calculateTopPadding(), bottom = frame.padding.calculateBottomPadding() + 40.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            frame.header?.let { header -> item(key = "list:header", span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.padding(horizontal = 0.dp)) { header() } } }
            item(key = "list:heading", span = { GridItemSpan(maxLineSpan) }) {
                val inProgress = list.count { it.summary.key in watched }
                val label = listOfNotNull(if (list.size == 1) "1 saved" else "${list.size} saved", inProgress.takeIf { it > 0 }?.let { "$it in progress" }).joinToString(" · ")
                OrbitPageHeading("My list", label, fonts, look, Modifier.padding(start = 6.dp, top = if (frame.wide) 24.dp else 18.dp))
            }
            item(key = "list:tabs", span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.cosmotune.ui.orbit.OrbitSelectChip("My list · ${list.size}", true, fonts, look, onClick = {}, haptic = false)
                    com.cosmotune.ui.orbit.OrbitSelectChip("Playlists · ${playlists.size}", false, fonts, look, onClick = { WatchNav.open(WatchRoute.Playlists()) }, haptic = false)
                    if (actions.downloads != null) com.cosmotune.ui.orbit.OrbitSelectChip("Downloads · ${kept.size}", false, fonts, look, onClick = { WatchNav.go(WatchTab.DOWNLOADS) }, haptic = false)
                }
            }
            if (list.isNotEmpty()) item(key = "list:tools", span = { GridItemSpan(maxLineSpan) }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (selecting) {
                        WatchCardChip(if (selected.isEmpty()) "Remove" else "Remove ${selected.size}", look.ink, fonts, enabled = selected.isNotEmpty(), icon = CosmoIcons.Close, onClick = {
                            val gone = list.filter { it.key.value in selected }.map { it.summary }
                            gone.forEach { actions.setInMyList(it, false) }
                            selected.clear(); selecting = false
                            // "Removed · Undo" for 10 s: Undo puts the titles back on the list.
                            val putBack = { gone.forEach { actions.setInMyList(it, true) } }
                            if (gone.size == 1) undo?.removed(gone.single().name, putBack) else undo?.show("Removed ${gone.size} from My list", putBack)
                        })
                        Spacer(Modifier.weight(1f))
                        WatchCardChip("Done", look.ink, fonts, onClick = { selected.clear(); selecting = false })
                    } else {
                        Box {
                            WatchCardChip(sort.label, look.ink, fonts, onClick = { sortMenu = true }, icon = CosmoIcons.Sort)
                            com.cosmotune.ui.orbit.OrbitMenu(sortMenu, { sortMenu = false }, fonts = fonts, look = look, header = "Sort", items =
                                MyListSort.entries.map { s -> com.cosmotune.ui.orbit.OrbitMenuItem(s.label, selected = s == sort) { sort = s } })
                        }
                        Spacer(Modifier.weight(1f))
                        WatchCardChip("Select", look.ink, fonts, onClick = { selecting = true }, icon = CosmoIcons.Check)
                    }
                }
            }
            if (list.isEmpty()) item(key = "list:empty", span = { GridItemSpan(maxLineSpan) }) {
                WatchMessage("My list", "Nothing kept yet", "Use My list on a film or a series to keep it here, on every device you sign in on.", fonts, look,
                    action = "Browse films", onAction = { WatchNav.go(WatchTab.FILMS) })
            }
            itemsIndexed(shown, key = { _, e -> e.key.value }) { _, entry ->
                val s = entry.summary
                val progress = watched[s.key]
                val line = progress?.takeIf { !it.completed }?.let { leftText(it.remainingMs) } ?: posterLine(s)
                val picked = entry.key.value in selected
                Box {
                    WatchCard(s.name, line, s.posterUrl ?: s.backdropUrl, s.key.value, fonts, look, width = null,
                        onClick = { if (selecting) { if (picked) selected.remove(entry.key.value) else selected.add(entry.key.value) } else actions.open(s) },
                        onPlay = if (selecting) null else ({ actions.play(s) }), progress = progress?.fraction?.takeIf { it > 0f && it < 1f })
                    if (selecting) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(26.dp).clip(androidx.compose.foundation.shape.CircleShape)
                        .background(if (picked) look.accent else Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                        if (picked) androidx.compose.material3.Icon(CosmoIcons.Check, "Selected", tint = look.ground, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

/** What can be downloaded, said on the Downloads page and wherever a copy cannot be. */
internal const val WATCH_DOWNLOAD_RULE = "Direct files only: MP4, MKV and WebM from your servers, your add-ons and the free libraries. Live channels and HLS or DASH streams can't be downloaded."

/**
 * Downloads: the films and episodes kept on this device, newest first, with how far each has come; a finished one plays from
 * the file (no network), a stopped one starts again, and any can be removed with its file. Where new downloads are saved is set
 * in Watch settings.
 */
@Composable
internal fun WatchDownloadsPage(actions: WatchActions, fonts: OrbitFonts, look: OrbitLook, frame: WatchFrame) {
    val side = frame.side
    val downloads = actions.downloads
    val list = downloads?.items?.collectAsState()?.value.orEmpty()
    val profile by actions.repository.activeProfile.collectAsState()
    val batches = downloads?.batches?.items?.collectAsState()?.value.orEmpty().filter { it.profileId == profile }
    Box(Modifier.fillMaxSize()) {
        WatchWash(Color(0xFF4E3C12), look)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = frame.padding) {
            frame.header?.let { header -> item(key = "dl:header") { header() } }
            item(key = "dl:heading") {
                val done = list.count { it.status == com.cosmotune.watch.WatchDownload.Status.DONE }
                OrbitPageHeading("Downloads", if (list.isEmpty()) "On this device" else "On this device · $done kept", fonts, look, Modifier.padding(start = side + 6.dp, end = side, top = if (frame.wide) 24.dp else 18.dp))
            }
            if (downloads != null) itemsIndexed(batches, key = { _, batch -> "batch:" + batch.id }) { _, batch ->
                Column(Modifier.padding(horizontal = side + 6.dp)) { WatchBatchRow(batch, downloads, fonts, look) }
            }
            if (downloads == null || (list.isEmpty() && batches.isEmpty())) item(key = "dl:empty") {
                WatchMessage("Downloads", "Nothing downloaded",
                    if (downloads == null) "Downloading is not available on this device." else "Use Download on a film's player or its page to keep it here and watch it offline. $WATCH_DOWNLOAD_RULE",
                    fonts, look, Modifier.padding(horizontal = side).padding(top = 22.dp))
            } else {
                item(key = "dl:rule") {
                    androidx.compose.material3.Text(WATCH_DOWNLOAD_RULE, style = fonts.body.copy(fontSize = 12.5.sp), color = look.dim,
                        modifier = Modifier.padding(horizontal = side + 6.dp).padding(top = 14.dp))
                }
                itemsIndexed(list, key = { _, d -> "dl:" + d.id }) { _, d ->
                    DownloadRow(d, downloads, actions, fonts, look, Modifier.padding(horizontal = side).padding(top = 10.dp))
                }
            }
            item(key = "dl:end") { Spacer(Modifier.height(40.dp)) }
        }
    }
}

@Composable
private fun DownloadRow(d: com.cosmotune.watch.WatchDownload, downloads: com.cosmotune.watch.WatchDownloads, actions: WatchActions, fonts: OrbitFonts, look: OrbitLook, modifier: Modifier) {
    val done = d.status == com.cosmotune.watch.WatchDownload.Status.DONE
    val play = { d.target?.let { t -> WatchNav.open(WatchRoute.Play(t, d.summary, downloads.streamOf(d))) }; Unit }
    com.cosmotune.ui.orbit.WatchRowFrame(look, modifier, height = 84.dp, onClick = if (done) play else null) {
        com.cosmotune.ui.orbit.WatchPicture(d.summary?.backdropUrl ?: d.summary?.posterUrl, d.key.value, Modifier.padding(start = 4.dp).size(96.dp, 56.dp), radius = 14.dp)
        Column(Modifier.weight(1f)) {
            androidx.compose.material3.Text(d.title, style = fonts.title, color = look.ink, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            val status = when (d.status) {
                com.cosmotune.watch.WatchDownload.Status.DONE -> listOfNotNull(d.line, sizeText(d.totalBytes ?: d.bytes)).joinToString(" · ")
                com.cosmotune.watch.WatchDownload.Status.DOWNLOADING -> "Downloading · " + (d.fraction?.let { "${(it * 100).toInt()}%" } ?: sizeText(d.bytes))
                com.cosmotune.watch.WatchDownload.Status.FAILED -> d.error ?: "Stopped"
                com.cosmotune.watch.WatchDownload.Status.PAUSED -> "Paused · " + (sizeText(d.bytes) ?: "Ready to resume")
            }
            androidx.compose.material3.Text(status, style = fonts.body, color = look.dim, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (d.status == com.cosmotune.watch.WatchDownload.Status.DOWNLOADING) {
                com.cosmotune.ui.orbit.OrbitProgressLine({ d.fraction ?: 0f }, look.accent, Modifier.padding(top = 6.dp, end = 12.dp))
            }
        }
        when (d.status) {
            com.cosmotune.watch.WatchDownload.Status.DONE -> com.cosmotune.ui.orbit.WatchCardChip("Play", look.ink, fonts, onClick = play, icon = com.cosmotune.ui.components.CosmoIcons.Play)
            com.cosmotune.watch.WatchDownload.Status.FAILED, com.cosmotune.watch.WatchDownload.Status.PAUSED -> com.cosmotune.ui.orbit.WatchCardChip("Resume", look.ink, fonts, onClick = {
                if (!downloads.retry(d.id)) d.summary?.let { actions.open(it) }
            })
            com.cosmotune.watch.WatchDownload.Status.DOWNLOADING -> com.cosmotune.ui.orbit.WatchCardChip("Pause", look.ink, fonts, onClick = { actions.launch { downloads.pause(d.id) } })
        }
        com.cosmotune.ui.orbit.WatchRoundButton(com.cosmotune.ui.components.CosmoIcons.Close, if (done) "Remove ${d.title}" else "Stop ${d.title}", look, onClick = { downloads.remove(d.id) }, size = 36.dp)
    }
}

/** "1.4 GB", "820 MB". */
internal fun sizeText(bytes: Long?): String? = when {
    bytes == null || bytes <= 0 -> null
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1e9)
    else -> "${(bytes / 1_000_000).coerceAtLeast(1)} MB"
}
