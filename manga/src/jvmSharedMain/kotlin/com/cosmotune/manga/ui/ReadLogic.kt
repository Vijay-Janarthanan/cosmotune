package com.cosmotune.manga.ui

import com.cosmotune.manga.data.MangaBook
import com.cosmotune.manga.data.MangaChapter
import com.cosmotune.manga.data.MangaLibraryState
import com.cosmotune.manga.data.MangaProgress
import com.cosmotune.manga.data.MangaReaderMode
import com.cosmotune.manga.data.MangaTapZones
import com.cosmotune.manga.data.MangaQueued
import com.cosmotune.manga.data.MangaQueueState
import com.cosmotune.manga.data.listedChapters
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * The decisions behind the Manga and Read screens, kept out of composition so they can be tested on their own: which chapter a
 * series continues with and how its button reads, the Updates and History lists, the reader's tap zones and the labels of a
 * library card.
 */

/**
 * The tabs of Manga, in order (board D: Library, Updates, Browse, Sources, History). The strings are also what
 * [com.cosmotune.ui.blocks.ModeNav.mangaTab] carries between the page and the desktop sidebar.
 */
internal object MangaTabs {
    const val LIBRARY = "Library"
    const val UPDATES = "Updates"
    const val BROWSE = "Browse"
    const val SOURCES = "Sources"
    const val HISTORY = "History"
    val all = listOf(LIBRARY, UPDATES, BROWSE, SOURCES, HISTORY)

    /** The bottom bar's and the rail's order (the Manga board): Sources stands last, under the name More. */
    val bar = listOf(LIBRARY, UPDATES, BROWSE, HISTORY, SOURCES)

    /** A tab name from the shared channel; one this mode does not have (Read's "Bookmarks", the old "Home") maps onto its own. */
    fun of(name: String): String = when (name) {
        in all -> name
        "Discover" -> BROWSE
        else -> LIBRARY
    }
}

/** The tabs of Read (books), in order: the library first (with the book being read on top), then Browse and Bookmarks. */
internal object BookTabs {
    const val LIBRARY = "Library"
    const val BROWSE = "Browse"
    const val BOOKMARKS = "Bookmarks"
    val all = listOf(LIBRARY, BROWSE, BOOKMARKS)

    fun of(name: String): String = when (name) {
        in all -> name
        "Discover", "Sources" -> BROWSE
        "Updates" -> BOOKMARKS
        else -> LIBRARY
    }
}

/** What the series page's main button does: open [chapter] (null when the series has no chapters yet), labelled [label]. */
internal data class MangaContinue(val chapter: MangaChapter?, val label: String, val resumes: Boolean)

/**
 * The chapter a series continues with. A chapter in progress comes first; after a finished one, the next chapter of the same
 * edition ([MangaBook.nextChapter]'s rule: same language, branch and scanlator); otherwise the first unread chapter in reading
 * order; and the first chapter when everything is read. The label names the chapter by its number when the source gives one
 * ("Continue chapter 12"), never just "Read", which is the name a local comic gives its only chapter.
 */
internal fun mangaContinue(book: MangaBook, state: MangaLibraryState): MangaContinue {
    val ordered = book.listedChapters()
    if (ordered.isEmpty()) return MangaContinue(null, "Refresh chapters", resumes = false)
    val progress = state.progress[book.id]
    val inProgress = progress?.let { saved -> ordered.find { it.id == saved.chapterId } }
    if (progress != null && inProgress != null && !progress.finished) return MangaContinue(inProgress, "Continue " + chapterName(inProgress), resumes = true)
    val after = inProgress?.let { current -> book.nextChapterInEdition(current, ordered) }
    val target = after ?: ordered.firstOrNull { it.id !in state.readChapters }
    return when {
        target != null && (progress != null || ordered.any { it.id in state.readChapters }) -> MangaContinue(target, "Continue " + chapterName(target), resumes = true)
        target != null -> MangaContinue(target, "Start reading", resumes = false)
        else -> MangaContinue(ordered.first(), "Read again", resumes = false)
    }
}

