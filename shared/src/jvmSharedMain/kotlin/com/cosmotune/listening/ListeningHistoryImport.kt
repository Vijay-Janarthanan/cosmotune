package com.cosmotune.listening

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Listening history from Spotify or ListenBrainz, read so it can join Replay (Namida's idea):
 * - Spotify's "Extended streaming history" (`Streaming_History_Audio_*.json`: `ts`, `ms_played`, `master_metadata_*`) and the
 *   account data's `StreamingHistory_music_*.json` (`endTime`, `artistName`, `trackName`, `msPlayed`), as the files or the zip
 *   Spotify sends; a play counts from 30 seconds, as Spotify counts it;
 * - a ListenBrainz export: the zip of monthly `.jsonl` files under `listens` or an older single JSON file, each listen one play.
 * Podcasts and videos are left out. Nothing is fetched; the songs are matched to recordings afterwards.
 */
object ListeningHistoryImport {
    data class Play(val atMs: Long, val playedMs: Long, val counts: Boolean)
    data class Track(val title: String, val artist: String, val album: String?, val plays: List<Play>)
    data class History(val from: String, val tracks: List<Track>) {
        val playCount: Int get() = tracks.sumOf { t -> t.plays.count { it.counts } }
    }

    private const val COUNTS_FROM_MS = 30_000L
    private const val LISTEN_GUESS_MS = 180_000L
    private const val MAX_BYTES = 512L * 1024 * 1024
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val accountTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

    private class Raw(val title: String, val artist: String, val album: String?, val play: Play)

    /** The history in [bytes]; fails with a plain message when the file holds none of the kinds above. */
    fun read(bytes: ByteArray): History {
        val texts = if (bytes.size > 3 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) zipTexts(bytes) else listOf(bytes.decodeToString())
        var from: String? = null
        val raws = ArrayList<Raw>()
        for (text in texts) {
            val (kind, found) = parse(text) ?: continue
            from = from ?: kind
            raws += found
        }
        requireNotNull(from) { "That file is not a Spotify or ListenBrainz listening history." }
        val tracks = raws.groupBy { key(it.artist) + "\u0000" + key(it.title) }.values.map { group ->
            val first = group.first()
            Track(first.title, first.artist, group.firstNotNullOfOrNull { it.album }, group.map { it.play }.sortedBy { it.atMs })
        }.sortedWith(compareByDescending<Track> { t -> t.plays.count { it.counts } }.thenByDescending { t -> t.plays.sumOf { it.playedMs } })
        return History(from, tracks)
    }

    private fun zipTexts(bytes: ByteArray): List<String> = buildList {
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.lowercase(Locale.ROOT)
                if (entry.isDirectory || !(name.endsWith(".json") || name.endsWith(".jsonl"))) continue
                val data = zip.readBytes()
                total += data.size
                require(total <= MAX_BYTES) { "That history is too large to read at once." }
                add(data.decodeToString())
            }
        }
    }

    private fun parse(text: String): Pair<String, List<Raw>>? {
        val trimmed = text.trimStart('﻿', ' ', '\n', '\r', '\t')
        val items: List<JsonObject> = when {
            trimmed.startsWith("[") -> (runCatching { json.parseToJsonElement(trimmed) }.getOrNull() as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return null
            trimmed.startsWith("{") -> trimmed.lineSequence().mapNotNull { line ->
                line.trim().takeIf { it.startsWith("{") }?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            }.toList()
            else -> return null
        }
        if (items.isEmpty()) return if (trimmed.startsWith("[")) "Spotify" to emptyList() else null
        val sample = items.first()
        return when {
            "track_metadata" in sample || "listened_at" in sample -> "ListenBrainz" to items.mapNotNull(::listen)
            "ms_played" in sample || "msPlayed" in sample -> "Spotify" to items.mapNotNull(::spotify)
            else -> null
        }
    }

    private fun JsonElement?.text(): String? = (this as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
    private fun JsonElement?.number(): Long? = (this as? kotlinx.serialization.json.JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toLong() }

    private fun spotify(o: JsonObject): Raw? {
        val title = (o["master_metadata_track_name"] ?: o["trackName"]).text() ?: return null
        val artist = (o["master_metadata_album_artist_name"] ?: o["artistName"]).text() ?: return null
        val played = (o["ms_played"] ?: o["msPlayed"]).number()?.coerceAtLeast(0) ?: return null
        val at = o["ts"].text()?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            ?: o["endTime"].text()?.let { runCatching { LocalDateTime.parse(it, accountTime).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull() }
            ?: return null
        return Raw(title, artist, o["master_metadata_album_album_name"].text(), Play(at, played, played >= COUNTS_FROM_MS))
    }

    private fun listen(o: JsonObject): Raw? {
        val meta = o["track_metadata"] as? JsonObject ?: return null
        val title = meta["track_name"].text() ?: return null
        val artist = meta["artist_name"].text() ?: return null
        val at = o["listened_at"].number()?.let { it * 1000 } ?: return null
        val info = meta["additional_info"] as? JsonObject
        val length = (info?.get("duration_ms").number() ?: info?.get("duration").number()?.times(1000))?.takeIf { it > 0 } ?: LISTEN_GUESS_MS
        return Raw(title, artist, meta["release_name"].text(), Play(at, length, true))
    }

    private fun key(text: String) = text.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
}
