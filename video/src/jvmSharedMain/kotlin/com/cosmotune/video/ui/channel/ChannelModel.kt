package com.cosmotune.video.ui.channel

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cosmotune.video.model.ChannelPage
import com.cosmotune.video.model.ChannelTab
import com.cosmotune.video.model.ListItem
import com.cosmotune.video.model.Page
import com.cosmotune.video.model.PageToken
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.source.VideoSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/*
 * What the channel and the playlist pages load, apart from any window (plan task Y-20): the sections of a channel page, a paged list
 * that loads the first page, the next ones and the rest, the channel's model, and the small cache that lets a page which was left and
 * is come back to find its lists where it left them (the nav host drops a page's composition when another page covers it).
 */

/** The sections of a channel page (spec 5.5): its tabs, and About, which is drawn from the header and needs no tab. */
internal enum class ChannelSection { VIDEOS, SHORTS, LIVE, PLAYLISTS, ABOUT }

/** Which sections a channel page offers, and which one it shows. Pure. */
internal object ChannelSections {
    /**
     * The sections for a channel that has [tabs]: in the order of the spec (Videos, Shorts, Live, Playlists), Shorts left out while
     * [hideShorts] is on (spec 7.4), About last. A channel with no tab at all still has About.
     */
    fun of(tabs: List<ChannelTab>, hideShorts: Boolean): List<ChannelSection> = buildList {
        for (tab in ChannelTab.entries) {
            if (tab !in tabs) continue
            if (tab == ChannelTab.SHORTS && hideShorts) continue
            add(sectionOf(tab))
        }
        add(ChannelSection.ABOUT)
    }

    fun sectionOf(tab: ChannelTab): ChannelSection = when (tab) {
        ChannelTab.VIDEOS -> ChannelSection.VIDEOS
        ChannelTab.SHORTS -> ChannelSection.SHORTS
        ChannelTab.LIVE -> ChannelSection.LIVE
        ChannelTab.PLAYLISTS -> ChannelSection.PLAYLISTS
    }

    /** The tab a section loads, or null for About. */
    fun tabOf(section: ChannelSection): ChannelTab? = when (section) {
        ChannelSection.VIDEOS -> ChannelTab.VIDEOS
        ChannelSection.SHORTS -> ChannelTab.SHORTS
        ChannelSection.LIVE -> ChannelTab.LIVE
        ChannelSection.PLAYLISTS -> ChannelTab.PLAYLISTS
        ChannelSection.ABOUT -> null
    }

    /** The section to show: [wanted] when the channel offers it, else the first one it does. */
    fun resolve(wanted: ChannelSection, offered: List<ChannelSection>): ChannelSection = if (wanted in offered) wanted else offered.first()

    /** The section a saved name stands for, or [fallback]. */
    fun parse(name: String?, fallback: ChannelSection): ChannelSection = ChannelSection.entries.firstOrNull { it.name == name } ?: fallback

    /** The tab whose uploads "Download the latest" takes while [section] is open: the open tab when it lists videos, else the Videos tab. */
    fun uploadsTab(section: ChannelSection): ChannelTab = when (section) {
        ChannelSection.SHORTS -> ChannelTab.SHORTS
        ChannelSection.LIVE -> ChannelTab.LIVE
        else -> ChannelTab.VIDEOS
    }
}

/** How far a load has got. */
internal sealed interface Loaded<out T> {
    data object Loading : Loaded<Nothing>
    data class Failed(val error: Throwable) : Loaded<Nothing>
    data class Ready<T>(val value: T) : Loaded<T>
}

/** Where a [PagedItems] stands. */
internal enum class PagedState { IDLE, LOADING_FIRST, FAILED_FIRST, READY, LOADING_MORE, FAILED_MORE }

/**
 * One list that comes in pages: the items so far, whether another page waits, and where the loading stands. [start] fetches the first
 * page once, [loadMore] the next one (nothing while a page is on its way or the last one is in), [retry] repeats the page that failed,
 * and [loadUntil] reads on until the list holds enough. One page is on its way at a time. A failure keeps what was loaded. All state is
 * snapshot state, so a screen reads it where it draws; the pages are fetched on [scope].
 */
@Stable
internal class PagedItems<T>(private val scope: CoroutineScope, private val fetch: suspend (PageToken?) -> Page<T>) {
    var items: List<T> by mutableStateOf(emptyList())
        private set
    var state: PagedState by mutableStateOf(PagedState.IDLE)
        private set

    /** Whether the source has another page after the ones in [items]. */
    var hasMore: Boolean by mutableStateOf(false)
        private set

    /** Why the last page failed (null while nothing has). */
    var error: Throwable? = null
        private set

    private val lock = Any()
    private var next: PageToken? = null
    private var job: Job? = null

    /** Fetches the first page, once. */
    fun start() {
        synchronized(lock) {
            if (state != PagedState.IDLE) return
            state = PagedState.LOADING_FIRST
            launch(null, first = true)
        }
    }

