package com.cosmotune.video.feed

import com.cosmotune.video.library.VideoLibrary
import kotlinx.coroutines.flow.StateFlow
import kotlin.jvm.Synchronized

/**
 * The feed as the graph hands it out: [engine] with a limit on refreshes nobody forced. The engine already skips a channel read in
 * the last 30 minutes, but a channel that failed is due again at once and costs about 5 s of retries and a fallback request on every
 * refresh (plan task Y-9's report). So a refresh that is not forced runs at most once per [minIntervalMs] (10 minutes), unless a
 * channel was followed since the last one (its videos should show at once); a forced refresh (pull to refresh, "Try again") always
 * runs and counts as the last one. [subscriptions] gives the ids of the followed channels.
 */
class RefreshLimitedFeed(
    private val engine: SubscriptionFeed,
    private val subscriptions: () -> Set<String>,
    private val clock: () -> Long,
    private val minIntervalMs: Long = MIN_INTERVAL_MS,
) : SubscriptionFeed {

    /** [engine] limited the same way, the followed channels read from [library]. */
    constructor(engine: SubscriptionFeed, library: VideoLibrary, clock: () -> Long) :
        this(engine, { library.subscriptions.value.mapTo(HashSet()) { it.channelId } }, clock)

    override val state: StateFlow<FeedState> get() = engine.state

    private var lastRunMs: Long? = null
    private var lastChannels: Set<String> = emptySet()

    override suspend fun refresh(force: Boolean) {
        if (!claim(force)) return
        engine.refresh(force)
    }

    /** Whether this refresh may run; if so it becomes the last one. */
    @Synchronized
    private fun claim(force: Boolean): Boolean {
        val now = clock()
        val channels = subscriptions()
        val last = lastRunMs
        val due = force || last == null || now - last >= minIntervalMs || now < last || !lastChannels.containsAll(channels)
        if (!due) return false
        lastRunMs = now
        lastChannels = channels
        return true
    }

    companion object {
        /** The least time between two refreshes nobody forced: 10 minutes. */
        const val MIN_INTERVAL_MS = 10 * 60_000L
    }
}
