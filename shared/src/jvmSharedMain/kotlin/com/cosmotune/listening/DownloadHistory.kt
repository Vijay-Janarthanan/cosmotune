package com.cosmotune.listening

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The downloads that finished or failed, kept across restarts (the idea from Lokal, see NOTICE.md): newest first, one row
 * per song (a retry that works replaces the failure), at most [MAX] rows.
 */
object DownloadHistory {
    const val MAX = 100

    @Serializable
    data class Entry(
        val trackId: String,
        val title: String,
        val artist: String = "",
        val atMs: Long,
        /** Why it failed, or null when the song was saved. */
        val failed: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(entries: List<Entry>): String = json.encodeToString(ListSerializer(Entry.serializer()), entries)

    fun decode(text: String): List<Entry> =
        if (text.isBlank()) emptyList() else runCatching { json.decodeFromString(ListSerializer(Entry.serializer()), text) }.getOrDefault(emptyList()).take(MAX)

    fun add(entries: List<Entry>, entry: Entry): List<Entry> =
        if (entry.trackId.isBlank()) entries else (listOf(entry) + entries.filterNot { it.trackId == entry.trackId }).take(MAX)

    /** "Today", "Yesterday", or the day ("12 Nov", with the year when it is not this one). */
    fun dayLabel(atMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val day = Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return when (day) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> day.format(DateTimeFormatter.ofPattern(if (day.year == today.year) "d MMM" else "d MMM yyyy", Locale.ENGLISH))
        }
    }

    /** The rows grouped under their day labels, in order. */
    fun byDay(entries: List<Entry>, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): List<Pair<String, List<Entry>>> =
        entries.groupBy { dayLabel(it.atMs, nowMs, zone) }.toList()

    /** The line a finished batch announces: "12 songs downloaded" or "3 songs downloaded, 1 failed". Null for nothing. */
    fun batchSummary(saved: Int, failed: Int): String? = when {
        saved == 0 && failed == 0 -> null
        failed == 0 -> "${songs(saved)} downloaded"
        saved == 0 -> "${songs(failed)} could not be downloaded"
        else -> "${songs(saved)} downloaded, $failed failed"
    }

    private fun songs(n: Int) = if (n == 1) "1 song" else "$n songs"
}
