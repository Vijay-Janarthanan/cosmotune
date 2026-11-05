package com.cosmotune.ui.listening

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cosmotune.listening.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun MusicPlaylistImportPanel(host: ListeningToolsHost, busy: Boolean, run: (suspend () -> String?) -> Unit) {
    var preview by remember { mutableStateOf<MusicPlaylistPreview?>(null) }
    var title by remember { mutableStateOf("") }
    var excluded by remember { mutableStateOf(emptySet<Int>()) }
    var saved by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Import a music playlist", style = MaterialTheme.typography.titleLarge)
        Text("Choose a UTF-8 M3U/M3U8, CSV or text file. Existing library files and YouTube music links can be imported directly. CSV titles with an artist can be matched when you choose. Review the results before saving; track order and repeats are kept.", style = MaterialTheme.typography.bodySmall)
        ToolButton("Choose playlist file", enabled = !busy, tag = "listening:playlist:import", onClick = { run {
            val file = host.importPlaylistFile() ?: return@run null
            val loaded = withContext(Dispatchers.IO) {
                MusicPlaylistImport.preview(file, host.library.songs) { host.available(ListeningTrack.of(it)) }
            }
            preview = loaded; title = loaded.name; excluded = emptySet(); saved = false
            "Review ${loaded.rows.size} playlist entries before saving."
        } })
        preview?.let { current ->
            OutlinedTextField(title, { title = it.take(80) }, enabled = !busy && !saved, label = { Text("Playlist name") },
                singleLine = true, modifier = Modifier.fillMaxWidth().testTag("listening:playlist:name"))
            val chosen = current.rows.filter { it.number !in excluded }.mapNotNull { it.song }
            Text("${chosen.size} ready · ${current.pending} need matching · ${current.skipped} skipped",
                style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("listening:playlist:status"))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp).testTag("listening:playlist:preview"),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(current.rows, key = { it.number }) { row ->
                    Row(Modifier.fillMaxWidth().testTag("listening:playlist:row:${row.number}"), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = row.song != null && row.number !in excluded, enabled = row.song != null && !busy && !saved,
                            onCheckedChange = { included -> excluded = if (included) excluded - row.number else excluded + row.number },
                            modifier = Modifier.testTag("listening:playlist:include:${row.number}")
                                .semantics { contentDescription = "Include track ${row.number}" })
                        Column(Modifier.weight(1f)) {
                            Text("${row.number}. ${row.song?.title ?: row.title}", style = MaterialTheme.typography.bodyMedium)
                            val credit = row.song?.artist ?: row.artist
                            if (credit.isNotBlank()) Text(credit, style = MaterialTheme.typography.bodySmall)
                            (row.song?.albumName ?: row.album)?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            Text(row.detail, style = MaterialTheme.typography.labelSmall,
                                color = if (row.song == null && !row.canSearch) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (current.pending > 0) ToolButton("Match next ${minOf(current.pending, 30)} titles", enabled = !busy && !saved,
                tag = "listening:playlist:match", onClick = { run {
                    val matched = withContext(Dispatchers.IO) { MusicPlaylistImport.match(current, host.matchPlaylistTrack, limit = 30) }
                    preview = matched
                    "${matched.songs.size} tracks ready. ${matched.pending} still need matching."
                } })
            ToolButton(if (saved) "Playlist saved" else "Save ${chosen.size} tracks as playlist",
                enabled = !busy && !saved && title.isNotBlank() && chosen.isNotEmpty(), tag = "listening:playlist:save", onClick = { run {
                    check(withContext(Dispatchers.IO) { chosen.all { host.available(ListeningTrack.of(it)) } }) {
                        "A library file became unavailable. Reload the playlist preview before saving."
                    }
                    host.createPlaylist(title.trim(), chosen)
                    saved = true
                    "Playlist saved with ${chosen.size} tracks. Order and repeats were preserved."
                } })
            Text("Unknown file locations, unsupported links and unverified rows are skipped. Playlist URLs are never fetched as documents; local paths must already belong to your music library.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
