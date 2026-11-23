package com.cosmotune.video.feed

import com.cosmotune.video.model.VideoItem

/**
 * New-video alerts for the channels whose bell is on (the bell beside Subscribe on a channel page). After each feed refresh the
 * phone's background check and the desktop's timer ask [due] which uploads are new since the last alert; the first check only sets
 * the mark, so following a channel does not bring a pile of old videos.
 */
object UploadAlerts {
    /** What to tell the listener ([items], newest first, at most [MAX]) and the mark to keep for next time. */
    data class Due(val items: List<VideoItem>, val seenUntilMs: Long)

    const val MAX = 8

    fun due(feed: List<VideoItem>, bellChannels: Set<String>, seenUntilMs: Long, nowMs: Long): Due {
        if (bellChannels.isEmpty()) return Due(emptyList(), seenUntilMs.coerceAtLeast(nowMs))
        val belled = feed.filter { it.channelId != null && it.channelId in bellChannels && !it.isUpcoming && !it.isShort && it.uploadedAtMs != null }
        val newest = belled.maxOfOrNull { it.uploadedAtMs!! } ?: 0L
        if (seenUntilMs <= 0L) return Due(emptyList(), maxOf(nowMs, newest))
        val fresh = belled.filter { it.uploadedAtMs!! > seenUntilMs }.distinctBy { it.id }.sortedByDescending { it.uploadedAtMs }
        return Due(fresh.take(MAX), maxOf(seenUntilMs, newest))
    }

    /** The notification's title and line: one video names itself, several say how many and from whom. */
    fun text(items: List<VideoItem>): Pair<String, String>? = when {
        items.isEmpty() -> null
        items.size == 1 -> (items[0].channelName ?: "New video") to items[0].title
        else -> "${items.size} new videos" to items.mapNotNull { it.channelName }.distinct().joinToString(", ")
    }
}
