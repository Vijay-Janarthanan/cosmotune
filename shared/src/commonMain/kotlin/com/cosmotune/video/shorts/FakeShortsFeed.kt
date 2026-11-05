package com.cosmotune.video.shorts

import com.cosmotune.video.model.PageToken
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.source.FakeVideoSource
import kotlin.jvm.Synchronized
import kotlin.math.min

/**
 * A Shorts queue in memory (plan task Y-2, step 3): twelve Shorts `fakeshort01` to `fakeshort12` ([shorts]: `isShort`,
 * vertical thumbnails), paged [PAGE_SIZE] at a time for every origin; the seed's Shorts come first and are not repeated.
 * [calls] records each call as `name(origin)`.
 */
class FakeShortsFeed(private val all: List<VideoItem> = shorts) : ShortsFeed {
    private val queues = mutableMapOf<ShortsOrigin, List<VideoItem>>()
    private val log = mutableListOf<String>()

    /** Every call so far, as `start(origin)` or `more(origin)`, oldest first. */
    val calls: List<String>
        @Synchronized get() = log.toList()

    override suspend fun start(origin: ShortsOrigin, seed: List<VideoItem>): ShortsPage = startNow(origin, seed)

    override suspend fun more(origin: ShortsOrigin, token: PageToken): ShortsPage = moreNow(origin, token)

    @Synchronized
    private fun startNow(origin: ShortsOrigin, seed: List<VideoItem>): ShortsPage {
        log += "start($origin)"
        val seeded = seed.filter { it.isShort }.distinctBy { it.id }
        val queue = seeded + all.filter { s -> seeded.none { it.id == s.id } }
        queues[origin] = queue
        return page(queue, 0)
    }

    @Synchronized
    private fun moreNow(origin: ShortsOrigin, token: PageToken): ShortsPage {
        log += "more($origin)"
        return page(queues[origin] ?: all, token.value.toIntOrNull() ?: 0)
    }

    private fun page(queue: List<VideoItem>, from: Int): ShortsPage {
        if (from >= queue.size) return ShortsPage(emptyList(), null)
        val to = min(queue.size, from + PAGE_SIZE)
        return ShortsPage(queue.subList(from, to).toList(), if (to < queue.size) PageToken(to.toString()) else null)
    }

    companion object {
        /** Pages hold six Shorts. */
        const val PAGE_SIZE = 6

        /** The twelve Shorts (the same items as [FakeVideoSource.shorts]). */
        val shorts: List<VideoItem> get() = FakeVideoSource.shorts
    }
}
