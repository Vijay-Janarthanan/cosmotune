package com.cosmotune.manga.data

/**
 * The series in the library that look like the same one twice (Mihon's duplicate check): the same title, ignoring case and
 * punctuation, or one's title among the other's alternate titles. Each group has two or more series, longest read first.
 */
fun mangaDuplicates(state: MangaLibraryState): List<List<MangaBook>> {
    fun key(title: String) = title.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "")
    val groups = LinkedHashMap<String, MutableList<MangaBook>>()
    state.books.forEach { book ->
        val keys = (listOf(book.title) + book.parserData?.alternateTitles.orEmpty()).map(::key).filter { it.length >= 2 }
        val existing = keys.firstOrNull { it in groups } ?: keys.firstOrNull() ?: return@forEach
        groups.getOrPut(existing) { ArrayList() } += book
    }
    return groups.values.filter { it.size > 1 }.map { list -> list.sortedByDescending { book -> book.chapters.count { it.id in state.readChapters } } }
}

/** Merges [others] into [keep] (Komikku's merge): each one's read chapters, place, collections and links move to [keep], then it goes. */
fun MangaLibraryState.merged(keep: MangaBook, others: List<MangaBook>): MangaLibraryState =
    others.filter { it.id != keep.id }.fold(this) { state, other ->
        val current = state.books.find { it.id == keep.id } ?: keep
        val readBefore = state.readChapters
        state.migrated(other, current).let { next ->
            // The kept series' own read marks and place stay; the other's add to them.
            next.copy(readChapters = next.readChapters + readBefore.intersect(current.chapters.map { it.id }.toSet()),
                progress = state.progress[keep.id]?.let { next.progress + (keep.id to it) } ?: next.progress)
        }
    }

/** A series found on another source for a move: how well its title matches, 0 to 1. */
data class MangaMigrationMatch(val book: MangaBook, val score: Double)

/**
 * The search hits worth offering for moving [book]: the best title matches first (against its title and alternate titles), at
 * most [perSource] from each source, and never the series itself.
 */
fun mangaMigrationMatches(book: MangaBook, found: List<MangaBook>, perSource: Int = 3): List<MangaMigrationMatch> {
    val titles = listOf(book.title) + book.parserData?.alternateTitles.orEmpty()
    return found.filter { it.id != book.id && it.sourceId != book.sourceId }
        .map { hit -> MangaMigrationMatch(hit, (titles.maxOf { mangaTitleScore(it, hit.title) }).coerceAtLeast(
            hit.parserData?.alternateTitles.orEmpty().maxOfOrNull { mangaTitleScore(it, book.title) } ?: 0.0)) }
        .filter { it.score > 0.0 }
        .groupBy { it.book.sourceId }.values.flatMap { list -> list.sortedByDescending { it.score }.take(perSource) }
        .sortedByDescending { it.score }
}

/** What a move carries over, for the confirmation: [read] chapters found again on the new source, of the [readBefore] read. */
data class MangaMigrationSummary(val read: Int, val readBefore: Int, val chapters: Int)

/** The key a chapter is found again by on another source: its number when it has one, else its title. */
private fun MangaChapter.matchKey(): String = if (number > 0f) "n:$number" else "t:" + title.lowercase().trim()

fun mangaMigrationSummary(from: MangaBook, to: MangaBook, state: MangaLibraryState): MangaMigrationSummary {
    val readKeys = from.chapters.filter { it.id in state.readChapters }.map { it.matchKey() }.toSet()
    return MangaMigrationSummary(to.chapters.map { it.matchKey() }.distinct().count { it in readKeys }, from.chapters.count { it.id in state.readChapters }, to.chapters.size)
}

/**
 * Moves [from] onto [to] (its details, with chapters, from another source): the new series takes the old one's place in the
 * library with its added date, collections, reading-list links and read chapters (matched by chapter number, else by title);
 * the reading place moves to the same chapter there. The old series' downloads and new marks go, as they belong to its source.
 */
fun MangaLibraryState.migrated(from: MangaBook, to: MangaBook): MangaLibraryState {
    require(from.id != to.id) { "Choose a series on another source" }
    val oldIds = from.chapters.map { it.id }.toSet()
    val readKeys = from.chapters.filter { it.id in readChapters }.map { it.matchKey() }.toSet()
    val byKey = to.chapters.groupBy { it.matchKey() }
    val read = to.chapters.filter { it.matchKey() in readKeys }.map { it.id }
    val place = progress[from.id]?.let { saved ->
        val chapter = from.chapters.find { it.id == saved.chapterId } ?: return@let null
        val target = byKey[chapter.matchKey()]?.firstOrNull() ?: return@let null
        // The new source's pages may differ, so an unfinished place starts its chapter over at the same page or less.
        saved.copy(bookId = to.id, chapterId = target.id, pageOffsetPx = 0)
    }
    val moved = to.copy(addedAtMs = from.addedAtMs)
    return copy(
        books = books.map { if (it.id == from.id) moved else it }.let { list -> if (list.any { it.id == to.id }) list.distinctBy { it.id } else list + moved },
        readChapters = readChapters - oldIds + read,
        newChapters = newChapters - oldIds,
        downloads = downloads.filterKeys { it !in oldIds },
        progress = (progress - from.id).let { if (place != null) it + (to.id to place) else it },
        collections = collections.map { if (from.id in it.bookIds) it.copy(bookIds = it.bookIds - from.id + to.id) else it },
        tracks = tracks[from.id]?.let { tracks - from.id + (to.id to it) } ?: tracks,
        trackFailures = trackFailures - from.id,
        updateFailures = updateFailures - from.id,
        activeBookId = if (activeBookId == from.id) to.id else activeBookId,
        activeChapterId = if (activeBookId == from.id) place?.chapterId else activeChapterId,
    )
}
