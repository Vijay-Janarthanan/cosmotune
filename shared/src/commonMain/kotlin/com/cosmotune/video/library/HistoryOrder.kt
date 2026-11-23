package com.cosmotune.video.library

/** How the History page orders what was watched: newest first (the day groups), oldest first, by name, most or least played. */
enum class HistorySort { NEWEST, OLDEST, NAME, MOST_PLAYED, LEAST_PLAYED }

/** The History page's time range: any time, today, the last 7 days, the last 30 days. */
enum class HistoryRange(val days: Int?) { ANY(null), TODAY(1), WEEK(7), MONTH(30) }

object HistoryOrder {
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /** [entries] within [range] (counted back from [startOfTodayMs]; "today" is since midnight), in [sort]'s order. */
    fun apply(entries: List<HistoryEntry>, sort: HistorySort, range: HistoryRange, startOfTodayMs: Long): List<HistoryEntry> {
        val since = range.days?.let { startOfTodayMs - (it - 1) * DAY_MS }
        val kept = if (since == null) entries else entries.filter { it.lastWatchedMs >= since }
        return when (sort) {
            HistorySort.NEWEST -> kept.sortedByDescending { it.lastWatchedMs }
            HistorySort.OLDEST -> kept.sortedBy { it.lastWatchedMs }
            HistorySort.NAME -> kept.sortedBy { it.item.title.lowercase() }
            HistorySort.MOST_PLAYED -> kept.sortedWith(compareByDescending<HistoryEntry> { it.watchCount }.thenByDescending { it.lastWatchedMs })
            HistorySort.LEAST_PLAYED -> kept.sortedWith(compareBy<HistoryEntry> { it.watchCount }.thenByDescending { it.lastWatchedMs })
        }
    }
}
