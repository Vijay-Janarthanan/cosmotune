package com.cosmotune.manga.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One chapter in the download queue: waiting, being fetched ([done] of [total] pages) or failed with [error]. */
data class MangaQueued(val book: MangaBook, val chapter: MangaChapter, val done: Int = 0, val total: Int = 0, val error: String? = null)

/** The queue as the Downloads page shows it: in order, the one being fetched first; [paused] holds it after the current page. */
data class MangaQueueState(val items: List<MangaQueued> = emptyList(), val paused: Boolean = false, val running: String? = null) {
    val waiting: Int get() = items.count { it.error == null }
    fun has(chapterId: String) = items.any { it.chapter.id == chapterId && it.error == null }
}

/**
 * The chapters to fetch ahead of the reader: the next [count] chapters of the same edition after [current] that are neither read
 * nor on the device. Local comics have nothing to fetch.
 */
fun mangaChaptersAhead(book: MangaBook, current: MangaChapter, state: MangaLibraryState, count: Int): List<MangaChapter> {
    if (count <= 0 || book.sourceId == "local") return emptyList()
    val edition = book.listedChapters().filter { it.language == current.language && it.branch == current.branch && it.scanlator == current.scanlator }
    val index = edition.indexOfFirst { it.id == current.id }
    if (index < 0) return emptyList()
    return edition.drop(index + 1).filter { it.id !in state.readChapters && !state.downloads.containsKey(it.id) }.take(count)
}

/** The new chapters an update found that should be fetched automatically: in the library, not local, not yet on the device. */
fun mangaNewToDownload(state: MangaLibraryState, fresh: Set<String>): List<Pair<MangaBook, MangaChapter>> =
    state.books.filter { it.sourceId != "local" }.flatMap { book -> book.listedChapters().filter { it.id in fresh && !state.downloads.containsKey(it.id) }.map { book to it } }

/**
 * The download queue: one chapter at a time, in order, with pause and resume, retry for a failed chapter and cancel. It also
 * runs the automatic rules of Settings › Manga › Downloads: new chapters found by an update, the next chapters ahead of the
 * reader, and removing a chapter's download once it is read.
 */
class MangaDownloadQueue internal constructor(private val store: MangaStore, private val scope: CoroutineScope,
    private val fetch: suspend (MangaChapter, (Int, Int) -> Unit) -> Unit, private val remove: suspend (Collection<String>) -> Unit) {
    private val mutable = MutableStateFlow(MangaQueueState())
    val state = mutable.asStateFlow()
    private var worker: Job? = null
    private var current: Job? = null

    fun enqueue(book: MangaBook, chapters: List<MangaChapter>) {
        val wanted = chapters.filter { it.sourceId != "local" && !store.state.value.downloads.containsKey(it.id) }
        if (wanted.isEmpty()) return
        mutable.update { queue ->
            val known = queue.items.map { it.chapter.id }.toSet()
            queue.copy(items = queue.items.map { if (it.chapter.id in wanted.map { c -> c.id } && it.error != null) it.copy(error = null) else it } +
                wanted.filter { it.id !in known }.map { MangaQueued(book, it) })
        }
        kick()
    }

    fun pause() { mutable.update { it.copy(paused = true) }; current?.cancel() }
    fun resume() { mutable.update { it.copy(paused = false) }; kick() }
    fun cancel(chapterId: String) {
        if (mutable.value.running == chapterId) current?.cancel()
        mutable.update { queue -> queue.copy(items = queue.items.filterNot { it.chapter.id == chapterId }) }
        kick()
    }
    fun cancelAll() { mutable.update { it.copy(items = emptyList()) }; current?.cancel() }
    fun retry(chapterId: String) { mutable.update { queue -> queue.copy(items = queue.items.map { if (it.chapter.id == chapterId) it.copy(error = null, done = 0) else it }) }; kick() }

    @Synchronized private fun kick() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            while (true) {
                val queue = mutable.value
                if (queue.paused) break
                val next = queue.items.firstOrNull { it.error == null } ?: break
                val id = next.chapter.id
                mutable.update { it.copy(running = id) }
                var problem: Exception? = null
                val job = scope.launch {
                    try { fetch(next.chapter) { done, total -> mutable.update { q -> q.copy(items = q.items.map { if (it.chapter.id == id) it.copy(done = done, total = total) else it }) } } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { problem = failure }
                }
                current = job
                job.join()
                current = null
                val error: Throwable? = problem ?: if (job.isCancelled) CancellationException() else null
                mutable.update { q ->
                    when {
                        error == null -> q.copy(running = null, items = q.items.filterNot { it.chapter.id == id })
                        // A pause or a cancel leaves the chapter as it was (waiting, or gone); its fetched pages are kept for the next try.
                        error is CancellationException -> q.copy(running = null)
                        else -> q.copy(running = null, items = q.items.map { if (it.chapter.id == id) it.copy(error = error.message ?: "Download failed") else it })
                    }
                }
            }
        }
    }

    /** Starts the automatic rules; the library as it is now is the baseline. */
    fun watch() = scope.launch {
        var fresh = store.state.value.newChapters
        var read = store.state.value.readChapters
        var place = store.state.value.progress.mapValues { it.value.chapterId }
        val toDelete = LinkedHashSet<String>()
        store.state.collect { state ->
            val added = state.newChapters - fresh
            val finished = state.readChapters - read
            val moved = state.progress.filter { (book, saved) -> place[book] != saved.chapterId }
            fresh = state.newChapters; read = state.readChapters; place = state.progress.mapValues { it.value.chapterId }
            if (state.autoDownloadNew && added.isNotEmpty()) mangaNewToDownload(state, added).groupBy({ it.first }, { it.second }).forEach { (book, list) -> enqueue(book, list) }
            if (state.downloadAhead > 0 && MangaNetwork.aheadAllowed(state)) moved.values.forEach { saved ->
                val book = state.books.find { it.id == saved.bookId } ?: return@forEach
                val chapter = book.chapters.find { it.id == saved.chapterId } ?: return@forEach
                enqueue(book, mangaChaptersAhead(book, chapter, state, state.downloadAhead))
            }
            if (state.deleteAfterRead) {
                // A chapter finished in the reader stays until the reader has moved off it.
                toDelete += finished.filter { state.downloads.containsKey(it) }
                val gone = toDelete.filter { it != state.activeChapterId }
                if (gone.isNotEmpty()) {
                    toDelete -= gone.toSet()
                    try { remove(gone.filter { it in state.readChapters }) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
                }
            } else toDelete.clear()
        }
    }
}
