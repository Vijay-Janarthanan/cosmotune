package com.cosmotune.listening

import kotlinx.serialization.Serializable
import java.util.UUID

/** Documents are retained as inert text/metadata. Imported paths confer no filesystem grant. */
@Serializable
data class PendingMusicPlaylistRow(val number: Int, val title: String, val artist: String, val album: String? = null,
    val location: String = "", val track: ListeningTrack? = null, val canSearch: Boolean = false, val detail: String = "") {
    fun row() = MusicPlaylistImportRow(number, title, artist, album, track?.song(), canSearch, detail, location)
}

@Serializable
data class PendingMusicPlaylist(val id: String = UUID.randomUUID().toString(), val name: String,
    val rows: List<PendingMusicPlaylistRow>, val savedAt: Long = System.currentTimeMillis()) {
    fun preview() = MusicPlaylistPreview(name, rows.map { it.row() })
    companion object {
        fun of(preview: MusicPlaylistPreview, id: String = UUID.randomUUID().toString()) = PendingMusicPlaylist(id, preview.name,
            preview.rows.map { PendingMusicPlaylistRow(it.number, it.title, it.artist, it.album, it.location,
                it.song?.let(ListeningTrack::of), it.canSearch, it.detail) })
    }
}