private fun MangaBook.nextChapterInEdition(current: MangaChapter, ordered: List<MangaChapter>): MangaChapter? {
    val edition = ordered.filter { it.language == current.language && it.branch == current.branch && it.scanlator == current.scanlator }
    val index = edition.indexOfFirst { it.id == current.id }
    return if (index < 0) null else edition.getOrNull(index + 1)
}

/** "chapter 12" when the source numbers its chapters, "chapter 4.5" for a half chapter, else "reading" (the title says the rest). */
internal fun chapterName(chapter: MangaChapter): String = when {
    chapter.number <= 0f -> "reading"
    chapter.number % 1f == 0f -> "chapter ${chapter.number.toInt()}"
    else -> "chapter ${chapter.number}"
}

/** What a chapter's tile says: its number ("99", "4.5"), else its place in the list ([fallback], from 1). */
internal fun chapterNumber(chapter: MangaChapter, fallback: Int): String = when {
    chapter.number <= 0f -> fallback.toString()
    chapter.number % 1f == 0f -> chapter.number.toInt().toString()
    else -> chapter.number.toString()
}

/** The line under a chapter's title: the reading position, else the upload date, the scanlator and the edition. */
internal fun chapterSubtitle(chapter: MangaChapter, progress: MangaProgress?, read: Boolean, zone: ZoneId = ZoneId.systemDefault()): String {
    val position = progress?.takeIf { it.chapterId == chapter.id && !read && it.pageCount > 0 }?.let { "Page ${it.page + 1} of ${it.pageCount}" }
    val date = chapter.uploadDate.takeIf { it > 0 }?.let { DATE.format(Instant.ofEpochMilli(it).atZone(zone)) }
    return listOfNotNull(position, date, chapter.scanlator?.takeIf { it.isNotBlank() }, chapter.language.takeIf { it.isNotBlank() }).joinToString(" · ")
}

/** One row of the Updates tab: a chapter found by an update check that is still new. */
internal data class MangaUpdateEntry(val book: MangaBook, val chapter: MangaChapter)

/** The new chapters of the library's series, newest upload first, then by series and chapter order. */
internal fun mangaUpdates(state: MangaLibraryState): List<MangaUpdateEntry> =
    state.books.flatMap { book -> book.listedChapters().filter { it.id in state.newChapters }.map { MangaUpdateEntry(book, it) } }
        .sortedWith(compareByDescending<MangaUpdateEntry> { it.chapter.uploadDate }.thenBy { it.book.title.lowercase() }.thenBy { it.chapter.volume }.thenBy { it.chapter.number })

/** A day of the Updates tab ("Today", "Yesterday", a date; "Earlier" for chapters whose source gives no date) and its chapters. */
internal data class MangaUpdateDay(val label: String, val entries: List<MangaUpdateEntry>)

internal fun mangaUpdateDays(state: MangaLibraryState, nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): List<MangaUpdateDay> {
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return mangaUpdates(state).groupBy { entry ->
        entry.chapter.uploadDate.takeIf { it > 0 }?.let { dayLabel(Instant.ofEpochMilli(it).atZone(zone).toLocalDate(), today) } ?: "Earlier"
    }.map { (label, entries) -> MangaUpdateDay(label, entries) }
}

/** The Updates tab's series view (Komikku's grouped updates): each series with its new chapters, the newest upload first. */
internal fun mangaUpdatesBySeries(state: MangaLibraryState): List<Pair<MangaBook, List<MangaUpdateEntry>>> =
    mangaUpdates(state).groupBy { it.book.id }.values.map { it.first().book to it }
        .sortedByDescending { (_, entries) -> entries.maxOf { it.chapter.uploadDate } }

