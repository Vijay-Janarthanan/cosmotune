package com.cosmotune.video.ui.playlist

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.cosmotune.video.library.PlaylistExport
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.ui.actions.ActionToastKind
import com.cosmotune.video.ui.actions.VideoActionsState
import com.cosmotune.video.ui.library.FilePickerLauncher
import com.cosmotune.video.ui.library.rememberFilePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Saves a playlist to a file the listener names (the platform's save dialog), then says whether it was saved. */
internal class PlaylistExporter {
    internal class Job(val kind: PlaylistExport.Kind, val items: suspend () -> List<VideoItem>)

    internal var pending: Job? = null
    internal lateinit var picker: FilePickerLauncher

    fun export(name: String, kind: PlaylistExport.Kind, items: suspend () -> List<VideoItem>) {
        pending = Job(kind, items)
        picker.create(PlaylistExport.fileName(name, kind), kind.mimeType)
    }
}

@Composable
internal fun rememberPlaylistExporter(): PlaylistExporter {
    val scope = rememberCoroutineScope()
    val exporter = remember { PlaylistExporter() }
    exporter.picker = rememberFilePicker(
        onOpened = {},
        onCreated = { file ->
            val job = exporter.pending
            exporter.pending = null
            if (job != null) scope.launch {
                val saved = runCatching {
                    val items = job.items()
                    withContext(Dispatchers.IO) { file.open().use { it.write(PlaylistExport.text(items, job.kind, System.currentTimeMillis()).encodeToByteArray()) } }
                }.isSuccess
                VideoActionsState.shared.showToast(if (saved) ActionToastKind.FILE_SAVED else ActionToastKind.FILE_NOT_SAVED)
            }
        },
    )
    return exporter
}
