package com.cosmotune.ui.listening

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.cosmotune.listening.*
import com.cosmotune.remote.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun ColumnScope.DownloadUpdatesPage(state: ListeningState, host: ListeningToolsHost,
    updates: PlaylistDownloadUpdates?, busy: Boolean, run: (suspend () -> String?) -> Unit) {
    val watches = updates?.watches?.collectAsState()?.value.orEmpty()
    val enqueue = host.queuePlaylistAdditions
    LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("listening:download-updates"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("Downloaded playlist updates", style = MaterialTheme.typography.headlineSmall)
            Text("Choose a linked playlist to download future additions. " +
                (if (host.backgroundPlaylistUpdates) "Android schedules checks about every 12 hours, including after the app closes, with your download network rules. " else "Checks run every 12 hours while Cosmotune is open. ") +
                "Nothing is removed; tracks you deleted stay deleted. Batches over 50 additions wait here for approval.")
            if (updates == null) Text("Update storage could not be opened. Its original data was kept.")
            if (enqueue == null) Text("Downloading additions is unavailable in this window.")
            if (updates != null && enqueue != null) ToolButton("Check now", enabled = !busy, onClick = { run {
                updates.check(host.fetchPlaylist, enqueue, force = true); "Playlist checks finished. Review any large batches below."
            } })
        }
        if (state.linkedPlaylists.isEmpty()) item { Text("Link a playlist in Linked playlists first.") }
        items(state.linkedPlaylists, key = { it.id }) { playlist ->
            val watch = watches.firstOrNull { it.id == playlist.id && it.enabled }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(playlist.name, style = MaterialTheme.typography.titleMedium)
                Text(if (watch == null) "Automatic additions are off" else "Automatic additions are on · ${watch.seen.size} recording IDs remembered")
                ToolButton(if (watch == null) "Download future additions" else "Stop automatic additions", enabled = !busy && updates != null && enqueue != null,
                    onClick = { run { withContext(Dispatchers.IO) {
                        if (watch == null) updates?.follow(playlist) else updates?.unfollow(playlist.id)
                    }; "Playlist update preference saved" } })
                watch?.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (watch?.pending?.isNotEmpty() == true && enqueue != null && updates != null) {
                    Text("Review ${watch.pending.size} new downloads", style = MaterialTheme.typography.titleSmall)
                    watch.pending.take(20).forEach { Text("${it.title} · ${it.artist}", style = MaterialTheme.typography.bodySmall) }
                    ToolButton("Approve ${watch.pending.size} downloads", enabled = !busy, onClick = { run {
                        updates.approve(watch, enqueue); "Approved additions were queued"
                    } })
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun ColumnScope.DeviceSyncPage(host: ListeningToolsHost, store: LibrarySyncStore?, busy: Boolean,
    run: (suspend () -> String?) -> Unit) {
    var pairing by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var includeFiles by remember { mutableStateOf(false) }
    var allowRemovals by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<LibrarySyncClient.Preview?>(null) }
    var client by remember { mutableStateOf<LibrarySyncClient?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var confirmFileRemoval by remember { mutableStateOf<String?>(null) }
    var sharing by remember(host.remote?.controller) { mutableStateOf(host.remote?.controller?.librarySync != null) }
    val records = remember(store, revision) { store?.records().orEmpty() }
    LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("listening:device-sync"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("Device sync", style = MaterialTheme.typography.headlineSmall)
            Text("Exchange selected playlists and optional audio files on your local network. Order, edits and removal records are reconciled; account sessions and provider credentials stay on each device. Start Phone remote to show this device's current pairing link.")
            if (store == null) Text("Sync storage could not be opened. Its original journal was kept.")
            if (host.remote != null && store != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Switch(sharing, enabled = !busy, onCheckedChange = { enabled ->
                        sharing = enabled; host.remote.controller.librarySync = store.takeIf { enabled }
                    })
                    Text("Allow paired devices to exchange the shared catalogue and files")
                }
                Text("Sharing remains enabled until switched off or the app closes. Stopping the remote invalidates the pairing link.", style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            Text("Publish from this device", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                host.syncPlaylists.forEach { playlist -> FilterChip(playlist.id in selected,
                    { selected = if (playlist.id in selected) selected - playlist.id else selected + playlist.id },
                    label = { Text(playlist.title) }, enabled = !busy) }
            }
            if (host.syncPlaylists.isEmpty()) Text("Create a local playlist first. Linked/remote playlists can be saved locally from Linked playlists.")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Checkbox(includeFiles, onCheckedChange = { includeFiles = it }, enabled = !busy)
                Text("Include local audio files for the selected playlists (uses extra storage and network data)")
            }
            Text("Private sync storage allows 2 GB per file and 8 GB total, with a 64 MB free-space reserve.", style = MaterialTheme.typography.bodySmall)
            ToolButton("Publish selected playlists", enabled = !busy && selected.isNotEmpty() && store != null, onClick = { run {
                withContext(Dispatchers.IO) {
                    val transferContext = currentCoroutineContext()
                    for (playlist in host.syncPlaylists.filter { it.id in selected }) {
                        val tracks = playlist.tracks.map { track ->
                            val local = host.library.songs.firstOrNull { it.videoId == track.id && (it.localPath != null || it.localUri != null) }
                            if (includeFiles && local != null) {
                                val file = host.libraryAccess.inspect(local)
                                val head = file.open().use { input ->
                                    val bytes = ByteArray(com.cosmotune.audio.DeliveredAudio.PROBE_BYTES)
                                    var count = 0
                                    while (count < bytes.size) { val read = input.read(bytes, count, bytes.size - count); if (read < 0) break; count += read }
                                    bytes.copyOf(count)
                                }
                                require(com.cosmotune.audio.DeliveredAudio.inspect(head)?.audio == true) { "A selected local file was not recognized as supported audio." }
                                val offered = store!!.offerFile("${local.title}.${local.downloadFormat?.lowercase()?.takeIf { it.matches(Regex("[a-z0-9]{2,5}")) } ?: "audio"}", file.open) { transferContext.ensureActive() }
                                track.copy(id = if (track.id.contains("://") || track.id.startsWith('/')) "file:${offered.hash}" else track.id, fileHash = offered.hash)
                            } else track.copy(fileHash = track.fileHash ?: store!!.records().firstOrNull { it.playlist?.id == playlist.id }
                                ?.playlist?.tracks?.firstOrNull { it.id == track.id }?.fileHash)
                        }
                        store!!.publish(playlist.copy(tracks = tracks))
                    }
                }
                preview = null; revision++; "Selected playlists were published to your device's shared catalogue"
            } })
        }
        item {
            OutlinedTextField(pairing, { pairing = it.trim().take(2048); preview = null; client = null },
                label = { Text("Other device's private pairing link") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            ToolButton("Preview exchange", enabled = !busy && pairing.isNotBlank() && store != null, onClick = { run {
                val next = LibrarySyncClient(pairing)
                preview = next.preview(store!!); client = next
                "Exchange preview is ready. Review both directions before continuing."
            } })
            preview?.let { change ->
                Text("${change.incoming.size} incoming changes · ${change.outgoing.size} outgoing changes", style = MaterialTheme.typography.titleSmall)
                (change.incoming + change.outgoing).take(20).forEach { record ->
                    Text(if (record.deleted) "Remove: ${record.id}" else record.playlist?.let { "${it.title} · ${it.tracks.size} tracks" }
                        ?: record.file?.let { "${it.name} · ${it.size / 1024} KB" }.orEmpty(), style = MaterialTheme.typography.bodySmall)
                }
                ToolButton("Synchronize reviewed changes", enabled = !busy && store != null, onClick = { run {
                    val count = client!!.apply(store!!, change, includeFiles)
                    preview = null; revision++
                    "Exchanged changes; $count records changed here. Apply received playlists below to update your library."
                } })
            }
        }
        item {
            Text("Shared catalogue", style = MaterialTheme.typography.titleMedium)
            Text("${records.count { it.playlist != null && !it.deleted }} playlists · ${records.count { it.file != null && !it.deleted }} files · ${records.count { it.deleted }} retained removal records")
            ToolButton("Refresh received changes", enabled = !busy, onClick = { revision++ })
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Checkbox(allowRemovals, { allowRemovals = it }, enabled = !busy)
                Text("Apply reviewed playlist removals too. Audio files are kept.")
            }
            if (allowRemovals) records.filter { it.deleted && it.id.startsWith("playlist:") }.take(20).forEach { Text("Remove ${it.id}", color = MaterialTheme.colorScheme.error) }
            ToolButton("Apply shared playlists to this library", enabled = !busy && store != null && host.applySyncedPlaylists != null,
                onClick = { run {
                    val applying = store!!.records().filter { !it.deleted || allowRemovals }
                    val files = withContext(Dispatchers.IO) { applying.mapNotNull { it.playlist }.flatMap { it.tracks }.mapNotNull { it.fileHash }.distinct()
                        .mapNotNull { hash -> store.file(hash)?.let { hash to it } }.toMap() }
                    val alreadyLocal = withContext(Dispatchers.IO) { host.library.songs.filter { song ->
                        (song.localPath != null || song.localUri != null) && host.available(ListeningTrack.of(song))
                    }.mapTo(HashSet()) { it.videoId } }
                    require(applying.mapNotNull { it.playlist }.flatMap { it.tracks }.none { track ->
                        (track.id.startsWith("file:") || track.id.startsWith("local:")) && track.id !in alreadyLocal && (track.fileHash == null || files[track.fileHash] == null)
                    }) { "Transfer local audio files before applying playlists that need them." }
                    host.applySyncedPlaylists!!(applying) { hash -> files[hash] }
                    withContext(Dispatchers.IO) { store.bindReceived(applying) }
                    "Shared playlists and order applied to this device. Audio files were kept."
                } })
        }
        items(records.filter { it.playlist != null && !it.deleted }, key = { it.id }) { record ->
            val playlist = requireNotNull(record.playlist)
            Text("${playlist.title} · ${playlist.tracks.size} tracks", style = MaterialTheme.typography.titleSmall)
            ToolButton("Remove from shared catalogue", enabled = !busy && store != null, onClick = { run {
                withContext(Dispatchers.IO) { store!!.remove(record.id) }; revision++
                "Removal recorded for the next exchange. Files and the local playlist were kept."
            } })
        }
        items(records.filter { it.file != null && !it.deleted }, key = { it.id }) { record ->
            val file = requireNotNull(record.file)
            Text("${file.name} · ${file.size / 1024} KB", style = MaterialTheme.typography.bodySmall)
            if (confirmFileRemoval == file.hash) Text("This removes the private sync copy. Imported playlists using it will need another transfer.", color = MaterialTheme.colorScheme.error)
            ToolButton(if (confirmFileRemoval == file.hash) "Confirm remove local shared copy" else "Remove local shared copy", enabled = !busy && store != null,
                onClick = {
                    if (confirmFileRemoval != file.hash) confirmFileRemoval = file.hash
                    else run { withContext(Dispatchers.IO) { store!!.deleteLocalCopy(file.hash) }; confirmFileRemoval = null; revision++; "Local shared copy removed; the source file and catalogue were kept." }
                })
        }
    }
}
