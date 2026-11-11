package com.opencanvas.core.stream

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Remembers measured [SyncOffset]s: in a small in-memory LRU and, when a [directory] is given, as
 * one JSON file per song/video pair (`<trackVideoId>_<videoId>.json`), so a song measured once
 * starts instantly for good. The files use the same shape the community map service serves, which
 * means a cache directory can be shared or published as it is.
 *
 * Only successful measurements are stored: a failed lookup is never cached, so the next resolve
 * retries.
 *
 * @param directory Where the JSON files live, created on first write; null keeps everything in memory.
 * @param capacity Maximum number of in-memory entries; the least recently used one is evicted first.
 */
internal class SyncStore(
    private val directory: File? = null,
    private val capacity: Int = DEFAULT_CAPACITY,
) {

    init {
        require(capacity > 0) { "capacity must be > 0, was $capacity" }
    }

    private val entries = object : LinkedHashMap<String, SyncOffset>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SyncOffset>): Boolean = size > capacity
    }
    private val verified = mutableSetOf<String>()

    /** Number of in-memory entries. */
    val size: Int get() = synchronized(entries) { entries.size }

    /** The stored offset for the pair, from memory or disk, or null. */
    operator fun get(trackVideoId: String, videoId: String): SyncOffset? {
        val key = key(trackVideoId, videoId)
        synchronized(entries) { entries[key] }?.let { return it }
        val file = fileFor(trackVideoId, videoId) ?: return null
        val offset = readFile(file) ?: return null
        synchronized(entries) { entries[key] = offset }
        return offset
    }

    /**
     * Stores [offset] for the pair in memory and, when a directory is set, on disk. [video] is what the video was when it
     * was chosen (its title, channel and whether a publisher stands behind it), kept so the map can be re-checked on load.
     */
    fun put(trackVideoId: String, videoId: String, offset: SyncOffset, verifiedSelection: Boolean = false, video: ChosenVideo? = null) {
        synchronized(entries) {
            entries[key(trackVideoId, videoId)] = offset
            if (verifiedSelection) verified.add(key(trackVideoId, videoId)) else verified.remove(key(trackVideoId, videoId))
        }
        val file = fileFor(trackVideoId, videoId) ?: return
        val temporary = File(file.parentFile, "${file.name}.tmp")
        try {
            file.parentFile?.mkdirs()
            var encoded = encode(trackVideoId, videoId, offset)
            if (video != null) encoded = encoded.dropLast(1) + "," + buildJsonObject {
                put("videoTitle", video.title)
                put("channelTitle", video.channel)
                put("publisher", video.publisher)
            }.toString().drop(1)
            temporary.writeText(if (verifiedSelection) encoded.dropLast(1) + ",\"selectionPolicy\":3}" else encoded)
            if (!temporary.renameTo(file)) {
                file.delete()
                temporary.renameTo(file)
            }
        } catch (_: IOException) {
            // A cache that cannot be written is only a cache that does not help next time.
        } finally {
            temporary.delete()
        }
    }

    /** The music-video id measured for [trackVideoId] before, or null; lets a repeat play skip the search. */
    fun videoFor(trackVideoId: String): String? {
        synchronized(entries) {
            entries.keys.firstOrNull { it.startsWith("$trackVideoId|") }?.let { return it.substringAfter('|') }
        }
        val dir = directory ?: return null
        val prefix = "${trackVideoId}_"
        return dir.listFiles { _, name -> name.startsWith(prefix) && name.endsWith(".json") }
            ?.firstOrNull { readFile(it) != null }
            ?.name?.removePrefix(prefix)?.removeSuffix(".json")
    }

    /** Old timing-only entries and caller-supplied videos cannot become automatic selections. */
    fun verifiedVideoFor(trackVideoId: String): String? {
        synchronized(entries) {
            entries.keys.firstOrNull { it.startsWith("$trackVideoId|") && it in verified }
                ?.let { return it.substringAfter('|') }
        }
        val prefix = "${trackVideoId}_"
        return directory?.listFiles { _, name -> name.startsWith(prefix) && name.endsWith(".json") }
            ?.firstOrNull { file -> runCatching {
                Json.parseToJsonElement(file.readText()).jsonObject["selectionPolicy"]?.jsonPrimitive?.intOrNull == 3 && readFile(file) != null
            }.getOrDefault(false) }?.name?.removePrefix(prefix)?.removeSuffix(".json")
    }

    /**
     * The music video chosen for a song, whichever track id it was measured under: what lets the same song played from
     * another upload or service start with its video instead of searching again. The map itself stays in the
     * `<trackId>_<videoId>.json` file named here; [SongVideo.durationSec] says which length of the song it was measured on.
     */
    internal data class SongVideo(
        val trackVideoId: String,
        val videoId: String,
        val durationSec: Long,
        val videoTitle: String = "",
        val channelTitle: String = "",
        val officialArtist: Boolean = false,
        val verifiedChannel: Boolean = false,
    )

    private val songs = LinkedHashMap<String, SongVideo>()

    /** The verified video for [song] and the track it was measured under, from memory or disk, or null. */
    fun songVideo(song: SongIdentity): SongVideo? {
        synchronized(songs) { songs[song.key] }?.let { return it }
        val file = songFileFor(song) ?: return null
        val found = runCatching {
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            if (root["song"]?.jsonPrimitive?.content != song.key) return@runCatching null
            SongVideo(
                trackVideoId = root["trackId"]!!.jsonPrimitive.content,
                videoId = root["videoId"]!!.jsonPrimitive.content,
                durationSec = root["durationSec"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                videoTitle = root["videoTitle"]?.jsonPrimitive?.content.orEmpty(),
                channelTitle = root["channelTitle"]?.jsonPrimitive?.content.orEmpty(),
                officialArtist = root["officialArtist"]?.jsonPrimitive?.content == "true",
                verifiedChannel = root["verifiedChannel"]?.jsonPrimitive?.content == "true",
            )
        }.getOrNull()?.takeIf { safe(it.trackVideoId) && safe(it.videoId) }
            // Re-checked against today's rules: a reaction or cover upload is never the song's video.
            ?.takeIf { com.opencanvas.core.matcher.VideoMatcher.talkOverUpload(
                com.opencanvas.core.matcher.VideoCandidate(it.videoId, it.videoTitle, it.channelTitle, 0L)) == null }
            ?: return null
        synchronized(songs) { songs[song.key] = found }
        return found
    }

    /** Remembers [video] as [song]'s verified video; only automatic selections that were measured belong here. */
    fun putSong(song: SongIdentity, video: SongVideo) {
        synchronized(songs) {
            songs[song.key] = video
            if (songs.size > capacity) songs.remove(songs.keys.first())
        }
        val file = songFileFor(song) ?: return
        try {
            file.parentFile?.mkdirs()
            file.writeText(buildJsonObject {
                put("song", song.key)
                put("durationSec", video.durationSec)
                put("trackId", video.trackVideoId)
                put("videoId", video.videoId)
                put("videoTitle", video.videoTitle)
                put("channelTitle", video.channelTitle)
                put("officialArtist", video.officialArtist)
                put("verifiedChannel", video.verifiedChannel)
            }.toString())
        } catch (_: IOException) {
            // Only a shortcut lost: the song is searched for again next time.
        }
    }

    private fun songFileFor(song: SongIdentity): File? = directory?.resolve(SONGS)?.resolve(
        java.security.MessageDigest.getInstance("SHA-1").digest(song.key.toByteArray()).take(10)
            .joinToString("") { "%02x".format(it) } + ".json")

    /** Removes every in-memory entry and every file this store wrote. */
    fun clear() {
        synchronized(entries) { entries.clear(); verified.clear() }
        synchronized(songs) { songs.clear() }
        directory?.listFiles { _, name -> name.endsWith(".json") }?.forEach { it.delete() }
        directory?.resolve(SONGS)?.listFiles { _, name -> name.endsWith(".json") }?.forEach { it.delete() }
    }

    private fun fileFor(trackVideoId: String, videoId: String): File? =
        directory?.takeIf { safe(trackVideoId) && safe(videoId) }?.resolve("${trackVideoId}_$videoId.json")

    /** What a stored map's video was when it was chosen. */
    internal data class ChosenVideo(val title: String, val channel: String, val publisher: Boolean)

    /**
     * The map in [file], re-checked against today's rules. A video recorded as a reaction or similar upload is ignored.
     * So is a map that stops the song for long stretches between its parts, unless it was recorded as a publisher's own
     * upload (a film's "Video Song" carries dialogue inside the song): without that record (maps from before it was
     * kept) or from anyone else, it was most likely measured on a reaction, and the song is searched for again.
     */
    private fun readFile(file: File): SyncOffset? =
        try {
            if (!file.isFile) null else {
                val text = file.readText()
                YouTubeStreamResolver.parseSyncResponse(text)?.takeIf { offset ->
                    val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
                    val title = root?.get("videoTitle")?.jsonPrimitive?.content.orEmpty()
                    val channel = root?.get("channelTitle")?.jsonPrimitive?.content.orEmpty()
                    val publisher = root?.get("publisher")?.jsonPrimitive?.content == "true"
                    val reaction = com.opencanvas.core.matcher.VideoMatcher.talkOverUpload(
                        com.opencanvas.core.matcher.VideoCandidate("", title, channel, 0L)) != null
                    !reaction && (publisher ||
                        com.opencanvas.core.matcher.VideoMatcher.longestTalkGapMs(offset.segments) <= com.opencanvas.core.matcher.VideoMatcher.TALK_GAP_MS)
                }
            }
        } catch (_: IOException) {
            null
        }

    companion object {
        const val DEFAULT_CAPACITY = 256

        /** The folder of song-to-video entries ([songVideo]), beside the maps. */
        private const val SONGS = "songs"

        /** Cache key for a song/video pair. */
        fun key(trackVideoId: String, videoId: String): String = "$trackVideoId|$videoId"

        /** Video ids are 11 characters of `[A-Za-z0-9_-]`; anything else never becomes a file name. */
        private fun safe(id: String): Boolean = id.isNotEmpty() && id.length <= 64 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }

        /** The JSON the files and the community service use. */
        fun encode(trackVideoId: String, videoId: String, offset: SyncOffset): String = buildJsonObject {
            put("trackId", trackVideoId)
            put("videoId", videoId)
            put("offsetMs", offset.offsetMs)
            put("confidence", offset.confidence)
            put("coverage", offset.coverage)
            put("segments", buildJsonArray {
                offset.segments.forEach { segment ->
                    add(buildJsonObject {
                        put("songStartMs", segment.songStartMs)
                        put("songEndMs", segment.songEndMs)
                        put("offsetMs", segment.offsetMs)
                        if (segment.rate != 1.0) put("rate", segment.rate)
                    })
                }
            })
        }.toString()
    }
}
