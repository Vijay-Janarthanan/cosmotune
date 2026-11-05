package com.cosmotune.music.data.cloud

import com.cosmotune.cloud.SyncPlaylists
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.spotify.LocalPlaylist
import kotlinx.serialization.json.JsonObject

/**
 * How the phone's playlists turn into cloud entries and back. Pure, so it is tested on the JVM; [AndroidCloud] does the reading and
 * writing of [com.cosmotune.music.data.spotify.LocalPlaylistStore].
 */
object CloudPlaylists {
    /** Prefix for a playlist first seen through sync, the same one the peer sync and the desktop use. */
    private const val SYNCED_PREFIX = "synced:"

    /** The store as entries: one per playlist, keyed by the id that is the same on every device. */
    fun snapshot(playlists: List<LocalPlaylist>): Map<String, JsonObject> =
        playlists.associate { SyncPlaylists.entryId(it.id) to SyncPlaylists.data(it.title, it.songs) }

    /**
     * [current] with cloud changes written in. A playlist the cloud replaces keeps its id here (so an open page or a pinned shortcut
     * still finds it) and, song by song, what only this device knows about the file behind it; a new one goes first, as a playlist
     * saved on this device would.
     */
    fun merge(current: List<LocalPlaylist>, upserts: Map<String, JsonObject>, deletes: Set<String>): List<LocalPlaylist> {
        val replaced = mutableSetOf<String>()
        val kept = current.filterNot { SyncPlaylists.entryId(it.id) in deletes }.map { playlist ->
            val id = SyncPlaylists.entryId(playlist.id)
            val data = upserts[id] ?: return@map playlist
            replaced += id
            LocalPlaylist(playlist.id, SyncPlaylists.title(data), keepDeviceFields(SyncPlaylists.songs(data), playlist.songs))
        }
        val added = upserts.filterKeys { it !in replaced && it !in deletes }.map { (id, data) ->
            LocalPlaylist(SYNCED_PREFIX + id, SyncPlaylists.title(data), SyncPlaylists.songs(data))
        }
        return added + kept
    }

    /**
     * The synced songs carry no file paths or content URIs (another device's mean nothing here), so a song this device already had in
     * the playlist keeps its own, matched by video id. Without that, a sync would turn every local file in a playlist into a search.
     */
    fun keepDeviceFields(incoming: List<Song>, previous: List<Song>): List<Song> {
        if (previous.isEmpty()) return incoming
        val known = HashMap<String, Song>()
        for (song in previous) known.putIfAbsent(song.videoId, song)
        return incoming.map { song ->
            val old = known[song.videoId] ?: return@map song
            song.copy(
                localUri = old.localUri,
                localPath = old.localPath,
                localDateAddedSeconds = old.localDateAddedSeconds,
                // Which upload a catalogue match came from is decided on this device and not synced either.
                isVideoOrigin = old.isVideoOrigin || song.isVideo,
            )
        }
    }
}
