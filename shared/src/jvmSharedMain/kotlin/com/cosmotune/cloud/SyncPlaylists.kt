package com.cosmotune.cloud

import com.cosmotune.music.data.model.Song
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * A playlist as it travels between devices: its title and its songs' identity and display fields, and nothing tied to one device
 * (no file paths, no local URIs, no resolved stream). The id of the entry is the playlist id with any `synced:` prefix removed,
 * so the same playlist keeps one id on every platform.
 */
object SyncPlaylists {
    const val KIND = "playlists"

    fun entryId(playlistId: String): String = playlistId.removePrefix("synced:")

    fun data(title: String, songs: List<Song>): JsonObject = buildJsonObject {
        put("title", title)
        put("songs", JsonArray(songs.map(::songData)))
    }

    fun title(data: JsonObject): String = (data["title"] as? JsonPrimitive)?.contentOrNull.orEmpty()

    fun songs(data: JsonObject): List<Song> = (data["songs"] as? JsonArray).orEmpty().mapNotNull { runCatching { song(it.jsonObject) }.getOrNull() }

    private fun songData(song: Song): JsonObject = buildJsonObject {
        put("videoId", song.videoId)
        put("title", song.title)
        put("artist", song.artist)
        song.thumbnailUrl?.let { put("thumbnailUrl", it) }
        song.durationText?.let { put("durationText", it) }
        song.artistId?.let { put("artistId", it) }
        song.albumId?.let { put("albumId", it) }
        song.albumName?.let { put("albumName", it) }
        if (song.isVideo) put("isVideo", true)
    }

    private fun song(o: JsonObject): Song {
        fun text(name: String) = (o[name] as? JsonPrimitive)?.contentOrNull
        return Song(
            videoId = text("videoId") ?: error("no videoId"),
            title = text("title").orEmpty(),
            artist = text("artist").orEmpty(),
            thumbnailUrl = text("thumbnailUrl"),
            durationText = text("durationText"),
            artistId = text("artistId"),
            albumId = text("albumId"),
            albumName = text("albumName"),
            isVideo = (o["isVideo"] as? JsonPrimitive)?.booleanOrNull ?: false,
        )
    }
}
