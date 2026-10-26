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

    /** Stores [offset] for the pair in memory and, when a directory is set, on disk. */
    fun put(trackVideoId: String, videoId: String, offset: SyncOffset, verifiedSelection: Boolean = false) {
        synchronized(entries) {
            entries[key(trackVideoId, videoId)] = offset
            if (verifiedSelection) verified.add(key(trackVideoId, videoId)) else verified.remove(key(trackVideoId, videoId))
        }
        val file = fileFor(trackVideoId, videoId) ?: return
        val temporary = File(file.parentFile, "${file.name}.tmp")
        try {
            file.parentFile?.mkdirs()
            val encoded = encode(trackVideoId, videoId, offset)
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

    /** Removes every in-memory entry and every file this store wrote. */
    fun clear() {
        synchronized(entries) { entries.clear(); verified.clear() }
        directory?.listFiles { _, name -> name.endsWith(".json") }?.forEach { it.delete() }
    }

    private fun fileFor(trackVideoId: String, videoId: String): File? =
        directory?.takeIf { safe(trackVideoId) && safe(videoId) }?.resolve("${trackVideoId}_$videoId.json")

    private fun readFile(file: File): SyncOffset? =
        try {
            if (file.isFile) YouTubeStreamResolver.parseSyncResponse(file.readText()) else null
        } catch (_: IOException) {
            null
        }

    companion object {
        const val DEFAULT_CAPACITY = 256

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
