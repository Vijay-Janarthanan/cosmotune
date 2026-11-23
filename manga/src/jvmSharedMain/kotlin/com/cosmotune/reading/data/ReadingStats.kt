package com.cosmotune.reading.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.serialization.Serializable

/** How fast a book is read: the share of it read and the time that took (only steady forward reading counts). */
@Serializable data class ReadingPace(val fraction: Double = 0.0, val ms: Long = 0)

/** What the reader's foot line can show, in the order chosen. */
enum class ReadingFooterItem(val label: String) {
    CHAPTER("Chapter"), CHAPTER_LEFT("Time left in chapter"), BOOK_LEFT("Time left in book"), PERCENT("Book %"), CLOCK("Clock")
}

/** Where a reader is in a whole book, and how long is left, from chapter sizes and the reader's own pace. */
internal object ReadingProgressMath {
    /** The share of the book before [locator]: chapters weighed by their size. PDFs use the page. */
    fun overall(locator: ReadingLocator, spine: List<String>, sizes: List<Long>): Double {
        if (spine.isEmpty()) return locator.progression.coerceIn(0.0, 1.0)
        val index = spine.indexOf(locator.resourceHref?.substringBefore('#')).coerceAtLeast(0)
        val weights = if (sizes.size == spine.size && sizes.sum() > 0) sizes.map { it.coerceAtLeast(1).toDouble() } else spine.map { 1.0 }
        val total = weights.sum()
        return ((weights.take(index).sum() + weights[index] * locator.progression.coerceIn(0.0, 1.0)) / total).coerceIn(0.0, 1.0)
    }

    /** The share of the book where the current chapter ends. */
    fun chapterEnd(locator: ReadingLocator, spine: List<String>, sizes: List<Long>): Double {
        if (spine.isEmpty()) return 1.0
        val index = spine.indexOf(locator.resourceHref?.substringBefore('#')).coerceAtLeast(0)
        return overall(ReadingLocator(spine.getOrNull(index + 1) ?: return 1.0), spine, sizes)
    }

    /** Milliseconds to read [share] of a book at [pace]; null until there is enough reading to tell (two minutes and 0.2 %). */
    fun timeFor(share: Double, pace: ReadingPace?): Long? {
        if (pace == null || pace.ms < 120_000 || pace.fraction < 0.002) return null
        return (share.coerceAtLeast(0.0) / pace.fraction * pace.ms).toLong()
    }

    /** "45 s", "12 min", "1 h 5 min". */
    fun duration(ms: Long): String {
        val minutes = (ms + 30_000) / 60_000
        return when {
            ms < 60_000 -> "under a minute"
            minutes < 60 -> "$minutes min"
            else -> "${minutes / 60} h" + (minutes % 60).takeIf { it > 0 }?.let { " $it min" }.orEmpty()
        }
    }

    /** The foot line: [items] in order, those that cannot be told yet left out. */
    fun footer(items: List<ReadingFooterItem>, chapterTitle: String, overall: Double, chapterEnd: Double, pace: ReadingPace?, nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault()): String = items.mapNotNull { item ->
        when (item) {
            ReadingFooterItem.CHAPTER -> chapterTitle.takeIf { it.isNotBlank() }
            ReadingFooterItem.CHAPTER_LEFT -> timeFor(chapterEnd - overall, pace)?.let { duration(it) + " left in chapter" }
            ReadingFooterItem.BOOK_LEFT -> timeFor(1.0 - overall, pace)?.let { duration(it) + " left in book" }
            ReadingFooterItem.PERCENT -> "${(overall * 100).toInt()} %"
            ReadingFooterItem.CLOCK -> Instant.ofEpochMilli(nowMs).atZone(zone).toLocalTime().let { "%02d:%02d".format(it.hour, it.minute) }
        }
    }.joinToString(" · ")
}

/** The reading statistics: time read by day, by book, and books finished. */
internal object ReadingStatistics {
    fun dayKey(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toString()

    /** The last seven days, oldest first, as (short weekday, time read). */
    fun week(state: ReadingState, today: LocalDate): List<Pair<String, Long>> = (6 downTo 0).map { back ->
        val day = today.minusDays(back.toLong())
        day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) to (state.readingDays[day.toString()] ?: 0L)
    }

    /** Days in a row, up to today (or yesterday, when today has nothing yet), with some reading. */
    fun streak(state: ReadingState, today: LocalDate): Int {
        var day = if ((state.readingDays[today.toString()] ?: 0L) > 0) today else today.minusDays(1)
        var count = 0
        while ((state.readingDays[day.toString()] ?: 0L) >= 60_000 && count < 3650) { count++; day = day.minusDays(1) }
        return count
    }

    fun finishedIn(state: ReadingState, year: Int, zone: ZoneId = ZoneId.systemDefault()): Int = state.finished.count { id ->
        state.finishedAt[id]?.let { Instant.ofEpochMilli(it).atZone(zone).year == year } ?: false
    }

    /** The books read longest, longest first. */
    fun topBooks(state: ReadingState, limit: Int = 5): List<Pair<ReadingBook, Long>> =
        state.books.mapNotNull { book -> state.readingMs[book.id]?.takeIf { it > 0 }?.let { book to it } }.sortedByDescending { it.second }.take(limit)
}
