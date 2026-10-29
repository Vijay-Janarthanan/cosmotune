package com.cosmotune.video.source.newpipe

import com.cosmotune.music.data.TrackLog
import com.cosmotune.video.account.AccountFailure
import com.cosmotune.video.account.RelatedFallback
import com.cosmotune.video.account.failureOf
import com.cosmotune.video.model.ChannelPage
import com.cosmotune.video.model.ChannelTab
import com.cosmotune.video.model.CommentPage
import com.cosmotune.video.model.CommentSort
import com.cosmotune.video.model.Kiosk
import com.cosmotune.video.model.ListItem
import com.cosmotune.video.model.Page
import com.cosmotune.video.model.PageToken
import com.cosmotune.video.model.PlaylistItem
import com.cosmotune.video.model.PlaylistPage
import com.cosmotune.video.model.SearchFilter
import com.cosmotune.video.model.StreamSet
import com.cosmotune.video.model.VideoDetails
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.source.VideoSource
import com.cosmotune.video.source.VideoSourceError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.comments.CommentsInfo
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.Page as NpPage

/**
 * [VideoSource] on NewPipeExtractor (plan task Y-4, step 5; v0.26.3, YouTube only, no account).
 *
 * Every call runs `withContext(Dispatchers.IO) { NewPipeHost.ensure(); NewPipeHost.video { ... } }`, so NewPipe's blocking
 * requests go to Video's downloader (the real `next` endpoint, no cookies) and the thread is not the caller's. Whatever the
 * extractor throws leaves as a [VideoSourceError] ([NewPipeErrors]); paging is an opaque [PageToken] ([PageCodec]). No
 * NewPipe type crosses this class's boundary.
 *
 * What v0.26.3 cannot do, and how that shows: it has no Shorts search filter (the `ALL` search is read and the Shorts kept),
 * no comment sorting (`CommentPage.sortable` is false and `sort` is ignored), and its kiosk list still holds the removed
 * "Trending" chart, which [kiosks] never offers.
 *
 * Related videos ("Up next") come with the details. When the extractor returns none for a video (it parses only the item shapes
 * it knows, and a page whose list it cannot read gives an empty one without an error), [related] asks the site's own `next`
 * endpoint once ([RelatedFallback], signed when a session exists) and keeps the answer ten minutes; a failure is not kept.
 *
 * @param clock the time the cache and the premiere test use (injectable for tests).
 * @param relatedFallback the second way to the related videos (tests give a scripted one).
 */
