package com.cosmotune.video.feed

import com.cosmotune.video.model.VideoItem
import kotlinx.coroutines.flow.StateFlow

/**
 * The subscription feed (spec 8.3): the merged uploads of every followed channel, newest first, when it was last
 * updated, the channels that could not be loaded ("n channels could not be loaded") and whether a refresh is running.
 */
data class FeedState(val items: List<VideoItem>, val updatedAtMs: Long?, val failedChannels: List<String>, val refreshing: Boolean)

/** The feed of the followed channels (spec 8.3; the engine is plan task Y-9). */
interface SubscriptionFeed {
    /** The feed. */
    val state: StateFlow<FeedState>

    /**
     * Fetches the channels that are due (spec 8.3: a channel fetched in the last 30 minutes is skipped); [force] (a pull to
     * refresh) fetches every channel.
     */
    suspend fun refresh(force: Boolean)
}