/**
 * When the next chapter of [book] can be expected (Mihon's release estimate): the last upload plus the middle gap between its
 * last uploads. Null for a finished series, or one with fewer than three dated chapters or no steady rhythm (gaps over 60 days).
 */
internal fun mangaNextRelease(book: MangaBook): Long? {
    if (book.parserData?.state == "FINISHED") return null
    val dates = book.chapters.map { it.uploadDate }.filter { it > 0 }.distinct().sorted().takeLast(8)
    if (dates.size < 3) return null
    val gaps = dates.zipWithNext { a, b -> b - a }.sorted()
    val middle = gaps[gaps.size / 2]
    if (middle <= 0 || middle > 60L * 86_400_000L) return null
    return dates.last() + middle
}

/** The coming chapters for Updates' Upcoming list: series with an estimate within [daysAhead] days of [nowMs], soonest first. */
internal fun mangaUpcoming(state: MangaLibraryState, nowMs: Long = System.currentTimeMillis(), daysAhead: Int = 30): List<Pair<MangaBook, Long>> =
    state.books.filter { it.sourceId != "local" }.mapNotNull { book -> mangaNextRelease(book)?.let { book to it } }
        .filter { (_, at) -> at in (nowMs - 7L * 86_400_000L)..(nowMs + daysAhead * 86_400_000L) }.sortedBy { it.second }

/** "Expected today", "Expected in 3 days", "Expected 2 days ago" (late): the line under an upcoming series. */
internal fun expectedLabel(atMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val days = Math.round((atMs - nowMs) / 86_400_000.0).toInt()
    return when {
        days == 0 -> "Expected today"
        days == 1 -> "Expected tomorrow"
        days > 1 -> "Expected in $days days"
        days == -1 -> "Expected yesterday"
        else -> "Expected ${-days} days ago"
    }
}

/** "just now", "12 min ago", "3 h ago", "2 days ago": how long ago [thenMs] was, for "Library last updated …". */
internal fun agoLabel(thenMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val minutes = ((nowMs - thenMs).coerceAtLeast(0L) / 60_000L)
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 48 * 60 -> "${minutes / 60} h ago"
        else -> "${minutes / (24 * 60)} days ago"
    }
}

/** One row of the History tab: the last chapter read in a series, and where. */
internal data class MangaHistoryEntry(val book: MangaBook, val chapter: MangaChapter?, val progress: MangaProgress)

/** A day of the History tab: "Today", "Yesterday" or the date, and its rows, most recent first. */
internal data class MangaHistoryDay(val label: String, val entries: List<MangaHistoryEntry>)

/** The library's reading history grouped by day, most recent first. Series no longer in the library are left out. */
internal fun mangaHistory(state: MangaLibraryState, nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): List<MangaHistoryDay> {
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return state.progress.values.sortedByDescending { it.updatedAtMs }.mapNotNull { progress ->
        val book = state.books.find { it.id == progress.bookId } ?: return@mapNotNull null
        MangaHistoryEntry(book, book.chapters.find { it.id == progress.chapterId }, progress)
    }.groupBy { dayLabel(Instant.ofEpochMilli(it.progress.updatedAtMs).atZone(zone).toLocalDate(), today) }
        .map { (label, entries) -> MangaHistoryDay(label, entries) }
}

internal fun dayLabel(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> if (day.year == today.year) SHORT_DAY.format(day) else DATE.format(day)
}

/** The library card's line: "3 unread · 1 new", "Caught up", or nothing while the chapters are not known yet. */
internal fun mangaLibraryLine(book: MangaBook, state: MangaLibraryState): String? {
    if (book.chapters.isEmpty()) return null
    val unread = book.chapters.count { it.id !in state.readChapters }
    val fresh = book.chapters.count { it.id in state.newChapters }
    return if (unread == 0) "Caught up" else "$unread unread" + if (fresh > 0) " · $fresh new" else ""
}

/** What a library cover carries in its corner: the unread chapters and the chapters on this device. */
internal data class MangaCoverCounts(val unread: Int, val downloaded: Int)

