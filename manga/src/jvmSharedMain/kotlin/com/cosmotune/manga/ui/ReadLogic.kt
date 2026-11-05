package com.cosmotune.manga.ui

import com.cosmotune.manga.data.MangaBook
import com.cosmotune.manga.data.MangaChapter
import com.cosmotune.manga.data.MangaLibraryState
import com.cosmotune.manga.data.MangaProgress
import com.cosmotune.manga.data.MangaReaderMode
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
 * The tabs of Manga, in dock order (DESIGN_SYSTEM.md 5.2: Home, Discover, Following, Library). The strings are also what
 * [com.cosmotune.ui.blocks.ModeNav.mangaTab] carries between the page and the desktop sidebar.
 */
internal object MangaTabs {
    const val HOME = "Home"
    const val BROWSE = "Browse"
    const val UPDATES = "Updates"
    const val LIBRARY = "Library"
    val all = listOf(HOME, BROWSE, UPDATES, LIBRARY)

    /** A tab name from the shared channel; one this mode does not have ("Bookmarks", the old "Discover") maps onto its own. */
    fun of(name: String): String = when (name) {
        in all -> name
        "Discover" -> BROWSE
        "History" -> LIBRARY
        else -> HOME
    }
}

/** The tabs of Read (books), in dock order; slot 3 is Bookmarks, the design system's documented exception. */
internal object BookTabs {
    const val HOME = "Home"
    const val BROWSE = "Browse"
    const val BOOKMARKS = "Bookmarks"
    const val LIBRARY = "Library"
    val all = listOf(HOME, BROWSE, BOOKMARKS, LIBRARY)

    fun of(name: String): String = when (name) {
        in all -> name
        "Discover" -> BROWSE
        "Updates" -> BOOKMARKS
        else -> HOME
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

/** What a tap on a page does: turn back, turn forward, or show and hide the reader's chrome. */
internal enum class ReaderTap { PREVIOUS, NEXT, MENU }

/**
 * The reader's tap zones (Mihon's default for paged reading): the outer thirds turn the page, the middle third shows or hides
 * the chrome. In right-to-left reading the left edge goes forward. A webtoon scrolls, so every tap shows or hides the chrome.
 */
internal fun readerTap(x: Float, width: Float, mode: MangaReaderMode): ReaderTap {
    if (mode == MangaReaderMode.WEBTOON || width <= 0f) return ReaderTap.MENU
    val fraction = x / width
    val edge = when {
        fraction < 1f / 3f -> -1
        fraction > 2f / 3f -> 1
        else -> 0
    }
    if (edge == 0) return ReaderTap.MENU
    val forward = if (mode == MangaReaderMode.PAGES_RTL) edge < 0 else edge > 0
    return if (forward) ReaderTap.NEXT else ReaderTap.PREVIOUS
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
