package com.cosmotune.video.ui.home

import com.cosmotune.cache.CacheCodec
import com.cosmotune.cache.PageCache
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.cosmotune.video.home.HomePlanner

/** Home's kiosks and the first page of each, as [HomeCache] keeps them on the disk for the next start. */
@kotlinx.serialization.Serializable
internal class SavedHome(val regionKey: String, val kiosks: List<Kiosk>, val pages: Map<String, SavedKioskPage>)

@kotlinx.serialization.Serializable
internal class SavedKioskPage(val items: List<VideoItem>, val next: String? = null)

/** What the kiosk part of Home shows: the placeholders, the cards, or the empty state with "Try again". */
internal enum class KioskPhase { LOADING, READY, FAILED }

/** The pages of one kiosk that were loaded, when the first of them came, and the token of the next one (null: the end). */
internal class KioskPages(val items: List<VideoItem>, val next: PageToken?, val loadedAtMs: Long, val consumed: Set<String> = emptySet())

/**
 * What Home keeps while it is off screen (spec 5.2: the related lists are "fetched once per launch"): the offered kiosks, the pages
 * of each kiosk that were loaded, and the related list of each video that "Up next for you" and the Shorts shelf draw on. Coming
 * back from a video shows the same list in the same place at once instead of asking again; a kiosk's pages are kept for
 * [PAGES_TTL_MS]. A new [regionKey] (the region or the content language changed) forgets everything: the charts are another's.
 *
 * Touched only from the coroutines of one [HomeModel] (the page's own scope), one at a time, so it takes no lock.
 */
