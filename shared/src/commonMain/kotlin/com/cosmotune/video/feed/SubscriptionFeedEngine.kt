package com.cosmotune.video.feed

import com.cosmotune.video.feed.rss.RssEntry
import com.cosmotune.video.feed.rss.RssParser
import com.cosmotune.video.library.LibraryFiles
import com.cosmotune.video.library.Subscription
import com.cosmotune.video.library.VideoLibrary
import com.cosmotune.video.model.ChannelTab
import com.cosmotune.video.model.ListItem
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.settings.VideoSettingsRepository
import com.cosmotune.video.source.VideoSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException

/**
 * The subscription feed (plan task Y-9; spec 8.3). [refresh] reads, for every followed channel that is due and at most
 * [FeedPolicy.CONCURRENCY] at a time, its Atom feed through [fetcher] (the long-form `UULF` feed while Shorts are hidden, the
 * channel feed otherwise), tries a 404 or 500 again after 1 s and 4 s, and when the feed still fails, or the `UULF` feed has
 * no entries, takes the first page of the channel's Videos tab from [source]. A channel that fails all three counts in
 * [FeedState.failedChannels] (its channel ids, in the order of the subscriptions) and keeps what it showed before.
 *
 * Feed entries become [VideoItem]s with no duration (RSS has none), the channel's name and avatar from the subscription,
 * and `isShort` from a `/shorts/` link, so the feed carries the Shorts and the page can shelve them. The merged feed, the
 * time of each channel's last fetch and the failures are kept in `feed-cache.json` through [files] and read at start, so the
 * tab opens on the last feed at once. Switching Hide Shorts or following or unfollowing a channel changes [state] without
 * a fetch.
 *
 * A refresh runs in [scope], not in the caller: leaving the tab does not stop it. Refreshes run one after the other.
 */