internal fun mangaCoverCounts(book: MangaBook, state: MangaLibraryState): MangaCoverCounts = MangaCoverCounts(
    unread = book.chapters.count { it.id !in state.readChapters },
    downloaded = if (book.sourceId == "local") 0 else book.chapters.count { state.downloads.containsKey(it.id) },
)

/**
 * The library in the order its options sheet chose: last read (the default), title, added, the most unread first, the newest
 * chapter first (by upload date), or the most chapters first.
 */
internal fun mangaLibraryOrder(books: List<MangaBook>, sort: String, state: MangaLibraryState): List<MangaBook> = when (sort) {
    "title" -> books.sortedBy { it.title.lowercase() }
    "added" -> books.sortedByDescending { it.addedAtMs }
    "unread" -> books.sortedByDescending { book -> book.chapters.count { it.id !in state.readChapters } }
    "latest" -> books.sortedByDescending { book -> book.chapters.maxOfOrNull { it.uploadDate } ?: 0L }
    "chapters" -> books.sortedByDescending { it.chapters.size }
    else -> books.sortedByDescending { state.progress[it.id]?.updatedAtMs ?: it.addedAtMs }
}

/** The library's filters (Mihon's): each one on keeps only the series that match it; several on keep the series matching all. */
internal object MangaLibraryFilters {
    const val UNREAD = "unread"; const val DOWNLOADED = "downloaded"; const val STARTED = "started"; const val COMPLETED = "completed"; const val TRACKED = "tracked"
    val all = listOf(UNREAD, DOWNLOADED, STARTED, COMPLETED, TRACKED)
    fun label(filter: String): String = when (filter) {
        UNREAD -> "Unread"; DOWNLOADED -> "Downloaded"; STARTED -> "Started"; COMPLETED -> "Finished"; else -> "Tracked"
    }
}

internal fun mangaLibraryFilter(books: List<MangaBook>, filters: Set<String>, state: MangaLibraryState): List<MangaBook> = books.filter { book ->
    filters.all { filter -> when (filter) {
        MangaLibraryFilters.UNREAD -> book.chapters.any { it.id !in state.readChapters }
        MangaLibraryFilters.DOWNLOADED -> book.sourceId == "local" || book.chapters.any { state.downloads.containsKey(it.id) }
        MangaLibraryFilters.STARTED -> book.chapters.any { it.id in state.readChapters } || state.progress.containsKey(book.id)
        MangaLibraryFilters.COMPLETED -> book.parserData?.state == "FINISHED"
        MangaLibraryFilters.TRACKED -> state.tracks[book.id].orEmpty().isNotEmpty()
        else -> true
    } }
}

/** The Continue row's line: "Chapter 9 · Page 6 of 22" while a chapter is open, else "Next: Chapter 10". */
internal fun mangaContinueLine(book: MangaBook, state: MangaLibraryState): String? {
    val chapter = mangaContinue(book, state).chapter ?: return null
    val name = chapterName(chapter).takeIf { it != "reading" }?.replaceFirstChar(Char::uppercase) ?: chapter.title
    val progress = state.progress[book.id]?.takeIf { it.chapterId == chapter.id && !it.finished && it.pageCount > 0 }
    return if (progress != null) "$name · Page ${progress.page + 1} of ${progress.pageCount}" else "Next: $name"
}

/** How many new chapters [days] hold in all, for the Updates tab's count and the mode switcher's line. */
internal fun mangaNewCount(state: MangaLibraryState): Int = mangaUpdates(state).size

/**
 * The line a download queue shows for [items] (all of it, or one series' part): the chapter being fetched and its pages, how many
 * more wait, and how many failed; "Paused" while the queue is held.
 */
