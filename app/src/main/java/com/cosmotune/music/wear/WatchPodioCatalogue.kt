package com.cosmotune.music.wear

import com.cosmotune.podio.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.cosmotune.music.data.model.PlaybackSourceType
import com.cosmotune.music.data.model.QueueTier
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.playback.QueueTimeline
import com.cosmotune.music.wear.WatchPodioWire as W

/** Same tier and placement as the phone's Add to queue action, before context/AutoPlay. */
internal fun watchPodioQueueEntry(timeline: List<Song>, currentIndex: Int, song: Song): Pair<Int, Song> {
    val current = timeline.getOrNull(currentIndex)
    val at = QueueTimeline.findUserQueueInsertionIndex(timeline, currentIndex, isNext = false)
    val queued = with(QueueTimeline) {
        song.copy(
            radioName = current?.radioName,
            playbackSource = current?.playbackSource ?: "Podio",
            playbackSourceType = current?.playbackSourceType ?: PlaybackSourceType.QUEUE,
            playbackSourceId = current?.playbackSourceId,
        ).asQueueEntry(QueueTier.USER_QUEUE)
    }
    return at to queued
}

/** Phone-owned identities and a bounded cache; watch requests cannot introduce a URL. */
internal class WatchPodioCatalogue(private val library: PodioLibrary,
    private val stations: suspend () -> List<RadioStation> = { PodioCatalogue().stations() },
    private val feed: suspend (String) -> PodcastFeed = { PodioCatalogue().feed(it) },
    private val more: suspend (PodcastFeed) -> PodcastFeed = { PodioCatalogue().moreEpisodes(it) },
    private val now: () -> Long = System::currentTimeMillis) {
    private data class Cached(val rows: List<W.Row>, val items: Map<String, PodioItem>, val at: Long, val feed: PodcastFeed? = null)
    private val lock = Mutex()
    private val cache = LinkedHashMap<String, Cached>(8, .75f, true)

    suspend fun browse(request: W.Request): W.Page = lock.withLock {
        val key = "${request.kind}:${request.parent}"
        val previous = cache[key]?.takeIf { now() - it.at in 0..CACHE_MS }
        var failed = false
        val value = try {
            when (request.kind) {
                W.Kind.SAVED_RADIO -> radio(library.state.value.stations)
                W.Kind.POPULAR_RADIO -> previous?.takeUnless { request.refresh } ?: radio(stations())
                W.Kind.PODCASTS -> Cached(library.state.value.shows.take(W.MAX_ROWS).map {
                    W.Row("show:${it.id}", W.RowKind.SHOW, it.title, it.author)
                }, emptyMap(), now())
                W.Kind.EPISODES -> {
                    val show = library.state.value.shows.firstOrNull { "show:${it.id}" == request.parent }
                        ?: error("Follow this podcast on the phone first")
                    var episodes = previous?.feed?.takeUnless { request.refresh } ?: feed(show.feed)
                    var pages = 0
                    while (episodes.episodes.size < request.offset + W.PAGE_SIZE && episodes.nextPage != null && pages++ < 3) episodes = more(episodes)
                    val items = episodes.episodes.take(W.MAX_ROWS).map { it.item() }
                    Cached(items.map { row(it, W.RowKind.EPISODE) }, items.associateBy { it.id }, now(),
                        episodes.copy(episodes = episodes.episodes.take(W.MAX_ROWS).map { it.copy(description = "") }))
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { failed = true; previous ?: throw failure }
        cache[key] = value
        while (cache.size > 8) cache.remove(cache.keys.first())
        val end = minOf(request.offset + W.PAGE_SIZE, value.rows.size)
        val rows = if (request.offset >= value.rows.size) emptyList() else value.rows.subList(request.offset, end)
        W.Page(request, rows, more = request.offset + W.PAGE_SIZE < W.MAX_ROWS && (end < value.rows.size || value.feed?.nextPage != null),
            cached = failed, error = if (failed) "Phone connection failed; showing the saved catalogue" else "")
    }

    suspend fun resolve(row: String): Pair<PodioItem, Long>? = lock.withLock {
        val item = cache.values.asSequence().filter { now() - it.at in 0..CACHE_MS }.mapNotNull { it.items[row] }.firstOrNull()
            ?: return@withLock null
        item to if (item.live) 0 else library.resumePosition(item.id, item.durationSeconds * 1000)
    }
    private fun radio(stations: List<RadioStation>): Cached {
        val items = stations.take(W.MAX_ROWS).map { it.item() }
        return Cached(items.map { row(it, W.RowKind.STATION) }, items.associateBy { it.id }, now())
    }
    private fun row(item: PodioItem, kind: W.RowKind) = W.Row(item.id, kind, item.title,
        if (item.live) listOf("Live", item.author).filter(String::isNotBlank).joinToString(" · ") else item.author,
        item.durationSeconds.coerceIn(0, 604_800) * 1000,
        if (item.live) 0 else library.resumePosition(item.id, item.durationSeconds * 1000))

    companion object { private const val CACHE_MS = 10 * 60 * 1000L }
}

/** A retried Wearable message must not append the same episode to the queue twice. */
internal class WatchPodioActions(private val now: () -> Long = System::currentTimeMillis) {
    private val lock = Mutex()
    private data class Saved(val action: W.Action, val at: Long)
    private val saved = LinkedHashMap<String, Saved>()
    suspend fun execute(node: String, request: W.Selection, action: suspend () -> W.Action): W.Action = lock.withLock {
        val key = "$node:${request.id}"
        saved[key]?.takeIf { now() - it.at in 0..120_000 }?.let { return@withLock it.action }
        action().also {
            saved[key] = Saved(it, now())
            while (saved.size > 32) saved.remove(saved.keys.first())
        }
    }
}