    /** Fetches the page after the last one in, when there is one and none is on its way. */
    fun loadMore() {
        synchronized(lock) {
            val token = next
            if (state != PagedState.READY || token == null) return
            state = PagedState.LOADING_MORE
            launch(token, first = false)
        }
    }

    /** Repeats the page that failed. */
    fun retry() {
        synchronized(lock) {
            when (state) {
                PagedState.FAILED_FIRST -> {
                    state = PagedState.LOADING_FIRST
                    launch(null, first = true)
                }
                PagedState.FAILED_MORE -> {
                    state = PagedState.LOADING_MORE
                    launch(next, first = false)
                }
                else -> Unit
            }
        }
    }

    /** Reads pages until the list holds [count] items, or the source has no more, or a page fails: what is loaded then is [items]. */
    suspend fun loadUntil(count: Int) {
        start()
        while (true) {
            val running = synchronized(lock) { job }
            running?.join()
            if (items.size >= count || state != PagedState.READY || !hasMore) return
            loadMore()
        }
    }

    private fun launch(token: PageToken?, first: Boolean) {
        job = scope.launch {
            try {
                val page = fetch(token)
                synchronized(lock) {
                    items = if (first) page.items else items + page.items
                    next = page.next
                    hasMore = page.next != null
                    error = null
                    state = PagedState.READY
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                synchronized(lock) {
                    error = e
                    state = if (first) PagedState.FAILED_FIRST else PagedState.FAILED_MORE
                }
            }
        }
    }
}

/** The videos among [items] (a channel's tabs mix videos, Shorts and playlists), in order. */
internal fun videosOf(items: List<ListItem>): List<VideoItem> = items.mapNotNull { (it as? ListItem.Video)?.item }

/**
 * A channel page's data: the header and, for each tab the page opens, its [PagedItems]. Tabs load when asked for ([tab] then [PagedItems.start]).
 * [uploads] loads a tab far enough for "Download the latest".
 */
@Stable
internal class ChannelModel(private val source: VideoSource, private val scope: CoroutineScope, val channelId: String) {
    var header: Loaded<ChannelPage> by mutableStateOf(Loaded.Loading)
        private set

    private var started = false
    private val tabs = HashMap<ChannelTab, PagedItems<ListItem>>()

    /** The list of [tab], created on first use. */
    fun tab(tab: ChannelTab): PagedItems<ListItem> = synchronized(tabs) {
        tabs.getOrPut(tab) { PagedItems(scope) { token -> source.channelTab(channelId, tab, token) } }
    }

    /** Loads the header, once. */
    fun start() {
        synchronized(this) {
            if (started) return
            started = true
        }
        scope.launch {
            header = try {
                Loaded.Ready(source.channel(channelId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Loaded.Failed(e)
            }
        }
    }

    /** Loads the header again after a failure. */
    fun retry() {
        synchronized(this) { started = false }
        header = Loaded.Loading
        start()
    }

    /** Whether this model is worth keeping for the next visit: a header that failed is not. */
    val reusable: Boolean get() = header !is Loaded.Failed

    /** The videos of [tab], loaded until there are [atLeast] of them (or the tab ends): what "Download the latest" offers. */
    suspend fun uploads(tab: ChannelTab, atLeast: Int): List<VideoItem> {
        val paged = tab(tab)
        paged.loadUntil(atLeast)
        return videosOf(paged.items)
    }
}

/** The scope the pages' loads run on: one for the process, so a load goes on while its page is covered by another. */
internal object PageLoads {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

/**
 * A few models kept by [key] (least recently used out), each for [ttlMs] and for one [owner] (the source it was made for): a page that
 * is left and come back to finds its lists as they were, with its place in them (the lazy list's saved state), instead of starting over.
 */
internal class ModelCache<V : Any>(
    private val capacity: Int,
    private val ttlMs: Long,
    private val clock: () -> Long,
    private val reusable: (V) -> Boolean = { true },
) {
    private class Entry<V>(val owner: Any, val madeAtMs: Long, val value: V)

    private val entries = object : LinkedHashMap<String, Entry<V>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry<V>>?): Boolean = size > capacity
    }

    /** The kept model for [key] and [owner], or the one [create] makes (and keeps). */
    @Synchronized
    fun obtain(key: String, owner: Any, create: () -> V): V {
        val kept = entries[key]
        if (kept != null && kept.owner === owner && clock() - kept.madeAtMs in 0 until ttlMs && reusable(kept.value)) return kept.value
        val made = create()
        entries[key] = Entry(owner, clock(), made)
        return made
    }

    @Synchronized
    fun size(): Int = entries.size
}

/** The channel models in use. */
internal object ChannelModels {
    private val cache = ModelCache<ChannelModel>(capacity = 8, ttlMs = 10 * 60_000L, clock = System::currentTimeMillis, reusable = { it.reusable })

    fun obtain(source: VideoSource, channelId: String): ChannelModel =
        cache.obtain("channel:$channelId", source) { ChannelModel(source, PageLoads.scope, channelId) }
}
