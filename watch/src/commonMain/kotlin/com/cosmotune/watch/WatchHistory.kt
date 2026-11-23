package com.cosmotune.watch

import com.cosmotune.watch.model.WatchProgress

/** What the History page shows: everything, films, episodes, what was finished, or what was left part way. */
enum class HistoryFilter(val label: String) { ALL("All"), FILMS("Films"), EPISODES("Episodes"), FINISHED("Finished"), UNFINISHED("Left part way") }

/** The History page: what this profile played, newest first, by day, with a filter. */
object WatchHistory {
    fun filter(items: List<WatchProgress>, filter: HistoryFilter): List<WatchProgress> = items.filter { p ->
        when (filter) {
            HistoryFilter.ALL -> true
            HistoryFilter.FILMS -> p.key.seriesKey == null
            HistoryFilter.EPISODES -> p.key.seriesKey != null
            HistoryFilter.FINISHED -> p.completed
            HistoryFilter.UNFINISHED -> !p.completed
        }
    }.sortedByDescending { it.updatedAtMs }

    /** [items] (newest first) under their day: "Today", "Yesterday", else the day as [dayName] gives it. */
    fun byDay(items: List<WatchProgress>, todayEpochDay: Long, epochDayOf: (Long) -> Long, dayName: (Long) -> String): List<Pair<String, List<WatchProgress>>> =
        items.groupBy { epochDayOf(it.updatedAtMs) }.entries.sortedByDescending { it.key }.map { (day, list) ->
            when (day) { todayEpochDay -> "Today"; todayEpochDay - 1 -> "Yesterday"; else -> dayName(day) } to list
        }
}
