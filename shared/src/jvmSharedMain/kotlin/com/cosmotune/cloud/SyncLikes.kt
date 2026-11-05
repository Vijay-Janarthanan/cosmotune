package com.cosmotune.cloud

import com.cosmotune.music.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * A like as it travels between devices: the entry id is the video id, and the data is what a list of likes needs to draw a row
 * (title, artist, cover), each left out where the device that liked it did not know it. Dislikes do not travel, and neither do
 * ratings of source (third-party provider) tracks: those belong to the device and its provider.
 */
object SyncLikes {
    const val KIND = "likes"

    /**
     * Whether a like of this id means the same on another device: not a file on this one, not a source provider's track, and short
     * enough to be a key in a cloud document.
     */
    fun travels(videoId: String): Boolean =
        videoId.isNotBlank() && videoId.length <= 200 && !videoId.startsWith("local:") && !sourceTrack(videoId)

    /**
     * Whether an id is a source provider's track. The providers are outside this module, so each app sets this at start (to
     * `SourceLibraryStore::ownsTrack`); until then no id counts as one.
     */
    @Volatile var sourceTrack: (String) -> Boolean = { false }

    /** The liked ids that travel as entries, each with what [known] remembers of it (or nothing). */
    fun snapshot(likedIds: Collection<String>, known: Map<String, JsonObject>): Map<String, JsonObject> =
        likedIds.filter(::travels).associateWith { known[it] ?: JsonObject(emptyMap()) }

    /** [likedIds] with cloud changes laid over it. Only ids that travel are touched, so a source rating or a file's like stays as it is. */
    fun applyToIds(likedIds: Set<String>, upserts: Map<String, JsonObject>, deletes: Set<String>): Set<String> =
        likedIds - deletes.filter(::travels).toSet() + upserts.keys.filter(::travels)

    fun data(song: Song): JsonObject = data(song.title, song.artist, song.thumbnailUrl)

    fun data(title: String?, artist: String?, thumbnailUrl: String?): JsonObject = buildJsonObject {
        title?.takeIf { it.isNotBlank() }?.let { put("title", it) }
        artist?.takeIf { it.isNotBlank() }?.let { put("artist", it) }
        thumbnailUrl?.takeIf { it.isNotBlank() }?.let { put("thumbnailUrl", it) }
    }

    fun title(data: JsonObject): String? = text(data, "title")
    fun artist(data: JsonObject): String? = text(data, "artist")
    fun thumbnailUrl(data: JsonObject): String? = text(data, "thumbnailUrl")

    private fun text(data: JsonObject, name: String) = (data[name] as? JsonPrimitive)?.contentOrNull
}

/**
 * Likes kept in a small file as `videoId -> data`. On the phone it is the list of likes made in the app (the YouTube account keeps the
 * rest, and offers no change times); on the desktop it only remembers what a liked song looked like, beside the liked ids it already keeps.
 * What the sync wrote reads back exactly as it came in, so the journal never mistakes it for a local edit.
 */
class LikeFile(private val file: File?) {
    private val json = Json { ignoreUnknownKeys = true }
    private val state = MutableStateFlow(load())
    val likes: StateFlow<Map<String, JsonObject>> = state.asStateFlow()

    /** The map the last cloud write produced, so a watcher can tell the sync's own write from a like made on this device. */
    @Volatile var lastCloudWrite: Map<String, JsonObject>? = null
        private set

    private fun load(): Map<String, JsonObject> = runCatching {
        json.parseToJsonElement(file!!.readText()).jsonObject.mapValues { it.value.jsonObject }
    }.getOrDefault(emptyMap())

    /** A like made here. Liking again with less known about the song keeps what was already known. */
    @Synchronized
    fun like(videoId: String, data: JsonObject) {
        val current = state.value
        val previous = current[videoId]
        if (previous != null && (data.isEmpty() || previous == data)) return
        save(current + (videoId to data))
    }

    @Synchronized
    fun unlike(videoId: String) {
        val current = state.value
        if (videoId in current) save(current - videoId)
    }

    /** Lays cloud changes over the file. Returns the new content. */
    @Synchronized
    fun applyFromCloud(upserts: Map<String, JsonObject>, deletes: Set<String>): Map<String, JsonObject> {
        val next = (state.value - deletes) + upserts
        lastCloudWrite = next
        if (next != state.value) save(next)
        return next
    }

    private fun save(next: Map<String, JsonObject>) {
        state.value = next
        val target = file ?: return
        runCatching {
            target.parentFile?.mkdirs()
            val temporary = File(target.parentFile, target.name + ".tmp")
            temporary.writeText(JsonObject(next).toString())
            if (!temporary.renameTo(target)) { target.delete(); temporary.renameTo(target) }
        }
    }
}
