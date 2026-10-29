package com.cosmotune.video.ui.home

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cosmotune.video.model.Kiosk
import com.cosmotune.video.model.ListItem
import com.cosmotune.video.model.PageToken
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.source.VideoSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

/** What the kiosk part of Home shows: the placeholders, the cards, or the empty state with "Try again". */
internal enum class KioskPhase { LOADING, READY, FAILED }

/** The pages of one kiosk that were loaded, when the first of them came, and the token of the next one (null: the end). */
internal class KioskPages(val items: List<VideoItem>, val next: PageToken?, val loadedAtMs: Long)

/**
 * What Home keeps while it is off screen (spec 5.2: the related lists are "fetched once per launch"): the offered kiosks, the pages
 * of each kiosk that were loaded, and the related list of each video that "Up next for you" and the Shorts shelf draw on. Coming
 * back from a video shows the same list in the same place at once instead of asking again; a kiosk's pages are kept for
 * [PAGES_TTL_MS]. A new [regionKey] (the region or the content language changed) forgets everything: the charts are another's.
 *
 * Touched only from the coroutines of one [HomeModel] (the page's own scope), one at a time, so it takes no lock.
 */
internal class HomeCache {
    var regionKey: String? = null
        private set
    var kiosks: List<Kiosk>? = null
    val pages = HashMap<String, KioskPages>()
    val related = HashMap<String, List<ListItem>>()
    val relatedFailedAtMs = HashMap<String, Long>()

    /** Forgets everything when [key] is not the region key the cache was filled for. */
    fun reset(key: String) {
        if (key == regionKey) return
        regionKey = key
        kiosks = null
        pages.clear()
        related.clear()
        relatedFailedAtMs.clear()
    }

    /** Forgets everything (tests). */
    fun clear() {
        regionKey = null
        kiosks = null
        pages.clear()
        related.clear()
        relatedFailedAtMs.clear()
    }

    companion object {
        /** A kiosk's loaded pages are shown again for this long without asking the source: 10 minutes. */
        const val PAGES_TTL_MS = 10 * 60_000L

        /** A video whose related list could not be fetched is not asked for again for this long: 10 minutes. */
        const val RELATED_RETRY_MS = 10 * 60_000L

        /** The cache of the process: one Home at a time. */
        val shared = HomeCache()
    }
}

/**
 * Home's data that comes from the source (spec 5.2), kept apart from the screen so it can be run without one: the kiosks on offer
 * and which one is selected, the selected kiosk's cards (the first page, then more at the end), and the related lists of the
 * videos watched last. Every call returns at once; the work runs in [scope] (the page's own, so it ends with the page), and a
 * result that arrives after the user chose another kiosk is dropped (the job is cancelled).
 *
 * The kiosks are only the ones [VideoSource.kiosks] offers, by the ids it gives: the removed "Trending" chart is never named here.
 * What the cache already holds is shown synchronously, from the first composition.
 */
