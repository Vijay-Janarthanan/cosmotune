package com.cosmotune.podio

import java.time.LocalDate

/**
 * How long the listener has listened, by day and show (AntennaPod's statistics, Pocket Casts' year in review): the player's own
 * progress is counted as it moves, a jump or a seek never. Kept for 400 days.
 */
object PodioStats {
    const val DAYS_KEPT = 400
    /** A step of the playhead longer than this is a seek, not listening. */
    const val MAX_STEP_MS = 15_000L

    /** [saved] with [ms] more heard of [showId] (or "" for an episode of no followed show) on [day]. */
    fun add(saved: PodioSaved, day: LocalDate, showId: String?, ms: Long): PodioSaved {
        if (ms <= 0) return saved
        val key = day.toString()
        val today = saved.listened[key].orEmpty()
        val show = showId.orEmpty()
        val next = saved.listened + (key to today + (show to (today[show] ?: 0L) + ms))
        return saved.copy(listened = next.toSortedMap().entries.toList().takeLast(DAYS_KEPT).associate { it.key to it.value })
    }

    data class Summary(val totalMs: Long, val days: Int, val byShow: List<Pair<String, Long>>)

    /** What was heard from [from] to [to] (both included): the total, on how many days, and each show, the most heard first. */
    fun summary(saved: PodioSaved, from: LocalDate, to: LocalDate): Summary {
        val inRange = saved.listened.filterKeys { key -> runCatching { LocalDate.parse(key) }.getOrNull()?.let { !it.isBefore(from) && !it.isAfter(to) } == true }
        val byShow = HashMap<String, Long>()
        inRange.values.forEach { day -> day.forEach { (show, ms) -> byShow[show] = (byShow[show] ?: 0L) + ms } }
        return Summary(byShow.values.sum(), inRange.count { it.value.values.sum() > 0 }, byShow.entries.sortedByDescending { it.value }.map { it.key to it.value })
    }

    /** "12 h 5 min", "45 min", "under a minute". */
    fun length(ms: Long): String {
        val minutes = ms / 60_000
        return when {
            minutes < 1 -> "under a minute"
            minutes < 60 -> "$minutes min"
            minutes % 60 == 0L -> "${minutes / 60} h"
            else -> "${minutes / 60} h ${minutes % 60} min"
        }
    }
}
