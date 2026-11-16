package com.cosmotune.ui.watch

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.orbit.*
import com.cosmotune.watch.*
import com.cosmotune.watch.model.Episode
import com.cosmotune.watch.model.Title
import com.cosmotune.watch.model.WatchKey
import kotlinx.coroutines.launch

@Composable
internal fun WatchBatchSheet(title: Title, season: Int, episodes: List<Episode>, actions: WatchActions, fonts: OrbitFonts, look: OrbitLook, dismiss: () -> Unit) {
    val downloads = actions.downloads ?: return
    val scope = rememberCoroutineScope()
    var selection by remember { mutableStateOf(WatchBatchSelection.SEASON) }
    var maxHeight by remember { mutableStateOf<Int?>(null) }
    var selected by remember { mutableStateOf(episodes.map { it.key }.toSet()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    WatchDialog("Downloads", "Keep episodes on this device", dismiss) {
        Column(Modifier.testTag("watch:batch:sheet"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Direct MP4, MKV and WebM files only. Jobs continue when you leave this page and resume when Cosmotune next opens.", style = fonts.body, color = look.dim)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(WatchBatchSelection.SEASON to "Season $season", WatchBatchSelection.UNWATCHED to "Unwatched", WatchBatchSelection.ALL to "All seasons", WatchBatchSelection.CUSTOM to "Choose episodes").forEach { (value, label) ->
                    OrbitSelectChip(label, selection == value, fonts, look, onClick = { selection = value }, haptic = false)
                }
            }
            OrbitLabel("Maximum quality", fonts, look.dim)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf<Int?>(null, 480, 720, 1080, 2160).forEach { quality ->
                    OrbitSelectChip(quality?.let { "${it}p" } ?: "Best available", maxHeight == quality, fonts, look, onClick = { maxHeight = quality }, haptic = false)
                }
            }
            if (selection == WatchBatchSelection.CUSTOM) {
                Text("Choose from season $season", style = fonts.body, color = look.dim)
                episodes.forEach { episode ->
                    OrbitSelectChip("E${episode.number} · ${episode.name}", episode.key in selected, fonts, look,
                        onClick = { selected = if (episode.key in selected) selected - episode.key else selected + episode.key }, haptic = false)
                }
            }
            Text("Unwatched and All seasons include regular seasons; choose Specials from its season page. Existing files and queued episodes are skipped. A quality limit requires a known resolution.", style = fonts.body, color = look.dim)
            error?.let { Text(it, style = fonts.body, color = look.dim) }
            com.cosmotune.ui.sheets.CosmoDialogActions(confirmLabel = if (busy) "Adding…" else "Add downloads", confirmEnabled = !busy && (selection != WatchBatchSelection.CUSTOM || selected.isNotEmpty()),
                onConfirm = {
                    busy = true
                    scope.launch {
                        try {
                            downloads.bindRepository(actions.repository)
                            downloads.batches.enqueue(title, selection, season, selected, maxHeight)
                            dismiss()
                            WatchNav.go(WatchTab.DOWNLOADS)
                        } catch (e: Exception) { error = e.message ?: "Could not add downloads."; busy = false }
                    }
                }, dismissLabel = "Cancel", onDismiss = dismiss)
        }
    }
}

@Composable
internal fun WatchBatchRow(batch: WatchBatchDownload, downloads: WatchDownloads, fonts: OrbitFonts, look: OrbitLook) {
    Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(batch.summary.name, style = fonts.title, color = look.ink)
        Text("${batch.completed} / ${batch.episodes.size} episodes · ${batch.status.name.lowercase().replaceFirstChar { it.uppercase() }}", style = fonts.body, color = look.dim)
        batch.episodes.firstOrNull { it.status == WatchBatchEpisodeStatus.FAILED }?.let {
            Text("S${it.target.season} E${it.target.episode} · ${it.error.orEmpty()}", style = fonts.body, color = look.dim)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (batch.status) {
                WatchBatchStatus.QUEUED, WatchBatchStatus.RUNNING -> WatchCardChip("Pause batch", look.ink, fonts, onClick = { downloads.batches.pause(batch.id) })
                WatchBatchStatus.PAUSED -> WatchCardChip("Resume batch", look.ink, fonts, onClick = { downloads.batches.resume(batch.id) })
                WatchBatchStatus.FAILED -> WatchCardChip("Retry failed episodes", look.ink, fonts, onClick = { downloads.batches.resume(batch.id) })
                WatchBatchStatus.DONE -> Unit
            }
            WatchCardChip("Dismiss batch", look.ink, fonts, onClick = { downloads.batches.remove(batch.id) })
        }
    }
}
