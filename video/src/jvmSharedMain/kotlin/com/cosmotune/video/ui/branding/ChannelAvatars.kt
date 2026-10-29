package com.cosmotune.video.ui.branding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.cosmotune.video.graph.LocalVideoGraph
import com.cosmotune.video.graph.VideoGraph
import com.cosmotune.video.model.VideoItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/*
 * The channel picture of a card. Most lists the source answers (search, the charts, a channel's tabs) carry no uploader picture on
 * their items, so a card has none to draw. This fills it in: from any item of the same channel that did carry one, from the
 * subscriptions, else from the channel's own page, fetched once per channel (two at a time, only for a card that stays on
 * screen) and kept for the process. Nothing here blocks scrolling: a card draws its initial until the picture is known.
 */

/**
 * The picture of the channel of [item]: the item's own, else the one learned for its channel, else null for now (the lookup runs
 * while the card is composed and the card recomposes with the answer).
 */
@Composable
fun rememberChannelAvatar(item: VideoItem): String? {
    val own = item.channelAvatarUrl
    val id = item.channelId
    if (own != null) {
        if (id != null) ChannelAvatars.learn(id, own)
        return own
    }
    if (id == null) return null
    val graph = LocalVideoGraph.current
    var url by remember(id) { mutableStateOf(ChannelAvatars.known(id)) }
    if (url == null) {
        LaunchedEffect(id, graph) {
            delay(ChannelAvatars.SETTLE_MS)
            url = ChannelAvatars.resolve(graph, id)
        }
    }
    return url
}

/** The process-wide channel pictures. */
internal object ChannelAvatars {
    /** How long a card must stay composed before its channel is asked for. */
    const val SETTLE_MS = 300L

    /** At most this many channel pages are read at once. */
    const val MAX_IN_FLIGHT = 2

    /** A channel whose lookup failed is not asked for again for this long: 10 minutes. */
    const val RETRY_MS = 10 * 60_000L

    private const val ENTRIES = 2048

    private val semaphore = Semaphore(MAX_IN_FLIGHT)
    private val urls = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > ENTRIES
    }
    private val failedAt = HashMap<String, Long>()

    /** The picture learned for channel [id], or null. */
    fun known(id: String): String? = synchronized(urls) { urls[id] }

    /** Records [url] as channel [id]'s picture (an item that carried one). */
    fun learn(id: String, url: String) {
        synchronized(urls) { urls[id] = url }
    }

    /** The picture of channel [id]: learned, a subscription's, else the channel page's; null when none is found. */
    suspend fun resolve(graph: VideoGraph, id: String, nowMs: Long = System.currentTimeMillis()): String? {
        known(id)?.let { return it }
        graph.library.subscriptions.value.firstOrNull { it.channelId == id }?.avatarUrl?.let { learn(id, it); return it }
        val failed = synchronized(failedAt) { failedAt[id] }
        if (failed != null && nowMs - failed < RETRY_MS) return null
        return semaphore.withPermit {
            known(id)?.let { return@withPermit it }
            try {
                val url = graph.source.channel(id).channel.avatarUrl?.takeIf { it.isNotBlank() }
                if (url != null) learn(id, url) else synchronized(failedAt) { failedAt[id] = nowMs }
                url
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                synchronized(failedAt) { failedAt[id] = nowMs }
                null
            }
        }
    }
}
