package com.cosmotune.video.feed

import com.cosmotune.video.model.VideoItem

/** The one feed: the videos of channels on other sites set in among the feed's by upload time, the feed's own order kept. */
object OtherSitesFeed {
    fun merge(feed: List<VideoItem>, others: List<VideoItem>): List<VideoItem> {
        if (others.isEmpty()) return feed
        val have = feed.mapTo(HashSet()) { it.id }
        val waiting = ArrayDeque(others.filter { it.id !in have }.sortedByDescending { it.uploadedAtMs ?: Long.MIN_VALUE })
        val out = ArrayList<VideoItem>(feed.size + waiting.size)
        for (item in feed) {
            val at = item.uploadedAtMs
            if (at != null) while (waiting.isNotEmpty() && (waiting.first().uploadedAtMs ?: Long.MIN_VALUE) > at) out += waiting.removeFirst()
            out += item
        }
        out += waiting
        return out
    }
}
