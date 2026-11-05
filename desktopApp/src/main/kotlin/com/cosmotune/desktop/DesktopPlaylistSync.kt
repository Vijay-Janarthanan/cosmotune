package com.cosmotune.desktop

import com.cosmotune.music.data.model.Song
import com.cosmotune.music.desktop.DesktopPlaylist
import com.cosmotune.remote.SyncPlaylist
import com.cosmotune.remote.SyncRecord
import com.cosmotune.remote.SyncTrack
import java.io.File

/** Portable catalogue data only: device paths, grants and provider credentials never leave this host. */
fun desktopSyncPlaylists(playlists: List<DesktopPlaylist>): List<SyncPlaylist> = playlists.map { playlist ->
    SyncPlaylist(playlist.id.removePrefix("synced:"), playlist.title, playlist.songs.map { song ->
        SyncTrack(song.videoId, song.title, song.artist, song.albumName, song.durationText)
    })
}

/** Apply only the records already reviewed in Device sync, retaining native IDs and local cover metadata. */
fun applyDesktopSyncedPlaylists(current: List<DesktopPlaylist>, records: List<SyncRecord>, files: (String) -> File?): List<DesktopPlaylist> {
    val next = current.toMutableList()
    for (record in records.filter { it.id.startsWith("playlist:") }) {
        val id = record.id.removePrefix("playlist:")
        val index = next.indexOfFirst { it.id.removePrefix("synced:") == id }
        if (record.deleted) {
            if (index >= 0) next.removeAt(index)
            continue
        }
        val portable = requireNotNull(record.playlist)
        require(portable.id == id)
        val previous = next.getOrNull(index)
        val known = previous?.songs.orEmpty().associateBy { it.videoId }
        val songs = portable.tracks.map { track ->
            val old = known[track.id]
            val local = track.fileHash?.let(files)
            require(local == null || local.isAbsolute && local.isFile) { "Download and verify the shared file first." }
            require(!(track.id.startsWith("local:") || track.id.startsWith("file:")) || local != null || old?.localPath?.let { File(it).isFile } == true) {
                "Download the shared local files before importing this playlist."
            }
            (old ?: Song(track.id, track.title, track.artist, null)).copy(title = track.title, artist = track.artist,
                albumName = track.album, durationText = track.duration,
                localPath = local?.absolutePath ?: old?.localPath, localUri = local?.toURI()?.toString() ?: old?.localUri)
        }
        val updated = DesktopPlaylist(previous?.id ?: "synced:$id", portable.title, songs)
        if (index >= 0) next[index] = updated else next += updated
    }
    return next
}
