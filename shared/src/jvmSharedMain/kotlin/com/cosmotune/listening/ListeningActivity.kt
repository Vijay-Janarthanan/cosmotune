package com.cosmotune.listening

import java.time.LocalDate

data class ListeningDay(val date: LocalDate, val listenedMs: Long)

/** Derived from recorded daily totals; undated legacy listening is never invented as dated plays. */
object ListeningActivity {
    /** Time listened on each day of the week, Monday first, over every `YYYY-MM-DD` in [days] (Kreate's weekday chart). */
    fun byWeekday(days: Map<String, Long>): List<Long> {
        val out = LongArray(7)
        for ((day, ms) in days) {
            val date = runCatching { LocalDate.parse(day) }.getOrNull() ?: continue
            out[date.dayOfWeek.value - 1] += ms.coerceAtLeast(0)
        }
        return out.toList()
    }

    /**
     * Time listened per source, largest first, from each song's id and its time (Kreate's time per source): YouTube Music, this
     * device's files, radio and podcasts, and the listener's own servers ([server] says which ids are theirs).
     */
    fun bySource(played: List<Pair<String, Long>>, server: (String) -> Boolean = { false }): List<Pair<String, Long>> =
        played.groupBy({ (id, _) ->
            when {
                id.startsWith("podio:") -> "Radio and podcasts"
                id.startsWith("local:") || id.startsWith("content:") || id.startsWith("file:") -> "This device"
                server(id) -> "Your servers"
                else -> "YouTube Music"
            }
        }, { it.second.coerceAtLeast(0) }).mapValues { it.value.sum() }.filterValues { it > 0 }.toList().sortedByDescending { it.second }

    fun window(days: Map<String, Long>, today: LocalDate = LocalDate.now(), count: Int = 28): List<ListeningDay> {
        require(count in 1..366)
        return (count - 1 downTo 0).map { offset ->
            val date = today.minusDays(offset.toLong())
            ListeningDay(date, days[date.toString()]?.coerceAtLeast(0) ?: 0)
        }
    }

    fun longestStreak(days: Map<String, Long>): Int {
        var previous: LocalDate? = null
        var streak = 0
        var longest = 0
        days.filterValues { it > 0 }.keys.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.sorted().forEach { date ->
            streak = if (previous?.plusDays(1) == date) streak + 1 else 1
            longest = maxOf(longest, streak)
            previous = date
        }
        return longest
    }
}