@Stable
internal class HomeModel(
    private val source: VideoSource,
    private val scope: CoroutineScope,
    private val cache: HomeCache = HomeCache.shared,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The kiosks on offer, or null until the source has answered. */
    var kiosks: List<Kiosk>? by mutableStateOf(null)
        private set

    /** The id of the selected kiosk, or null while the kiosks are not known. */
    var selectedId: String? by mutableStateOf(null)
        private set

    /** What the cards below the shelves show. */
    var phase: KioskPhase by mutableStateOf(KioskPhase.LOADING)
        private set

    /** The selected kiosk's cards so far, without repeats. */
    var items: List<VideoItem> by mutableStateOf(emptyList())
        private set

    /** Whether the next page is on its way (a card skeleton stands at the end of the list). */
    var loadingMore: Boolean by mutableStateOf(false)
        private set

    /** The related list of each of the videos [loadRelated] was asked about that has one, by video id. */
    var related: Map<String, List<ListItem>> by mutableStateOf(emptyMap())
        private set

    private var next: PageToken? = null
    private var job: Job? = null
    private var moreJob: Job? = null
    private var relatedJob: Job? = null
    private var moreGeneration = 0
    private var lastRegionKey: String = ""
    private var lastPreferred: String = ""

    /**
     * Fills the state from the cache, at once and without starting anything: when Home comes back from a video the first frame already
     * holds the kiosks, the preferred kiosk's cards (if their pages are fresh) and the related lists of [relatedIds], so the list
     * is there for the saved scroll position to return to. [start] and [loadRelated] then confirm it, or fetch what is missing.
     */
    fun restore(regionKey: String, preferredId: String, relatedIds: List<String>) {
        cache.reset(regionKey)
        related = knownRelated(relatedIds)
        val known = cache.kiosks ?: return
        val id = pick(known, preferredId)
        val cached = freshPages(id) ?: return
        kiosks = known
        selectedId = id
        items = cached.items
        next = cached.next
        phase = KioskPhase.READY
    }

    private fun freshPages(id: String): KioskPages? =
        cache.pages[id]?.takeIf { clock() - it.loadedAtMs < HomeCache.PAGES_TTL_MS }

    /**
     * Starts Home's kiosk part: the kiosks are those of the cache or the source's, and the selected one is [preferredId] when it is
     * offered, else the first. [regionKey] names the region and language the answers are for (see [HomeCache.reset]).
     */
    fun start(regionKey: String, preferredId: String) {
        lastRegionKey = regionKey
        lastPreferred = preferredId
        cache.reset(regionKey)
        job?.cancel()
        cancelMore()
        val known = cache.kiosks
        if (known != null) {
            kiosks = known
            select(pick(known, preferredId))
            return
        }
        kiosks = null
        selectedId = null
        items = emptyList()
        next = null
        phase = KioskPhase.LOADING
        job = scope.launch {
            val offered = attempt { source.kiosks() }
            if (offered.isNullOrEmpty()) {
                phase = KioskPhase.FAILED
                return@launch
            }
            cache.kiosks = offered
            kiosks = offered
            val id = pick(offered, preferredId)
            selectedId = id
            loadFirstPage(id)
        }
    }

    /** Shows kiosk [id] (a chip was tapped): its cached pages when they are fresh, else its first page from the source. */
    fun select(id: String) {
        job?.cancel()
        cancelMore()
        selectedId = id
        val cached = freshPages(id)
        if (cached != null) {
            items = cached.items
            next = cached.next
            phase = KioskPhase.READY
            return
        }
        items = emptyList()
        next = null
        phase = KioskPhase.LOADING
        job = scope.launch { loadFirstPage(id) }
    }

    /** "Try again": the kiosks again when they never came, else the selected kiosk's first page again. */
    fun retry() {
        val id = selectedId
        if (kiosks == null || id == null) {
            start(lastRegionKey, lastPreferred)
        } else {
            cache.pages.remove(id)
            select(id)
        }
    }

    /** Asks for the next page of the selected kiosk, when there is one and none is on its way. A failure keeps the token for the next try. */
    fun loadMore() {
        val token = next ?: return
        val id = selectedId ?: return
        if (phase != KioskPhase.READY || loadingMore) return
        loadingMore = true
        val generation = ++moreGeneration
        moreJob = scope.launch {
            try {
                val page = attempt { source.kiosk(id, token) }
                if (page != null) {
                    val merged = (items + page.items).distinctBy { it.id }
                    items = merged
                    next = page.next
                    cache.pages[id] = KioskPages(merged, page.next, cache.pages[id]?.loadedAtMs ?: clock())
                }
            } finally {
                if (generation == moreGeneration) loadingMore = false
            }
        }
    }

    /**
     * Sets [related] to what is known of the videos [ids] and fetches the related list of the others (`related(id)`, three
     * at a time at most), once for the launch: an answer is cached, a failure is not asked again for [HomeCache.RELATED_RETRY_MS].
     */
    fun loadRelated(ids: List<String>) {
        relatedJob?.cancel()
        related = knownRelated(ids)
        val now = clock()
        val missing = ids.filter { id ->
            id !in cache.related && (cache.relatedFailedAtMs[id]?.let { now - it >= HomeCache.RELATED_RETRY_MS } ?: true)
        }
        if (missing.isEmpty()) return
        relatedJob = scope.launch {
            val answers = missing.map { id -> async { id to attempt { source.related(id) } } }.awaitAll()
            for ((id, list) in answers) {
                if (list != null) cache.related[id] = list else cache.relatedFailedAtMs[id] = clock()
            }
            related = knownRelated(ids)
        }
    }

    /** Drops the page on its way: its answer must not land, and nothing is loading any more. */
    private fun cancelMore() {
        moreJob?.cancel()
        moreGeneration++
        loadingMore = false
    }

    private fun knownRelated(ids: List<String>): Map<String, List<ListItem>> =
        ids.mapNotNull { id -> cache.related[id]?.let { id to it } }.toMap()

    private suspend fun loadFirstPage(id: String) {
        val page = attempt { source.kiosk(id, null) }
        val cards = page?.items?.distinctBy { it.id }.orEmpty()
        if (page == null || cards.isEmpty()) {
            phase = KioskPhase.FAILED
            return
        }
        cache.pages[id] = KioskPages(cards, page.next, clock())
        items = cards
        next = page.next
        phase = KioskPhase.READY
    }

    private fun pick(offered: List<Kiosk>, preferredId: String): String =
        (offered.firstOrNull { it.id == preferredId } ?: offered.first()).id

    /** [block]'s answer, or null when the source failed; a cancellation goes on (a stale answer must not land). */
    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}
