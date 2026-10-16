package com.cosmotune.video.feed

import com.cosmotune.video.source.FakeVideoSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The feed in memory (plan task Y-2, step 3): starts at [initial] ([SAMPLE] by default: ten videos and four Shorts of the
 * two sample channels, newest first, with no durations as RSS gives none, spec 8.3); [set] replaces the state; [refresh]
 * counts the calls and stamps [FeedState.updatedAtMs] with the clock.
 */
class FakeSubscriptionFeed(
    initial: FeedState = SAMPLE,
    private val clock: () -> Long = { FakeVideoSource.NOW_MS },
) : SubscriptionFeed {
    private val flow = MutableStateFlow(initial)

    override val state: StateFlow<FeedState> = flow.asStateFlow()

    /** How many times [refresh] ran. */
    var refreshCount: Int = 0
        private set

    /** The `force` of the last [refresh], or null before the first. */
    var lastForce: Boolean? = null
        private set

    /** Replaces the state. */
    fun set(state: FeedState) {
        flow.value = state
    }

    override suspend fun refresh(force: Boolean) {
        refreshCount += 1
        lastForce = force
        flow.update { it.copy(updatedAtMs = clock(), refreshing = false) }
    }

    companion object {
        /** Ten videos and four Shorts, newest first, updated five minutes before the fakes' clock. */
        val SAMPLE: FeedState = FeedState(
            items = (FakeVideoSource.videos.take(10) + FakeVideoSource.shorts.take(4))
                .map { it.copy(durationSec = null) }
                .sortedByDescending { it.uploadedAtMs ?: 0 },
            updatedAtMs = FakeVideoSource.NOW_MS - 5 * 60_000L,
            failedChannels = emptyList(),
            refreshing = false,
        )
    }
}
