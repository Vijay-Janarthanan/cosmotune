package com.cosmotune.video.comments

import com.cosmotune.video.model.Comment
import com.cosmotune.video.model.CommentPage
import com.cosmotune.video.model.CommentSort
import com.cosmotune.video.model.PageToken
import com.cosmotune.video.source.VideoSource
import com.cosmotune.video.source.VideoSourceError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.jvm.Synchronized

/*
 * The paging behind the comments panel and the watch page's comments tile (plan task Y-23, spec 5.9). Pure: the screens draw
 * `CommentsState`, a `CommentsLoader` says where a page comes from (the source's comments, or the replies of one comment).
 */

/** Where the pages of one list come from: [page] of order [sort] after [token] (null: the first page); [fresh] asks past any cache. */
fun interface CommentsLoader {
    suspend fun page(sort: CommentSort, token: PageToken?, fresh: Boolean): CommentPage
}

/** Why a page did not load, in the words the screen has: no connection, the bot check, a source that changed, anything else. */
enum class CommentsFailure {
    NETWORK, BOT_CHECK, SOURCE_CHANGED, OTHER;

    companion object {
        /** The kind of [error]: the source wraps what goes wrong in a [VideoSourceError], anything else is [OTHER]. */
        fun of(error: Throwable): CommentsFailure = when (error) {
            is VideoSourceError.Network -> NETWORK
            is VideoSourceError.BotCheck -> BOT_CHECK
            is VideoSourceError.Parsing -> SOURCE_CHANGED
            else -> OTHER
        }
    }
}

/**
 * What a list of comments shows now. [loading] is a first page on its way (the list is empty; a page that came back with nothing
 * but has another after it stays loading), [loadingMore] a later one. [failure] is a first page that did not load; [moreFailure] a
 * later one (the comments stay). [disabled] is "Comments are turned off". [sortable] and [total] are the first page's: they stay
 * while the other order loads and through the pages that do not carry them. [hasMore] is another page to ask for.
 */
data class CommentsState(
    val items: List<Comment> = emptyList(),
    val sort: CommentSort = CommentSort.TOP,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val failure: CommentsFailure? = null,
    val moreFailure: CommentsFailure? = null,
    val disabled: Boolean = false,
    val sortable: Boolean = false,
    val total: Long? = null,
    val hasMore: Boolean = false,
) {
    /** The video has no comments (yet): loaded, nothing failed, not turned off, none to show. */
    val empty: Boolean get() = !loading && failure == null && !disabled && items.isEmpty()
}

/**
 * The comments of one list, page by page. Call [start] once, [loadMore] near the end of the list, [setSort] for the Top and Newest
 * chips, [retry] for "Try again" and [close] when the list goes away. Every call comes from one thread (the UI's); the pages are
 * asked in [scope]. A comment that comes twice (the source's pages overlap now and then) is kept once, because the list is keyed by
 * its id; a page that brings only repeats is followed at once by the next, at most [MAX_EMPTY_PAGES] times.
 */
class CommentsFeed(
    private val loader: CommentsLoader,
    private val scope: CoroutineScope,
    initialSort: CommentSort = CommentSort.TOP,
) {
    private val mutable = MutableStateFlow(CommentsState(sort = initialSort))

    /** What the list shows now. */
    val state: StateFlow<CommentsState> get() = mutable

    private var job: Job? = null
    private var started = false
    private var generation = 0
    private var next: PageToken? = null
    private val seen = HashSet<String>()

    /** Asks for the first page, once. */
    fun start() {
        if (started) return
        started = true
        reload(fresh = false)
    }

    /** Shows the list in order [sort], from its first page; the same order again changes nothing. */
    fun setSort(sort: CommentSort) {
        if (sort == mutable.value.sort) return
        mutable.update { it.copy(sort = sort) }
        started = true
        reload(fresh = false)
    }

    /** Asks for the next page, unless one is on its way, none is left or the last one failed (that waits for [retry]). */
    fun loadMore() {
        val token = next ?: return
        val s = mutable.value
        if (!started || s.loading || s.loadingMore || s.failure != null || s.moreFailure != null) return
        mutable.update { it.copy(loadingMore = true) }
        launchPages(generation, token, first = false, fresh = false)
    }

    /** "Try again": the first page again, past the cache, if it failed; else the page after the comments if that one failed. */
    fun retry() {
        val s = mutable.value
        when {
            s.failure != null -> reload(fresh = true)
            s.moreFailure != null -> {
                val token = next ?: return
                mutable.update { it.copy(loadingMore = true, moreFailure = null) }
                launchPages(generation, token, first = false, fresh = false)
            }
        }
    }

    /** Stops asking: an answer that arrives later changes nothing. */
    fun close() {
        generation++
        job?.cancel()
        job = null
    }

    private fun reload(fresh: Boolean) {
        job?.cancel()
        val gen = ++generation
        seen.clear()
        next = null
        mutable.update {
            it.copy(items = emptyList(), loading = true, loadingMore = false, failure = null, moreFailure = null, disabled = false, hasMore = false)
        }
        launchPages(gen, null, first = true, fresh = fresh)
    }

    private fun launchPages(gen: Int, from: PageToken?, first: Boolean, fresh: Boolean) {
        job = scope.launch {
            var token = from
            var firstPage = first
            var empty = 0
            try {
                do {
                    val page = loader.page(mutable.value.sort, token, fresh && firstPage)
                    if (gen != generation) return@launch
                    val added = page.items.filter { seen.add(it.id) }
                    token = if (page.disabled) null else page.next
                    next = token
                    val keepGoing = token != null && added.isEmpty() && ++empty < MAX_EMPTY_PAGES
                    val wasFirst = firstPage
                    mutable.update { s ->
                        s.copy(
                            items = if (wasFirst) added else s.items + added,
                            loading = wasFirst && keepGoing,
                            loadingMore = !wasFirst && keepGoing,
                            failure = null,
                            moreFailure = null,
                            disabled = if (wasFirst) page.disabled else s.disabled,
                            sortable = s.sortable || page.sortable,
                            total = page.totalCount ?: s.total,
                            hasMore = token != null,
                        )
                    }
                    firstPage = false
                } while (keepGoing)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (gen != generation) return@launch
                val kind = CommentsFailure.of(e)
                // with nothing to show, a failed page is a failure of the list (a page of repeats before it counts as nothing)
                mutable.update { s ->
                    if (s.items.isEmpty()) s.copy(loading = false, loadingMore = false, failure = kind)
                    else s.copy(loading = false, loadingMore = false, moreFailure = kind)
                }
            }
        }
    }

    companion object {
        /** Pages that bring nothing new are followed this many times, no more. */
        const val MAX_EMPTY_PAGES = 3
    }
}

