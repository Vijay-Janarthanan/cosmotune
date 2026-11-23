package com.cosmotune.ui.listening

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cosmotune.listening.PlaylistCsv
import com.cosmotune.listening.PlaylistCsvRow
import com.cosmotune.music.data.model.Song
import com.cosmotune.ui.orbit.OrbitMenuItem
import com.cosmotune.ui.orbit.OrbitMenuPill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Playlists as CSV, for a spreadsheet or another music app: the queue, the liked songs, or one of the playlists on this device
 * (picked from a menu), each saved as Title, Artist, Album, Duration and a YouTube Music link.
 */
@Composable
internal fun PlaylistCsvTools(host: ListeningToolsHost, busy: Boolean, run: (suspend () -> String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    suspend fun save(title: String, rows: List<PlaylistCsvRow>): String? {
        if (rows.isEmpty()) return "$title has no songs to export."
        val text = withContext(Dispatchers.Default) { PlaylistCsv.export(rows) }
        return if (host.exportFile(PlaylistCsv.fileName(title), text)) "Saved $title: ${rows.size} songs" else null
    }
    fun Song.row() = PlaylistCsvRow(title, artist, albumName, durationText, videoId)
    Heading("Playlists as CSV", "Save a list of songs that a spreadsheet or another music app can read: title, artist, album, length and a YouTube Music link.")
    ToolButton("Export queue as CSV", enabled = !busy, kind = ToolKind.QUIET, tag = "listening:csv:queue", onClick = { run {
        save("Queue", host.queue().songs.map { it.row() })
    } })
    ToolButton("Export liked songs as CSV", enabled = !busy, kind = ToolKind.QUIET, tag = "listening:csv:liked", onClick = { run {
        val liked = host.library.songs.filter { it.videoId in host.library.likedIds }
        save("Liked songs", liked.map { it.row() })
    } })
    if (host.syncPlaylists.isNotEmpty()) {
        OrbitMenuPill(
            text = "Export a playlist as CSV",
            open = open,
            onOpen = { open = true },
            onDismiss = { open = false },
            header = "Playlists",
            enabled = !busy,
            modifier = Modifier.padding(top = 6.dp, bottom = 4.dp).testTag("listening:csv:playlist"),
            items = host.syncPlaylists.map { playlist ->
                OrbitMenuItem(playlist.title, detail = "${playlist.tracks.size} songs") {
                    run { save(playlist.title, playlist.tracks.map { PlaylistCsvRow(it.title, it.artist, it.album, it.duration, it.id) }) }
                }
            },
        )
    }
}