class NewPipeVideoSource internal constructor(
    private val clock: () -> Long,
    private val relatedFallback: RelatedFallback,
) : VideoSource {

    constructor(clock: () -> Long = System::currentTimeMillis) : this(clock, RelatedFallback.Site)

    private val service get() = ServiceList.YouTube

    /** Reads the watch page of an address; tests give an in-memory answer. */
    internal var streamInfoLoader: (String) -> StreamInfo = { url -> StreamInfo.getInfo(ServiceList.YouTube, url) }

    /** Details by video id, most recently used last; guarded by itself. */
    private val cache = object : LinkedHashMap<String, VideoDetails>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, VideoDetails>): Boolean = size > CACHE_SIZE
    }

    /** Runs [block] on an IO thread inside Video's NewPipe scope; what it throws leaves as a [VideoSourceError]. */
    private suspend fun <T> call(block: () -> T): T = withContext(Dispatchers.IO) {
        NewPipeErrors.guard {
            NewPipeHost.ensure()
            NewPipeHost.video(block)
        }
    }

    // ---------------------------------------------------------------- Home

    override suspend fun kiosks(): List<Kiosk> = call {
        val offered = service.kioskList.availableKiosks
        KIOSKS.filter { it.sourceId in offered }.map { Kiosk(it.id, it.name) }
    }

    override suspend fun kiosk(id: String, page: PageToken?): Page<VideoItem> = call {
        val entry = KIOSKS.firstOrNull { it.id == id } ?: throw VideoSourceError.NotAvailable("There is no list called $id")
        val url = kioskUrl(entry)
        val now = clock()
        if (page == null) {
            val info = KioskInfo.getInfo(service, url)
            val items = info.relatedItems.orEmpty()
            throwIfFailed(items.isEmpty(), info.errors)
            Page(videos(items, now), nextToken(info.nextPage))
        } else {
            val more = KioskInfo.getMoreItems(service, url, PageCodec.decode(page).page)
            Page(videos(more.items.orEmpty(), now), nextToken(more.nextPage))
        }
    }

    // ---------------------------------------------------------------- Search

    override suspend fun suggestions(query: String): List<String> {
        val text = query.trim()
        if (text.isEmpty()) return emptyList()
        return call { service.suggestionExtractor.suggestionList(text).orEmpty().filter { it.isNotBlank() }.distinct() }
    }

    override suspend fun search(query: String, filter: SearchFilter, page: PageToken?): Page<ListItem> {
        val text = query.trim()
        if (text.isEmpty()) return Page(emptyList(), null)
        return if (filter == SearchFilter.SHORTS) shorts(text, page) else call { searchPage(text, filter, page) }
    }

    /** The address of a chart in the extractor's own kiosk list. */
    internal fun kioskUrl(entry: KioskEntry): String = service.kioskList.getListLinkHandlerFactoryByType(entry.sourceId).getUrl(entry.sourceId)

    private fun searchHandler(query: String, filter: SearchFilter) =
        service.searchQHFactory.fromQuery(query, listOf(searchFilterName(filter)), "")

    private fun searchPage(query: String, filter: SearchFilter, page: PageToken?): Page<ListItem> {
        val handler = searchHandler(query, filter)
        val now = clock()
        return if (page == null) {
            val info = SearchInfo.getInfo(service, handler)
            val items = info.relatedItems.orEmpty()
            throwIfFailed(items.isEmpty(), info.errors)
            Page(listItems(items, now), nextToken(info.nextPage))
        } else {
            val more = SearchInfo.getMoreItems(service, handler, PageCodec.decode(page).page)
            Page(listItems(more.items.orEmpty(), now), nextToken(more.nextPage))
        }
    }

    /**
     * The source has no Shorts filter (spec 5.3): the `ALL` search is read page by page, up to [MAX_SHORT_PAGES] pages, and
     * only the items marked as Shorts are kept, until [MIN_SHORTS] are found or the results end. The token continues from the
     * first page not read, so the next call reads on. A page that fails after some Shorts were found ends the call with
     * those, and the token retries that page.
     */
    private suspend fun shorts(query: String, page: PageToken?): Page<ListItem> {
        val found = LinkedHashMap<String, ListItem>()
        var next: NpPage? = page?.let { PageCodec.decode(it).page }
        var first = page == null
        var read = 0
        while (read < MAX_SHORT_PAGES) {
            val fetching = next
            val result = try {
                call {
                    val now = clock()
                    val handler = searchHandler(query, SearchFilter.ALL)
                    if (first) {
                        val info = SearchInfo.getInfo(service, handler)
                        val items = info.relatedItems.orEmpty()
                        throwIfFailed(items.isEmpty(), info.errors)
                        shortsOf(items, now) to info.nextPage?.takeIf { NpPage.isValid(it) }
                    } else {
                        val more = SearchInfo.getMoreItems(service, handler, fetching!!)
                        shortsOf(more.items.orEmpty(), now) to more.nextPage?.takeIf { NpPage.isValid(it) }
                    }
                }
            } catch (e: VideoSourceError) {
                if (found.isEmpty()) throw e
                break // keep what was found; `next` is still the page that failed, so the token retries it
            }
            first = false
            read++
            result.first.forEach { found.putIfAbsent((it as ListItem.Video).item.id, it) }
            next = result.second
            if (found.size >= MIN_SHORTS || next == null) break
        }
        return Page(found.values.toList(), next?.let { PageCodec.encode(it) })
    }

    private fun shortsOf(items: List<InfoItem>, now: Long): List<ListItem> = items
        .filterIsInstance<StreamInfoItem>()
        .filter { it.isShortFormContent }
        .mapNotNull { NewPipeMapper.listItem(it, now) }

    // ---------------------------------------------------------------- Watch

    override suspend fun details(videoId: String): VideoDetails {
        val key = NewPipeMapper.videoIdOf(videoId) ?: videoId
        cached(key)?.let { return it }
        return call { fetchDetails(key) }
    }

    /** Always asks the source (the refresh after a 403, spec 6.1), and keeps the answer for [details]. */
    override suspend fun streams(videoId: String): StreamSet {
        val key = NewPipeMapper.videoIdOf(videoId) ?: videoId
        return call { fetchDetails(key).streams }
    }

    private fun fetchDetails(videoId: String): VideoDetails {
        val info = streamInfoLoader(NewPipeAddresses.watchUrl(videoId))
        val details = NewPipeMapper.details(info, clock(), NewPipeHost.localization())
        if (details.related.none { it is ListItem.Video }) {
            // Nothing of the id: only how many errors the extractor kept, by kind, so a log shows why "Up next" starts empty.
            TrackLog.w(TAG, "related: the extractor returned no videos (${info.errors.size} errors: ${info.errors.map { it.javaClass.simpleName }.distinct()}, age limit ${info.ageLimit})")
        }
        synchronized(cache) { cache[videoId] = details }
        return details
    }

    /** The fallback's answers by video id, most recently used last; guarded by itself. Only non-empty answers are kept. */
    private val relatedCache = object : LinkedHashMap<String, Pair<Long, List<ListItem>>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, List<ListItem>>>): Boolean = size > CACHE_SIZE
    }

    /** One fallback request at a time, so two askers of the same video share the answer instead of making two requests. */
    private val relatedGate = Mutex()

    /**
     * The related videos: the extractor's own list when it has videos; else the site's `next` answer, asked once per video and kept
     * [DETAILS_TTL_MS]. An empty list means the site has no suggestions for it; a [VideoSourceError] means it could not be asked.
     */
    override suspend fun related(videoId: String): List<ListItem> {
        val key = NewPipeMapper.videoIdOf(videoId) ?: videoId
        val own = details(key).related
        if (own.any { it is ListItem.Video }) return own
        relatedKept(key)?.let { return it }
        return relatedGate.withLock {
            relatedKept(key)?.let { return@withLock it }
            val videos = try {
                relatedFallback.fetch(key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TrackLog.w(TAG, "related: the site's next answer failed: ${e.javaClass.simpleName}")
                throw relatedError(e)
            }
            val items = videos.map { ListItem.Video(it) }
            TrackLog.d(TAG, "related: ${items.size} videos from the site's next answer")
            if (items.isNotEmpty()) synchronized(relatedCache) { relatedCache[key] = clock() to items }
            items
        }
    }

    private fun relatedKept(videoId: String): List<ListItem>? = synchronized(relatedCache) {
        val hit = relatedCache[videoId] ?: return@synchronized null
        if (clock() - hit.first in 0 until DETAILS_TTL_MS) {
            hit.second
        } else {
            relatedCache.remove(videoId)
            null
        }
    }

    private fun relatedError(e: Throwable): VideoSourceError = when (val failure = failureOf(e)) {
        is AccountFailure.Network -> VideoSourceError.Network(failure.message.orEmpty(), failure)
        else -> VideoSourceError.Parsing(failure.message.orEmpty(), failure)
    }

    private fun cached(videoId: String): VideoDetails? = synchronized(cache) {
        val hit = cache[videoId]
        if (hit != null && clock() - hit.fetchedAtMs in 0 until DETAILS_TTL_MS) {
            hit
        } else {
            cache.remove(videoId)
            null
        }
    }

    // ---------------------------------------------------------------- Channel

    override suspend fun channel(channelIdOrUrl: String): ChannelPage = call {
        NewPipeMapper.channelPage(ChannelInfo.getInfo(service, NewPipeAddresses.channelUrl(channelIdOrUrl)))
    }

    override suspend fun channelTab(channelId: String, tab: ChannelTab, page: PageToken?): Page<ListItem> = call {
        val handler = service.channelTabLHFactory.fromQuery(NewPipeAddresses.channelPath(channelId), listOf(tabFilterName(tab)), "")
        val now = clock()
        val (items, next) = if (page == null) {
            val info = ChannelTabInfo.getInfo(service, handler)
            val items = info.relatedItems.orEmpty()
            throwIfFailed(items.isEmpty(), info.errors)
            items to info.nextPage
        } else {
            val more = ChannelTabInfo.getMoreItems(service, handler, PageCodec.decode(page).page)
            more.items.orEmpty() to more.nextPage
        }
        val mapped = listItems(items, now)
        // Everything on the Shorts tab is a Short, whether or not the extractor marked it.
        val out = if (tab == ChannelTab.SHORTS) mapped.map { if (it is ListItem.Video) ListItem.Video(it.item.copy(isShort = true)) else it } else mapped
        Page(out, nextToken(next))
    }

    // ---------------------------------------------------------------- Playlist

    override suspend fun playlist(playlistId: String, page: PageToken?): PlaylistPage = call {
        val id = NewPipeMapper.playlistIdOf(playlistId) ?: playlistId
        val url = NewPipeAddresses.playlistUrl(id)
        val now = clock()
        if (page == null) {
            val info = PlaylistInfo.getInfo(service, url)
            val items = info.relatedItems.orEmpty()
            throwIfFailed(items.isEmpty(), info.errors)
            PlaylistPage(
                playlist = PlaylistItem(
                    id = id,
                    title = info.name.orEmpty(),
                    thumbnailUrl = NewPipeMapper.bestImageUrl(info.thumbnails, NewPipeMapper.THUMBNAIL_MIN_WIDTH),
                    ownerName = info.uploaderName?.takeIf { it.isNotBlank() },
                    ownerId = NewPipeMapper.channelIdOf(info.uploaderUrl),
                    videoCount = info.streamCount.takeIf { it >= 0 },
                ),
                description = NewPipeMapper.description(info.description).text.takeIf { it.isNotBlank() },
                first = Page(videos(items, now), nextToken(info.nextPage)),
            )
        } else {
            val more = PlaylistInfo.getMoreItems(service, url, PageCodec.decode(page).page)
            PlaylistPage(
                playlist = PlaylistItem(id = id, title = "", thumbnailUrl = null, ownerName = null, ownerId = null, videoCount = null),
                description = null,
                first = Page(videos(more.items.orEmpty(), now), nextToken(more.nextPage)),
            )
        }
    }

    // ---------------------------------------------------------------- Comments

    /** [sort] is ignored: v0.26.3 offers one order, so [CommentPage.sortable] is false and the Top and Newest chips are not drawn. */
    override suspend fun comments(videoId: String, sort: CommentSort, page: PageToken?): CommentPage = call {
        val id = NewPipeMapper.videoIdOf(videoId) ?: videoId
        if (page == null) {
            val info = CommentsInfo.getInfo(service, NewPipeAddresses.watchUrl(id))
            val items = info.relatedItems.orEmpty()
            if (!info.isCommentsDisabled) throwIfFailed(items.isEmpty(), info.errors)
            CommentPage(
                items = items.map { NewPipeMapper.comment(it, id) },
                next = nextToken(info.nextPage, id),
                totalCount = info.commentsCount.takeIf { it >= 0 }?.toLong(),
                disabled = info.isCommentsDisabled,
                sortable = false,
            )
        } else {
            val more = CommentsInfo.getMoreItems(service, NewPipeAddresses.watchUrl(id), PageCodec.decode(page).page)
            CommentPage(more.items.orEmpty().map { NewPipeMapper.comment(it, id) }, nextToken(more.nextPage, id), null, disabled = false, sortable = false)
        }
    }

    override suspend fun replies(token: PageToken): CommentPage = call {
        val decoded = PageCodec.decode(token)
        val id = decoded.context ?: throw VideoSourceError.Parsing("A replies token without its video", null)
        val more = CommentsInfo.getMoreItems(service, NewPipeAddresses.watchUrl(id), decoded.page)
        CommentPage(more.items.orEmpty().map { NewPipeMapper.comment(it, id) }, nextToken(more.nextPage, id), null, disabled = false, sortable = false)
    }

    // ---------------------------------------------------------------- Region

    override fun setRegion(country: String, language: String) {
        NewPipeHost.setLocalization(country, language)
        synchronized(cache) { cache.clear() } // titles and descriptions come back in the new language
        synchronized(relatedCache) { relatedCache.clear() }
    }

    // ---------------------------------------------------------------- helpers

    private fun videos(items: List<StreamInfoItem>, now: Long): List<VideoItem> =
        items.mapNotNull { (NewPipeMapper.listItem(it, now) as? ListItem.Video)?.item }

    private fun listItems(items: List<InfoItem>, now: Long): List<ListItem> = items.mapNotNull { NewPipeMapper.listItem(it, now) }

    private fun nextToken(next: NpPage?, context: String? = null): PageToken? =
        next?.takeIf { NpPage.isValid(it) }?.let { PageCodec.encode(it, context) }

    /** NewPipe's list calls keep a failure in the info instead of throwing; an empty list with a real failure is a failure. */
    private fun throwIfFailed(empty: Boolean, errors: List<Throwable>?) {
        if (empty) NewPipeErrors.firstRealError(errors.orEmpty())?.let { throw it }
    }

    private fun searchFilterName(filter: SearchFilter): String = when (filter) {
        SearchFilter.ALL, SearchFilter.SHORTS -> YoutubeSearchQueryHandlerFactory.ALL
        SearchFilter.VIDEOS -> YoutubeSearchQueryHandlerFactory.VIDEOS
        SearchFilter.CHANNELS -> YoutubeSearchQueryHandlerFactory.CHANNELS
        SearchFilter.PLAYLISTS -> YoutubeSearchQueryHandlerFactory.PLAYLISTS
    }

    private fun tabFilterName(tab: ChannelTab): String = when (tab) {
        ChannelTab.VIDEOS -> ChannelTabs.VIDEOS
        ChannelTab.SHORTS -> ChannelTabs.SHORTS
        ChannelTab.LIVE -> ChannelTabs.LIVESTREAMS
        ChannelTab.PLAYLISTS -> ChannelTabs.PLAYLISTS
    }

    /** One of our kiosk ids, the id the v0.26.3 kiosk list uses for it, and the name Home's chip falls back to. */
    internal class KioskEntry(val id: String, val sourceId: String, val name: String)

    internal companion object {

        /** How long [details] answers from memory (spec 6.1: details are cached 10 minutes). */
        const val DETAILS_TTL_MS = 10 * 60_000L
        private const val CACHE_SIZE = 24
        private const val TAG = "CosmoVideo"

        /** The Shorts search reads at most this many pages per call, to return at least [MIN_SHORTS] (spec 5.3). */
        const val MAX_SHORT_PAGES = 3
        const val MIN_SHORTS = 10

        /**
         * The five charts of spec 5.2, in order, with the ids of v0.26.3's kiosk list. Its sixth, "Trending", was removed from
         * the site on 21 July 2025 and is never offered (spec 2.1).
         */
        val KIOSKS = listOf(
            KioskEntry("music", "trending_music", "Music"),
            KioskEntry("gaming", "trending_gaming", "Gaming"),
            KioskEntry("movies", "trending_movies_and_shows", "Movies and shows"),
            KioskEntry("podcasts", "trending_podcasts_episodes", "Podcasts"),
            KioskEntry("live", "live", "Live"),
        )
    }
}
