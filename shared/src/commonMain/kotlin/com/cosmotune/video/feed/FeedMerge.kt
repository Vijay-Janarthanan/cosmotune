package com.cosmotune.video.feed

import com.cosmotune.video.model.VideoItem

/** The day overlines of the feed (spec 5.7: Today, Yesterday, This week, This month, Older); the screen maps each to its `video_*` string. */
enum class FeedGroup { TODAY, YESTERDAY, THIS_WEEK, THIS_MONTH, OLDER }

/**
 * Merging and grouping the channels' uploads (plan task Y-9, step 2; spec 5.7, 8.3). Pure: the caller computes the start of
 * today in the device's zone (`FeedDay.startOfToday`) and passes it in.
 */
object FeedMerge {
    /** The feed keeps at most this many items (spec 8.1: `feed-cache.json`, 2,000 items). */
    const val MAX_ITEMS: Int = 2_000

    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /**
     * The union of every channel's items by video id, with Shorts left out when [hideShorts] is on, newest upload first
     * (items without an upload time come last), at most [MAX_ITEMS]. Equal times keep the order they came in. Of two items
     * with the same id the one with a known duration is kept (the Videos-tab fallback has one, a feed entry has none),
     * otherwise the first.
     */
    fun merge(perChannel: Map<String, List<VideoItem>>, hideShorts: Boolean): List<VideoItem> {
        val byId = LinkedHashMap<String, VideoItem>()
        for (items in perChannel.values) {
            for (item in items) {
                if (hideShorts && item.isShort) continue
                val known = byId[item.id]
                if (known == null || (known.durationSec == null && item.durationSec != null)) byId[item.id] = item
            }
        }
        val sorted = byId.values.sortedWith(compareByDescending<VideoItem> { it.uploadedAtMs != null }.thenByDescending { it.uploadedAtMs ?: 0L })
        return if (sorted.size > MAX_ITEMS) sorted.subList(0, MAX_ITEMS).toList() else sorted
    }

    /**
     * [items] under the day overlines, in the order of [FeedGroup], the empty ones left out and each group in the order of
     * the input. Today runs from [startOfTodayMs] (and takes whatever is later, such as a premiere); Yesterday is the 24
     * hours before it; This week the days before that back to 7 days before today; This month back to 30 days before
     * today; the rest, and every item without an upload time, is Older.
     */
    fun groups(items: List<VideoItem>, startOfTodayMs: Long): List<Pair<FeedGroup, List<VideoItem>>> =
        groups(items, startOfTodayMs) { it.uploadedAtMs }

    /**
     * The same grouping for any list that has a time per element, such as the history's day overlines (spec 5.8), which
     * group by when a video was watched: `groups(entries, startOfToday) { it.lastWatchedMs }`. A null [timeOf] is Older.
     */
    fun <T> groups(items: List<T>, startOfTodayMs: Long, timeOf: (T) -> Long?): List<Pair<FeedGroup, List<T>>> {
        val buckets = FeedGroup.entries.associateWith { ArrayList<T>() }
        for (item in items) buckets.getValue(groupOf(timeOf(item), startOfTodayMs)).add(item)
        return FeedGroup.entries.mapNotNull { group -> buckets.getValue(group).takeIf { it.isNotEmpty() }?.let { group to it.toList() } }
    }

    private fun groupOf(uploadedAtMs: Long?, startOfTodayMs: Long): FeedGroup = when {
        uploadedAtMs == null -> FeedGroup.OLDER
        uploadedAtMs >= startOfTodayMs -> FeedGroup.TODAY
        uploadedAtMs >= startOfTodayMs - DAY_MS -> FeedGroup.YESTERDAY
        uploadedAtMs >= startOfTodayMs - 7 * DAY_MS -> FeedGroup.THIS_WEEK
        uploadedAtMs >= startOfTodayMs - 30 * DAY_MS -> FeedGroup.THIS_MONTH
        else -> FeedGroup.OLDER
    }
}
