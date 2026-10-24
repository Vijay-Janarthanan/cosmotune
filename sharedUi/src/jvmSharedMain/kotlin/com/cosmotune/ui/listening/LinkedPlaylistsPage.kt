package com.cosmotune.ui.listening

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cosmotune.listening.*
import com.cosmotune.music.data.YtMusicRepository
import com.cosmotune.ui.components.CosmoButtonKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** The public YouTube Music playlist reader both hosts share; rows the parser drops are not invented. */
suspend fun fetchYouTubePlaylist(remoteId: String): RemotePlaylist = withTimeout(60_000) {
    val songs = YtMusicRepository.allSongs("VL$remoteId").getOrThrow()
    RemotePlaylist("", songs.map { RemoteTrack(it) })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ColumnScope.LinkedPlaylistsPage(state: ListeningState, host: ListeningToolsHost, store: ListeningStore, busy: Boolean, run: (suspend () -> String?) -> Unit, close: () -> Unit) {
    var link by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<LinkedSyncPreview?>(null) }
    var confirmUnlink by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("listening:linked:list"), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Text("Keep a playlist in step with its source", style = MaterialTheme.typography.headlineSmall)
            Text("Link a public YouTube Music playlist. Checking for changes shows what the source added or removed before anything is saved. Your removals, additions and order are kept. Tracks the source drops stay as greyed placeholders with their likes and history, and return if the source restores them.")
            OutlinedTextField(link, { link = it.take(300) }, label = { Text("Playlist link or ID") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("listening:linked:link"))
            OutlinedTextField(name, { name = it.take(80) }, label = { Text("Name on this device") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("listening:linked:name"))
            ToolButton("Link playlist", enabled = !busy && link.isNotBlank(), tag = "listening:linked:add", onClick = { run {
                val id = youtubePlaylistId(link) ?: error("Enter a playlist link or ID.")
                val remote = host.fetchPlaylist("youtube", id)
                if (remote.tracks.isEmpty()) error("That playlist has no playable tracks.")
                val created = newLinkedPlaylist("youtube", id, remote.copy(title = name.ifBlank { remote.title.ifBlank { "Linked playlist" } }))
                withContext(Dispatchers.IO) { store.saveLinked(created) }
                link = ""; name = ""
                "Linked ${created.entries.size} tracks"
            } })
        }
        if (state.linkedPlaylists.isEmpty()) item { Text("No linked playlists yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(state.linkedPlaylists, key = { it.id }) { playlist ->
            val ghosts = playlist.entries.withIndex().filter { it.value.ghost }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(playlist.name, style = MaterialTheme.typography.titleMedium)
                Text("${playlist.entries.size - ghosts.size} playable · ${ghosts.size} placeholders", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("listening:linked:counts"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolButton("Check for changes", enabled = !busy, tag = "listening:linked:check", onClick = { run {
                        val remote = host.fetchPlaylist(playlist.source, playlist.remoteId)
                        val next = withContext(Dispatchers.Default) { previewLinkedSync(playlist, remote) }
                        preview = next.takeIf { it.changed }
                        if (!next.changed) "Already up to date" else null
                    } })
                    ToolButton("Play", tag = "listening:linked:play", enabled = !busy && ghosts.size < playlist.entries.size, onClick = { run {
                        // Library first: local copies of the exact recordings play from this device; the rest stream.
                        val current = host
                        val resolved = withContext(Dispatchers.IO) { resolveLibraryFirst(playlist.playable(), current.library.songs) { current.available(ListeningTrack.of(it)) } }
                        current.play(resolved.map { it.play })
                        "Playing: " + resolved.summary()
                    } })
                    ToolButton("Save as playlist", kind = CosmoButtonKind.TONAL, enabled = !busy && ghosts.size < playlist.entries.size, onClick = { run {
                        host.createPlaylist(playlist.name, playlist.playable()); "Playlist saved to your library"
                    } })
                    ToolButton(if (confirmUnlink == playlist.id) "Confirm unlink" else "Unlink", kind = CosmoButtonKind.TEXT, enabled = !busy, onClick = {
                        if (confirmUnlink != playlist.id) confirmUnlink = playlist.id
                        else run { withContext(Dispatchers.IO) { store.unlink(playlist.id) }; confirmUnlink = null; "Playlist unlinked. Saved library playlists are unchanged." }
                    })
                }
                preview?.takeIf { it.playlistId == playlist.id }?.let { change ->
                    Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.padding(12.dp).testTag("listening:linked:preview"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${change.added.size} new · ${change.ghosted.size} become placeholders · ${change.restored.size} restored · ${change.keptLocalRemovals.size} of your removals kept")
                            if (change.suspiciousShrink) Text("The source returned far fewer tracks than last time. It may be incomplete; check before applying.", color = MaterialTheme.colorScheme.error)
                            change.added.take(20).forEach { Text("+ ${it.title} · ${it.artist}", style = MaterialTheme.typography.bodySmall) }
                            change.ghosted.take(20).forEach { Text("○ ${it.title} · ${it.artist}", style = MaterialTheme.typography.bodySmall) }
                            change.restored.take(20).forEach { Text("↺ ${it.title} · ${it.artist}", style = MaterialTheme.typography.bodySmall) }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ToolButton("Apply changes", enabled = !busy, tag = "listening:linked:apply", onClick = { run {
                                    withContext(Dispatchers.IO) { store.update { applyLinkedSync(it, change) } }
                                    preview = null; "Linked playlist updated"
                                } })
                                TextButton(onClick = { preview = null }, enabled = !busy) { Text("Discard") }
                            }
                        }
                    }
                }
                if (ghosts.isNotEmpty()) {
                    ghosts.take(50).forEach { (index, entry) ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${entry.track.title} · ${entry.track.artist}\n" + if (entry.ghostReason == GhostReason.UNAVAILABLE) "Unavailable from the source" else "Removed from the source",
                                Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            TextButton(enabled = !busy, onClick = { run {
                                withContext(Dispatchers.IO) { store.update { s -> s.copy(linkedPlaylists = s.linkedPlaylists.map { if (it.id == playlist.id && it.entries.getOrNull(index) == entry) it.removeEntry(index) else it }) } }
                                null
                            } }) { Text("Remove") }
                        }
                    }
                    ToolButton("Remove all placeholders", kind = CosmoButtonKind.TEXT, enabled = !busy, tag = "listening:linked:drop-ghosts", onClick = { run {
                        withContext(Dispatchers.IO) { store.update { s -> s.copy(linkedPlaylists = s.linkedPlaylists.map { if (it.id == playlist.id) it.dropGhosts() else it }) } }
                        "Placeholders removed. A later sync will not bring them back unless the source restores them."
                    } })
                }
                HorizontalDivider()
            }
        }
    }
}
