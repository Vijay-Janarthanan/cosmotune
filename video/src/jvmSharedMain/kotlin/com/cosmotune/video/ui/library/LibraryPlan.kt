package com.cosmotune.video.ui.library

import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.library.HistoryEntry
import com.cosmotune.video.library.LocalPlaylist
import com.cosmotune.video.library.Subscription
import java.text.Collator
import java.time.LocalDate

/**
 * The small rules of the library pages (plan task Y-22; spec 5.2, 5.8, 8.1), kept apart from the screens so they can be run in a
 * test: which history entries "Continue watching" shows, what the history's search keeps, the order of the channels, the name of
 * the export file, and where a dragged row of watch later lands.
 */
internal object LibraryPlan {
    /** "Continue watching" shows at most this many videos (spec 5.2: up to 10). */
    const val CONTINUE_MAX = 10

    /** ... from this much of a video watched (spec 5.2: between 10 % and 90 % watched). */
    const val CONTINUE_FROM = 0.10f

    /**
     * The history entries "Continue watching" shows (spec 5.2): watched at least 10 % and less than 90 % (the "watched" mark,
     * [VideoMetrics.WATCHED_DONE]), with a watched bar to draw (10 s or more watched, a known duration), newest first (the order
     * the history keeps), at most [CONTINUE_MAX].
     */
    fun continueWatching(history: List<HistoryEntry>): List<HistoryEntry> {
        val out = ArrayList<HistoryEntry>(CONTINUE_MAX)
        for (entry in history) {
            val fraction = entry.item.watchedFraction(entry.positionMs, entry.durationMs) ?: continue
            if (fraction >= CONTINUE_FROM && fraction < VideoMetrics.WATCHED_DONE) out += entry
            if (out.size == CONTINUE_MAX) break
        }
        return out
    }

    /** The watched bar of each of [entries] by video id (the shelf and the rows draw it). */
    fun fractions(entries: List<HistoryEntry>): Map<String, Float> {
        val out = HashMap<String, Float>(entries.size * 2)
        for (entry in entries) entry.item.watchedFraction(entry.positionMs, entry.durationMs)?.let { out[entry.item.id] = it }
        return out
    }

    /**
     * The entries of the history whose title or channel holds [query] (spec 5.8: "a search field at the top"), ignoring case and
     * the spaces around it; the same list when the query is blank. The order is kept (newest first).
     */
    fun filterHistory(history: List<HistoryEntry>, query: String): List<HistoryEntry> {
        val q = query.trim()
        if (q.isEmpty()) return history
        return history.filter { entry ->
            entry.item.title.contains(q, ignoreCase = true) || entry.item.channelName?.contains(q, ignoreCase = true) == true
        }
    }

    /** The channels by name (spec 5.8: "sorted by name"), in the device's collation (accents and case after the letters), ties by id. */
    fun channelsByName(channels: List<Subscription>, collator: Comparator<Any> = Collator.getInstance().apply { strength = Collator.SECONDARY }): List<Subscription> =
        channels.sortedWith { a, b -> collator.compare(a.name, b.name).takeIf { it != 0 } ?: a.channelId.compareTo(b.channelId) }

    /** The export file's name (spec 8.1): `cosmotune-video-<yyyy-MM-dd>.json`, the date of [date]. */
    fun exportFileName(date: LocalDate): String = "$EXPORT_PREFIX$date.json"

    /** The prefix of the export file's name. */
    const val EXPORT_PREFIX = "cosmotune-video-"

    /** The type the export is saved as. */
    const val EXPORT_MIME = "application/json"

    /** The picture of one of your playlists' rows (spec 5.8: "the first video's thumbnail as the cover"), or null when it is empty. */
    fun coverOf(playlist: LocalPlaylist): String? = playlist.items.firstOrNull()?.thumbnailUrl

    /**
     * Where the dragged row of watch later goes (spec 5.8: "Watch later can be reordered"): the index of the row whose span holds
     * [draggedCenter], the middle of the dragged row as it is drawn now; [current] when the middle is over no other row. [rows] are the
     * rows on screen, each with its index in the list, its top and its height (pixels, in the list's own frame).
     */
    fun reorderTarget(current: Int, draggedCenter: Float, rows: List<RowSpan>): Int {
        for (row in rows) {
            if (row.index == current) continue
            if (draggedCenter >= row.top && draggedCenter < row.top + row.size) return row.index
        }
        return current
    }

    /** [list] with the element at [from] moved to [to] (both clamped to the list), as the library's own moves do. */
    fun <T> moved(list: List<T>, from: Int, to: Int): List<T> {
        if (list.isEmpty()) return list
        val f = from.coerceIn(0, list.lastIndex)
        val t = to.coerceIn(0, list.lastIndex)
        if (f == t) return list
        val out = list.toMutableList()
        val item = out.removeAt(f)
        out.add(t, item)
        return out
    }
}

/** A row of a lazy list on screen: its index in the list, its top and its height in pixels. */
internal data class RowSpan(val index: Int, val top: Float, val size: Float)