internal fun mangaQueueLine(items: List<MangaQueued>, queue: MangaQueueState): String {
    val running = items.find { it.chapter.id == queue.running }
    val waiting = items.count { it.error == null && it.chapter.id != queue.running }
    val failed = items.count { it.error != null }
    return listOfNotNull(
        if (queue.paused) "Paused" else running?.let { "Downloading " + it.chapter.title + if (it.total > 0) ": ${it.done} of ${it.total} pages" else "" },
        waiting.takeIf { it > 0 }?.let { if (running != null && !queue.paused) "$it more waiting" else "$it waiting" },
        failed.takeIf { it > 0 }?.let { "$it failed" },
    ).joinToString(" · ")
}

/** What a tap on a page does: turn back, turn forward, or show and hide the reader's chrome. */
internal enum class ReaderTap { PREVIOUS, NEXT, MENU }

/**
 * The reader's tap zones (Mihon's default for paged reading): the outer thirds turn the page, the middle third shows or hides
 * the chrome. In right-to-left reading the left edge goes forward. A webtoon scrolls, so every tap shows or hides the chrome.
 */
internal fun readerTap(x: Float, width: Float, mode: MangaReaderMode): ReaderTap = readerTap(x, 0.5f, width, 1f, mode, MangaTapZones.THIRDS, false)

/**
 * The reader's tap zones in the layout the listener chose (Mihon's layouts): [MangaTapZones.THIRDS], the outer thirds turn the
 * page; [MangaTapZones.L_SHAPED], the top band and the left side go back, the bottom band and the right side go on;
 * [MangaTapZones.KINDLE], the left strip goes back and the rest under the top band goes on; [MangaTapZones.EDGE], both sides go
 * on and the bottom middle goes back; [MangaTapZones.OFF], every tap shows or hides the chrome. The middle always shows the
 * chrome. Right to left mirrors the layout; [invert] swaps back and on. A webtoon scrolls, so in it every tap is the chrome's.
 */
internal fun readerTap(x: Float, y: Float, width: Float, height: Float, mode: MangaReaderMode, zones: MangaTapZones, invert: Boolean): ReaderTap {
    if (mode == MangaReaderMode.WEBTOON || width <= 0f || height <= 0f || zones == MangaTapZones.OFF) return ReaderTap.MENU
    val fx = (x / width).let { if (mode == MangaReaderMode.PAGES_RTL) 1f - it else it }
    val fy = y / height
    val left = fx < 1f / 3f; val right = fx > 2f / 3f; val top = fy < 1f / 3f; val bottom = fy > 2f / 3f
    val forward: Boolean? = when (zones) {
        MangaTapZones.THIRDS -> if (left) false else if (right) true else null
        MangaTapZones.L_SHAPED -> when { top || (left && !bottom) -> false; bottom || right -> true; else -> null }
        MangaTapZones.KINDLE -> when { top -> null; left -> false; else -> true }
        MangaTapZones.EDGE -> when { left || right -> true; bottom -> false; else -> null }
        MangaTapZones.OFF -> null
    }
    return when (forward) { null -> ReaderTap.MENU; true -> if (invert) ReaderTap.PREVIOUS else ReaderTap.NEXT; false -> if (invert) ReaderTap.NEXT else ReaderTap.PREVIOUS }
}

/** The name of a reading mode, as the reading-mode sheet lists it. */
internal fun readerModeLabel(mode: MangaReaderMode): String = when (mode) {
    MangaReaderMode.PAGES_LTR -> "Left to right"
    MangaReaderMode.PAGES_RTL -> "Right to left"
    MangaReaderMode.WEBTOON -> "Webtoon"
}

/** Two initials of a title, for a cover that has not loaded or does not exist. */
internal fun titleInitials(title: String): String =
    title.trim().split(Regex("\\s+")).take(2).mapNotNull { it.firstOrNull()?.uppercaseChar()?.toString() }.joinToString("")

private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())
private val SHORT_DAY = DateTimeFormatter.ofPattern("EEEE, d MMM", Locale.getDefault())
