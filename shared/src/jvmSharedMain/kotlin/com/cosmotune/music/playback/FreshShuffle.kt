package com.cosmotune.music.playback

import kotlin.math.pow
import kotlin.random.Random

/**
 * Fresh shuffle (Lokal's history-aware shuffle): songs heard lately are still in the shuffle, but they tend to come later, so the
 * same few do not keep turning up first. A weighted random order (each song's key is random^(1 / weight)); a song played within the
 * hour weighs a twentieth, one not played for a week or more weighs as much as one never played.
 */
object FreshShuffle {
    const val REMEMBERED = 500
    private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
    private const val LIGHTEST = 0.05

    fun weight(lastPlayedMs: Long?, nowMs: Long): Double {
        val since = (nowMs - (lastPlayedMs ?: return 1.0)).coerceAtLeast(0)
        return if (since >= WEEK_MS) 1.0 else (since.toDouble() / WEEK_MS).coerceIn(LIGHTEST, 1.0)
    }

    /** [items] in a fresh shuffled order; [lastPlayed] says when an item was last played, null when not lately. */
    fun <T> order(items: List<T>, random: Random = Random.Default, nowMs: Long = System.currentTimeMillis(), lastPlayed: (T) -> Long?): List<T> =
        items.map { it to random.nextDouble().pow(1.0 / weight(lastPlayed(it), nowMs)) }.sortedByDescending { it.second }.map { it.first }

    /** [map] with [id] played at [atMs], keeping the newest [REMEMBERED]. */
    fun played(map: Map<String, Long>, id: String, atMs: Long): Map<String, Long> {
        val out = LinkedHashMap(map); out.remove(id); out[id] = atMs
        while (out.size > REMEMBERED) out.remove(out.keys.first())
        return out
    }

    fun encode(map: Map<String, Long>) = map.entries.joinToString("\n") { "${it.key}\t${it.value}" }
    fun decode(raw: String): Map<String, Long> = raw.take(80_000).lineSequence().mapNotNull { line ->
        val (id, at) = line.split('\t').takeIf { it.size == 2 } ?: return@mapNotNull null
        at.toLongOrNull()?.let { id to it }
    }.toList().takeLast(REMEMBERED).toMap(LinkedHashMap())
}