internal class HomeCache(
    /** Whether the cache keeps a copy on the disk ([PageCache.shared]'s folder) for the next start: the process's one does. */
    private val persistent: Boolean = false,
) {
    var regionKey: String? = null
        private set
    /** The region whose disk copy was read already (it is read once per region and run). */
    private var restoredFor: String? = null
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

    /**
     * Fills an empty cache with the copy kept on the disk for [key] (the kiosks and each kiosk's first page), once per run. The
     * copy's pages count as stale, so they are shown at once and fetched again.
     */
    suspend fun restoreSaved(key: String) {
        if (!persistent || restoredFor == key) return
        restoredFor = key
        if (kiosks != null) return
        val saved = PageCache.shared.readSaved(SAVED_KEY, SAVED_CODEC)?.second ?: return
        if (saved.regionKey != key || kiosks != null) return
        reset(key)
        kiosks = saved.kiosks
        for ((id, page) in saved.pages) if (id !in pages) pages[id] = KioskPages(page.items, page.next?.let(::PageToken), loadedAtMs = Long.MIN_VALUE / 2)
    }

    /** Keeps the kiosks and the first page of each kiosk on the disk, for the next start. */
    fun save() {
        if (!persistent) return
        val key = regionKey ?: return
        val offered = kiosks ?: return
        val firstPages = pages.mapValues { (_, page) -> SavedKioskPage(page.items.take(SAVED_ITEMS), page.next?.value) }
        PageCache.shared.writeSaved(SAVED_KEY, SavedHome(key, offered, firstPages), SAVED_CODEC)
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

        /** The cache of the process: one Home at a time. Its kiosks and first pages are kept on the disk too. */
        val shared = HomeCache(persistent = true)

        private const val SAVED_KEY = "video:home"
        private const val SAVED_ITEMS = 60
        private val SAVED_CODEC = CacheCodec.json(SavedHome.serializer())
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
    var initialError by mutableStateOf(false); private set
    var moreError by mutableStateOf(false); private set
    var repeatedPage by mutableStateOf(false); private set
    val hasMore get() = next != null && items.size < MAX_ITEMS
    val exhausted get() = phase == KioskPhase.READY && !hasMore
    private val consumed = HashSet<String>()
    private var emptyPages = 0

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
        // Pages past their ten minutes are shown too (start then fetches the first page again): a return is never blank.
        val cached = cache.pages[id] ?: return
        kiosks = known
        selectedId = id
        items = cached.items
        next = cached.next
        consumed.clear(); consumed.addAll(cached.consumed)
        phase = KioskPhase.READY
    }

    private fun freshPages(id: String): KioskPages? =
        cache.pages[id]?.takeIf { clock() - it.loadedAtMs in 0 until HomeCache.PAGES_TTL_MS }

    /**
     * Starts Home's kiosk part: the kiosks are those of the cache or the source's, and the selected one is [preferredId] when it is
     * offered, else the first. [regionKey] names the region and language the answers are for (see [HomeCache.reset]).
     */
    fun start(regionKey: String, preferredId: String) {
        lastRegionKey = regionKey
        lastPreferred = preferredId
        if (cache.regionKey != regionKey) { relatedJob?.cancel(); related = emptyMap() }
        cache.reset(regionKey)
        initialError = false; moreError = false; repeatedPage = false
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
            // After a restart: the kiosks and pages of the last run, at once, while the first page is fetched again.
            cache.restoreSaved(regionKey)
            cache.kiosks?.let { saved ->
                kiosks = saved
                select(pick(saved, preferredId))
                return@launch
            }
            val offered = attempt { source.kiosks() }
            if (offered.isNullOrEmpty()) {
                initialError = true
                phase = KioskPhase.FAILED
                return@launch
            }
            cache.kiosks = offered.take(32)
            kiosks = cache.kiosks
            val id = pick(requireNotNull(cache.kiosks), preferredId)
            selectedId = id
            loadFirstPage(id)
        }
    }

    /** Shows kiosk [id] (a chip was tapped): its cached pages when they are fresh, else its first page from the source. */
    fun select(id: String) {
        job?.cancel()
        cancelMore()
        selectedId = id
        initialError = false; moreError = false; repeatedPage = false; emptyPages = 0; consumed.clear()
        val cached = cache.pages[id]
        if (cached != null) {
            items = cached.items
            next = cached.next
            consumed.addAll(cached.consumed)
            phase = KioskPhase.READY
            // Past its ten minutes the list stays on screen while its first page is fetched again, then swapped in.
            if (freshPages(id) == null) job = scope.launch { loadFirstPage(id) }
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
            job?.cancel(); cancelMore(); initialError = false; moreError = false; repeatedPage = false
            if (items.isEmpty()) phase = KioskPhase.LOADING
            job = scope.launch { loadFirstPage(id) }
        }
    }

    fun retryMore() { moreError = false; loadMore() }

    /** Asks for the next page of the selected kiosk, when there is one and none is on its way. A failure keeps the token for the next try. */
    fun loadMore() {
        val token = next ?: return
        val id = selectedId ?: return
        if (phase != KioskPhase.READY || loadingMore || moreError || !hasMore) return
        loadingMore = true
        val generation = ++moreGeneration
        moreJob = scope.launch {
            try {
                val page = attempt { source.kiosk(id, token) }
                if (page != null) {
                    if (generation != moreGeneration || id != selectedId) return@launch
                    val merged = (items + page.items).distinctBy { it.id }.take(MAX_ITEMS)
                    emptyPages = if (merged.size == items.size) emptyPages + 1 else 0
                    consumed += token.value
                    repeatedPage = page.next?.value in consumed || emptyPages >= 2
                    items = merged
                    next = page.next.takeUnless { repeatedPage || consumed.size >= 128 || items.size >= MAX_ITEMS }
                    cache.pages[id] = KioskPages(merged, next, cache.pages[id]?.loadedAtMs ?: clock(), consumed.toSet())
                    trimPages(id)
                } else if (generation == moreGeneration) moreError = true
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
        val missing = ids.distinct().take(HomePlanner.RECENT_WATCHES).filter { id ->
            id !in cache.related && (cache.relatedFailedAtMs[id]?.let { now - it >= HomeCache.RELATED_RETRY_MS } ?: true)
        }
        if (missing.isEmpty()) return
        relatedJob = scope.launch {
            for (chunk in missing.chunked(3)) {
                val answers = chunk.map { id -> async { id to attempt { source.related(id) } } }.awaitAll()
                currentCoroutineContext().ensureActive()
                for ((id, list) in answers) {
                    if (list != null) cache.related[id] = list.take(150) else cache.relatedFailedAtMs[id] = clock()
                }
                while (cache.related.size > 64) cache.related.remove(cache.related.keys.first())
                while (cache.relatedFailedAtMs.size > 64) cache.relatedFailedAtMs.remove(cache.relatedFailedAtMs.keys.first())
                related = knownRelated(ids)
            }
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
        val cards = page?.items?.distinctBy { it.id }?.take(MAX_ITEMS).orEmpty()
        if (page == null) {
            initialError = true
            phase = if (items.isEmpty()) KioskPhase.FAILED else KioskPhase.READY
            return
        }
        initialError = false; consumed.clear(); emptyPages = 0
        cache.pages[id] = KioskPages(cards, page.next, clock())
        trimPages(id)
        cache.save()
        items = cards
        next = page.next
        phase = KioskPhase.READY
    }

    private fun pick(offered: List<Kiosk>, preferredId: String): String =
        (offered.firstOrNull { it.id == preferredId } ?: offered.first()).id

    private fun trimPages(currentId: String) {
        while (cache.pages.size > 8) cache.pages.keys.firstOrNull { it != currentId }?.let { cache.pages.remove(it) } ?: break
    }

    /** [block]'s answer, or null when the source failed; a cancellation goes on (a stale answer must not land). */
    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block().also { currentCoroutineContext().ensureActive() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        null
    }

    companion object { private const val MAX_ITEMS = 600 }
}
