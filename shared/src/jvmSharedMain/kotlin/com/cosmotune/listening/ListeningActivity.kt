package com.cosmotune.listening

import java.time.LocalDate

data class ListeningDay(val date: LocalDate, val listenedMs: Long)

/** Derived from recorded daily totals; undated legacy listening is never invented as dated plays. */
object ListeningActivity {
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
