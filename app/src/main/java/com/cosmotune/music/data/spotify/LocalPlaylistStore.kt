package com.cosmotune.music.data.spotify

import android.content.Context
import android.content.SharedPreferences
import com.cosmotune.music.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class LocalPlaylist(
    val id: String,
    val title: String,
    val songs: List<Song>,
) {
    val browseId: String get() = "local:playlist:$id"
}

object LocalPlaylistStore {
    private const val PREF_NAME = "cosmotune_local_playlists"
    private const val KEY_PLAYLISTS = "playlists_json"

    private lateinit var prefs: SharedPreferences

    private val _playlists = MutableStateFlow<List<LocalPlaylist>>(emptyList())
    val playlists = _playlists.asStateFlow()

    @Synchronized fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        load()
    }

    private fun load() {
        if (!this::prefs.isInitialized) return
        val raw = prefs.getString(KEY_PLAYLISTS, null) ?: return
        runCatching {
            val list = mutableListOf<LocalPlaylist>()
            val jsonArray = JSONArray(raw)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val id = obj.getString("id")
                val title = obj.getString("title")
                val songsArray = obj.getJSONArray("songs")
                val songs = mutableListOf<Song>()
                for (j in 0 until songsArray.length()) {
                    val sObj = songsArray.getJSONObject(j)
                    val song = Song(
                        videoId = sObj.optString("videoId", ""),
                        title = sObj.optString("title", ""),
                        artist = sObj.optString("artist", ""),
                        thumbnailUrl = sObj.optString("thumbnailUrl", null).takeIf { !it.isNullOrBlank() && it != "null" },
                        durationText = sObj.optString("durationText", null).takeIf { !it.isNullOrBlank() && it != "null" },
                        artistId = sObj.optString("artistId", null).takeIf { !it.isNullOrBlank() && it != "null" },
                        albumId = sObj.optString("albumId", null).takeIf { !it.isNullOrBlank() && it != "null" },
                        albumName = sObj.optString("albumName", null).takeIf { !it.isNullOrBlank() && it != "null" },
                        isVideo = sObj.optBoolean("isVideo", false),
                        isVideoOrigin = sObj.optBoolean("isVideoOrigin", sObj.optBoolean("isVideo", false)),
                        localUri = sObj.optString("localUri", "").takeIf { it.isNotBlank() && it != "null" },
                        localPath = sObj.optString("localPath", "").takeIf { it.isNotBlank() && it != "null" },
                        localDateAddedSeconds = sObj.optLong("localDateAddedSeconds", 0L).takeIf { it > 0L },
                    )
                    songs.add(song)
                }
                list.add(LocalPlaylist(id, title, songs))
            }
            _playlists.value = list
        }
    }

    @Synchronized fun savePlaylist(title: String, songs: List<Song>): LocalPlaylist {
        val id = "sp_local_" + java.util.UUID.randomUUID()
        val playlist = LocalPlaylist(id = id, title = title, songs = songs)
        val updated = listOf(playlist) + _playlists.value.filterNot { it.id == id }
        _playlists.value = updated
        persist(updated)
        return playlist
    }

    @Synchronized fun deletePlaylist(id: String) {
        val updated = _playlists.value.filterNot { it.id == id || it.browseId == id || it.id == id.removePrefix("local:playlist:") }
        _playlists.value = updated
        persist(updated)
    }

    @Synchronized fun renamePlaylist(id: String, newTitle: String) {
        val cleanId = id.removePrefix("local:playlist:").removePrefix("VL")
        val updated = _playlists.value.map {
            if (it.id == cleanId || it.id == id || it.browseId == id) {
                it.copy(title = newTitle)
            } else it
        }
        _playlists.value = updated
        persist(updated)
    }

    fun getPlaylist(id: String): LocalPlaylist? {
        val cleanId = id.removePrefix("local:playlist:").removePrefix("VL")
        return _playlists.value.firstOrNull { it.id == cleanId || it.id == id || it.browseId == id }
    }

    /** Reviewed peer records update stable local identities; paths and credentials never come from the peer. */
    @Synchronized fun applySyncedPlaylists(records: List<com.cosmotune.remote.SyncRecord>, file: (String) -> java.io.File?) {
        check(this::prefs.isInitialized)
        var updated = _playlists.value
        for (record in records) {
            if (!record.id.startsWith("playlist:")) continue
            val id = record.id.removePrefix("playlist:")
            val existing = updated.firstOrNull { it.id == id || it.id == "synced:$id" }
            if (record.deleted) updated = updated.filterNot { it.id == id || it.id == "synced:$id" }
            else record.playlist?.let { playlist ->
                val known = existing?.songs.orEmpty().associateBy { it.videoId }
                val songs = playlist.tracks.map { track ->
                    val old = known[track.id]
                    val local = track.fileHash?.let(file)
                    (old ?: Song(track.id, track.title, track.artist, null)).copy(title = track.title, artist = track.artist,
                        durationText = track.duration, albumName = track.album, localPath = local?.absolutePath ?: old?.localPath,
                        localUri = local?.toURI()?.toString() ?: old?.localUri)
                }
                val native = LocalPlaylist(existing?.id ?: "synced:$id", playlist.title, songs)
                updated = updated.filterNot { it.id == native.id } + native
            }
        }
        persist(updated)
        _playlists.value = updated
    }

    /**
     * The last list a cloud sync wrote. The sync watches [playlists] for edits to send, and its own write must not read as one.
     * Compared by identity: it is exactly the list the sync put there.
     */
    @Volatile
    var lastCloudWrite: List<LocalPlaylist>? = null
        private set

    /**
     * Serialize cloud pulls with local saves so an incoming edit cannot overwrite a playlist being imported.
     */
    @Synchronized fun applyFromCloud(merge: (List<LocalPlaylist>) -> List<LocalPlaylist>) {
        if (!this::prefs.isInitialized) return
        val updated = merge(_playlists.value)
        lastCloudWrite = updated
        _playlists.value = updated
        persist(updated)
    }

    private fun persist(list: List<LocalPlaylist>) {
        if (!this::prefs.isInitialized) return
        runCatching {
            val jsonArray = JSONArray()
            for (playlist in list) {
                val pObj = JSONObject()
                pObj.put("id", playlist.id)
                pObj.put("title", playlist.title)
                val songsArray = JSONArray()
                for (song in playlist.songs) {
                    val sObj = JSONObject()
                    sObj.put("videoId", song.videoId)
                    sObj.put("title", song.title)
                    sObj.put("artist", song.artist)
                    sObj.put("thumbnailUrl", song.thumbnailUrl)
                    sObj.put("durationText", song.durationText)
                    sObj.put("artistId", song.artistId)
                    sObj.put("albumId", song.albumId)
                    sObj.put("albumName", song.albumName)
                    sObj.put("isVideo", song.isVideo)
                    sObj.put("isVideoOrigin", song.isVideoOrigin)
                    sObj.put("localUri", song.localUri)
                    sObj.put("localPath", song.localPath)
                    sObj.put("localDateAddedSeconds", song.localDateAddedSeconds)
                    songsArray.put(sObj)
                }
                pObj.put("songs", songsArray)
                jsonArray.put(pObj)
            }
            prefs.edit().putString(KEY_PLAYLISTS, jsonArray.toString()).apply()
        }
    }
}