class SubscriptionFeedEngine(
    private val library: VideoLibrary,
    private val source: VideoSource,
    private val fetcher: FeedFetcher,
    private val files: LibraryFiles,
    private val settings: VideoSettingsRepository,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
) : SubscriptionFeed {

    /** What the engine knows, apart from what the setting and the subscriptions hide from [state]. */
    private data class Store(
        val perChannel: Map<String, List<VideoItem>>,
        val fetchedAtMs: Map<String, Long>,
        val failed: List<String>,
        val updatedAtMs: Long?,
        /** The Hide Shorts setting the last fetch ran under, or null before any. */
        val hideShorts: Boolean?,
    )

    private val flow = MutableStateFlow(FeedState(items = emptyList(), updatedAtMs = null, failedChannels = emptyList(), refreshing = false))
    private val store = MutableStateFlow(Store(emptyMap(), emptyMap(), emptyList(), null, null))
    private val one = Mutex()
    private val loaded: Job

    override val state: StateFlow<FeedState> = flow.asStateFlow()

    init {
        // Last in the constructor: the jobs below read the fields above.
        loaded = scope.launch {
            loadCache()
            publish()
        }
        scope.launch {
            combine(library.subscriptions, settings.settings.map { it.hideShorts }.distinctUntilChanged()) { _, _ -> }.collect { publish() }
        }
    }

    override suspend fun refresh(force: Boolean) {
        scope.async {
            loaded.join()
            one.withLock { readDueChannels(force) }
        }.await()
    }

    // ---- the refresh ----

    private suspend fun readDueChannels(force: Boolean) {
        val subscriptions = library.subscriptions.value
        val hideShorts = settings.settings.value.hideShorts
        val before = store.value
        // Fetch times from under the other setting say nothing about the feed that is wanted now.
        val otherFeed = before.hideShorts != null && before.hideShorts != hideShorts
        val now = clock()
        val due = subscriptions.filter { FeedPolicy.due(if (otherFeed) null else before.fetchedAtMs[it.channelId], now, force) }
        if (due.isEmpty()) {
            publish()
            return
        }
        flow.update { it.copy(refreshing = true) }
        try {
            val gate = Semaphore(FeedPolicy.CONCURRENCY)
            val anyRead = coroutineScope {
                due.map { subscription -> readChannelAsync(gate, subscription, hideShorts) }.awaitAll().any { it }
            }
            // The feed is as new as the end of the refresh when it read at least one channel.
            if (anyRead) {
                val at = clock()
                store.update { it.copy(updatedAtMs = at) }
            }
            forgetUnfollowed()
            publish()
            persist()
        } finally {
            flow.update { it.copy(refreshing = false) }
        }
    }

    /** Reads one channel when a place among the six is free and records the result; true when it was read. */
    private fun CoroutineScope.readChannelAsync(gate: Semaphore, subscription: Subscription, hideShorts: Boolean): Deferred<Boolean> = async {
        val items = gate.withPermit { fetchChannel(subscription, hideShorts) }
        record(subscription.channelId, items, hideShorts)
        items != null
    }

    /** Takes one channel's result into the store: its items and fetch time, or its failure (it is then due again at once). */
    private fun record(channelId: String, items: List<VideoItem>?, hideShorts: Boolean) {
        val at = clock()
        store.update { s ->
            if (items != null) {
                s.copy(
                    perChannel = s.perChannel + (channelId to items),
                    fetchedAtMs = s.fetchedAtMs + (channelId to at),
                    failed = s.failed - channelId,
                    updatedAtMs = at,
                    hideShorts = hideShorts,
                )
            } else {
                s.copy(fetchedAtMs = s.fetchedAtMs - channelId, failed = if (channelId in s.failed) s.failed else s.failed + channelId)
            }
        }
        // The first load shows each channel as it arrives; a feed already on screen is replaced once, when the refresh ends.
        if (flow.value.items.isEmpty()) publish()
    }

    private fun forgetUnfollowed() {
        val followed = library.subscriptions.value.mapTo(HashSet()) { it.channelId }
        store.update { s ->
            s.copy(
                perChannel = s.perChannel.filterKeys { it in followed },
                fetchedAtMs = s.fetchedAtMs.filterKeys { it in followed },
                failed = s.failed.filter { it in followed },
            )
        }
    }

    // ---- one channel ----

    /** The channel's items from its feed, else from its Videos tab; null when both fail. */
    private suspend fun fetchChannel(subscription: Subscription, hideShorts: Boolean): List<VideoItem>? =
        readFeed(subscription, hideShorts) ?: readVideosTab(subscription)

    /** The feed's entries as items; null when the feed gave nothing usable (spec 8.3 points 2 and 3). */
    private suspend fun readFeed(subscription: Subscription, hideShorts: Boolean): List<VideoItem>? {
        val url = FeedPolicy.feedUrl(subscription.channelId, hideShorts)
        var tries = 0
        while (true) {
            val response = try {
                fetcher.get(url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return null // no answer at all (no network, a timeout): not worth a pause, the Videos tab is the next try
            }
            if (response.status in 200..299) {
                if (!RssParser.looksLikeFeed(response.body)) return null
                val entries = RssParser.parse(response.body)
                // The undocumented UULF feed answering nothing is a reason to try the tab; the channel feed with nothing is an empty channel.
                if (entries.isEmpty() && hideShorts) return null
                return entries.map { it.toItem(subscription) }
            }
            if (!FeedPolicy.retriable(response.status) || tries >= FeedPolicy.RETRY_DELAYS_MS.size) return null
            delay(FeedPolicy.RETRY_DELAYS_MS[tries])
            tries += 1
        }
    }

    private suspend fun readVideosTab(subscription: Subscription): List<VideoItem>? = try {
        source.channelTab(subscription.channelId, ChannelTab.VIDEOS).items
            .filterIsInstance<ListItem.Video>()
            .map { it.item.copy(channelId = subscription.channelId, channelName = it.item.channelName ?: subscription.nameOrNull(), channelAvatarUrl = it.item.channelAvatarUrl ?: subscription.avatarUrl) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun RssEntry.toItem(subscription: Subscription): VideoItem = VideoItem(
        id = videoId,
        title = title,
        channelId = subscription.channelId,
        channelName = subscription.nameOrNull(),
        channelAvatarUrl = subscription.avatarUrl,
        thumbnailUrl = thumbnailUrl,
        durationSec = null,
        viewCount = views,
        uploadedAtMs = publishedMs,
        uploadedText = null,
        isShort = isShortLink,
    )

    private fun Subscription.nameOrNull(): String? = name.takeIf { it.isNotBlank() }

    // ---- what the screen sees ----

    /** The state from the store, the subscriptions and the setting as they are now; safe to call from any thread, last writer wins on current data. */
    private fun publish() {
        flow.update { previous ->
            val s = store.value
            val subscriptions = library.subscriptions.value
            val followed = subscriptions.mapTo(HashSet()) { it.channelId }
            val failed = s.failed.toSet()
            FeedState(
                items = FeedMerge.merge(channelsInOrder(s, subscriptions), settings.settings.value.hideShorts),
                updatedAtMs = s.updatedAtMs,
                failedChannels = subscriptions.map { it.channelId }.filter { it in failed && it in followed },
                refreshing = previous.refreshing,
            )
        }
    }

    /** The items of the followed channels, in the order of the subscriptions (so the merge does not depend on which fetch ended first). */
    private fun channelsInOrder(s: Store, subscriptions: List<Subscription>): Map<String, List<VideoItem>> {
        val ordered = LinkedHashMap<String, List<VideoItem>>()
        for (subscription in subscriptions) s.perChannel[subscription.channelId]?.let { ordered[subscription.channelId] = it }
        return ordered
    }

    // ---- the cache ----

    private fun loadCache() {
        val text = try {
            files.read(CACHE_FILE)
        } catch (e: Exception) {
            null
        } ?: return
        val document = try {
            json.decodeFromString(CacheDocument.serializer(), text)
        } catch (e: Exception) {
            return // a cache that does not read is a cache that is not there: the next refresh writes a new one
        }
        if (document.version != CACHE_VERSION) return
        val names = library.subscriptions.value.associateBy { it.channelId }
        val perChannel = LinkedHashMap<String, MutableList<VideoItem>>()
        for (cached in document.items) {
            perChannel.getOrPut(cached.channelId) { ArrayList() } += cached.toItem(names[cached.channelId])
        }
        store.value = Store(perChannel, document.fetchedAtMs, document.failed, document.updatedAtMs, document.hideShorts)
    }

    private fun persist() {
        val s = store.value
        val items = FeedMerge.merge(channelsInOrder(s, library.subscriptions.value), hideShorts = false)
        val document = CacheDocument(
            version = CACHE_VERSION,
            hideShorts = s.hideShorts,
            updatedAtMs = s.updatedAtMs,
            fetchedAtMs = s.fetchedAtMs,
            failed = s.failed,
            items = items.mapNotNull { CachedItem.of(it) },
        )
        try {
            files.write(CACHE_FILE, json.encodeToString(CacheDocument.serializer(), document))
        } catch (e: Exception) {
            // A cache that cannot be written (a full disk) costs the next start a refresh; it must not fail this one.
        }
    }

    /** `feed-cache.json` (spec 8.1): the merged feed (at most 2,000 items), each channel's fetch time and the failures. */
    @Serializable
    private class CacheDocument(
        val version: Int,
        val hideShorts: Boolean? = null,
        val updatedAtMs: Long? = null,
        val fetchedAtMs: Map<String, Long> = emptyMap(),
        val failed: List<String> = emptyList(),
        val items: List<CachedItem> = emptyList(),
    )

    /** An item as kept: without the channel's name and avatar, which come back from the subscription at load. */
    @Serializable
    private class CachedItem(
        val id: String,
        val title: String,
        val channelId: String,
        val thumbnailUrl: String? = null,
        val durationSec: Long? = null,
        val viewCount: Long? = null,
        val uploadedAtMs: Long? = null,
        val uploadedText: String? = null,
        val isLive: Boolean = false,
        val isUpcoming: Boolean = false,
        val isShort: Boolean = false,
    ) {
        fun toItem(subscription: Subscription?): VideoItem = VideoItem(
            id = id,
            title = title,
            channelId = channelId,
            channelName = subscription?.name?.takeIf { it.isNotBlank() },
            channelAvatarUrl = subscription?.avatarUrl,
            thumbnailUrl = thumbnailUrl,
            durationSec = durationSec,
            viewCount = viewCount,
            uploadedAtMs = uploadedAtMs,
            uploadedText = uploadedText,
            isLive = isLive,
            isUpcoming = isUpcoming,
            isShort = isShort,
        )

        companion object {
            fun of(item: VideoItem): CachedItem? {
                val channelId = item.channelId ?: return null
                return CachedItem(
                    id = item.id,
                    title = item.title,
                    channelId = channelId,
                    thumbnailUrl = item.thumbnailUrl,
                    durationSec = item.durationSec,
                    viewCount = item.viewCount,
                    uploadedAtMs = item.uploadedAtMs,
                    uploadedText = item.uploadedText,
                    isLive = item.isLive,
                    isUpcoming = item.isUpcoming,
                    isShort = item.isShort,
                )
            }
        }
    }

    companion object {
        /** The file the feed is kept in (spec 8.1). */
        const val CACHE_FILE: String = "feed-cache.json"

        /** The `version` of [CACHE_FILE] this engine reads and writes; a file with another number is ignored. */
        const val CACHE_VERSION: Int = 1

        private val json = Json { ignoreUnknownKeys = true }
    }
}