/**
 * The first pages of the last [CAPACITY] videos' comments, kept for [TTL_MS]: the watch page's tile loads a page to show the first
 * comment, and the panel the person opens next wants the same page. Only first pages go in (a later page needs a token the
 * list holds). Thread-safe; [clock] is the wall clock in milliseconds.
 */
class CommentsPageCache(private val clock: () -> Long) {
    private class Entry(val page: CommentPage, val atMs: Long)

    private val entries = LinkedHashMap<String, Entry>()

    /** The cached first page of [videoId] in order [sort], or null when there is none or it is older than [TTL_MS]. */
    @Synchronized
    fun get(videoId: String, sort: CommentSort): CommentPage? {
        val key = key(videoId, sort)
        val entry = entries.remove(key) ?: return null
        val age = clock() - entry.atMs
        if (age < 0 || age >= TTL_MS) return null
        entries[key] = entry   // the newest again: a page in use is the last to go
        return entry.page
    }

    /** Keeps [page] as the first page of [videoId] in order [sort]; the oldest entry goes when there are more than [CAPACITY]. */
    @Synchronized
    fun put(videoId: String, sort: CommentSort, page: CommentPage) {
        val key = key(videoId, sort)
        entries.remove(key)
        entries[key] = Entry(page, clock())
        while (entries.size > CAPACITY) entries.remove(entries.keys.first())
    }

    /** Forgets the first page of [videoId] in order [sort]. */
    @Synchronized
    fun invalidate(videoId: String, sort: CommentSort) {
        entries.remove(key(videoId, sort))
    }

    private fun key(videoId: String, sort: CommentSort) = "$videoId|$sort"

    companion object {
        /** How many videos are kept. */
        const val CAPACITY = 8

        /** How long a page is good for: 10 minutes. */
        const val TTL_MS = 10 * 60_000L
    }
}

/** The comments of [videoId] from [source]; the first page of each order goes through [cache] unless the ask is [fresh]. */
class CachedCommentsLoader(
    private val source: VideoSource,
    private val videoId: String,
    private val cache: CommentsPageCache,
) : CommentsLoader {
    override suspend fun page(sort: CommentSort, token: PageToken?, fresh: Boolean): CommentPage {
        if (token != null) return source.comments(videoId, sort, token)
        if (!fresh) cache.get(videoId, sort)?.let { return it }
        return source.comments(videoId, sort, null).also { cache.put(videoId, sort, it) }
    }
}

/** The replies that [first] opens, page by page (replies have one order). */
class RepliesLoader(private val source: VideoSource, private val first: PageToken) : CommentsLoader {
    override suspend fun page(sort: CommentSort, token: PageToken?, fresh: Boolean): CommentPage = source.replies(token ?: first)
}

/** What the watch page's comments tile shows (spec 5.4): nothing yet, the first comment and the total, nothing at all, or a failure. */
sealed interface CommentsPreview {
    /** The first page is on its way. */
    data object Loading : CommentsPreview

    /** Loaded: [first] is the top comment (null: there are none yet) and [total] the count when the source gives it. */
    class Ready(val first: Comment?, val total: Long?) : CommentsPreview

    /** Comments are turned off: the tile is not shown. */
    data object Off : CommentsPreview

    /** The page did not load: the tile says so, and the panel tries again when it opens. */
    data object Failed : CommentsPreview
}

/** The preview of this list's first page in the top order. A cancelled load is not a failed one: the cancellation goes on. */
suspend fun CommentsLoader.preview(): CommentsPreview = try {
    val page = page(CommentSort.TOP, null, false)
    if (page.disabled) CommentsPreview.Off else CommentsPreview.Ready(page.items.firstOrNull(), page.totalCount)
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    CommentsPreview.Failed
}
