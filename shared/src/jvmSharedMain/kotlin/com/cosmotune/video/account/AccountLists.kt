package com.cosmotune.video.account

import com.cosmotune.video.model.PlaylistItem
import com.cosmotune.video.model.VideoItem

/**
 * How the signed-in account's lists meet the local library's on the Library pages and in Subscriptions: one list, each video once
 * (by id). The account is the source of truth for what the account holds, so its entries come first; the local entries it does not
 * have follow, so nothing saved on this device is hidden by a sign-in. Signed out, or when the account cannot be read, the account
 * side is empty and the page is the local list exactly as before.
 */
object AccountLists {
    /** Watch later, Liked, History's account section: [account] first, then the [local] entries it does not hold. */
    fun merge(account: List<VideoItem>, local: List<VideoItem>): List<VideoItem> {
        if (account.isEmpty()) return local.distinctBy { it.id }
        val seen = HashSet<String>()
        val out = ArrayList<VideoItem>(account.size + local.size)
        for (item in account) if (seen.add(item.id)) out += item
        for (item in local) if (seen.add(item.id)) out += item
        return out
    }

    /** The account's own playlists first, then the [local] saved ones it does not have. */
    fun mergePlaylists(account: List<PlaylistItem>, local: List<PlaylistItem>): List<PlaylistItem> {
        if (account.isEmpty()) return local.distinctBy { it.id }
        val seen = HashSet<String>()
        val out = ArrayList<PlaylistItem>(account.size + local.size)
        for (item in account) if (seen.add(item.id)) out += item
        for (item in local) if (seen.add(item.id)) out += item
        return out
    }

    /**
     * The Subscriptions feed: the account's `FEsubscriptions` page merged with the local feed (the channels followed on this device,
     * read from their feeds). A video both have is the local one, whose upload time is exact; an account video gets its time
     * estimated from its "3 hours ago" ([estimateUploadedAt]). Newest first; a video with no time keeps its place after the dated
     * ones, account order first.
     */
    fun mergeFeed(account: List<VideoItem>, local: List<VideoItem>, nowMs: Long): List<VideoItem> {
        if (account.isEmpty()) return local
        val localById = local.associateBy { it.id }
        val seen = HashSet<String>()
        val merged = ArrayList<VideoItem>(account.size + local.size)
        for (item in account) {
            if (!seen.add(item.id)) continue
            merged += localById[item.id]
                ?: if (item.uploadedAtMs == null) item.copy(uploadedAtMs = estimateUploadedAt(item.uploadedText, nowMs)) else item
        }
        for (item in local) if (seen.add(item.id)) merged += item
        // A stable sort: equal and missing times keep the order they came in.
        return merged.sortedWith(compareBy<VideoItem> { it.uploadedAtMs == null }.thenByDescending { it.uploadedAtMs ?: 0L })
    }

    /**
     * The time a relative upload text stands for ("3 hours ago", "Streamed 2 days ago", "1 year ago"), or null for anything else
     * (another language, "Premieres tomorrow"). Months and years are taken as 30 and 365 days: the feed only needs the day group.
     */
    fun estimateUploadedAt(text: String?, nowMs: Long): Long? {
        val match = RELATIVE.find(text?.lowercase() ?: return null) ?: return null
        val amount = match.groupValues[1].toLongOrNull() ?: return null
        val unitMs = when (match.groupValues[2]) {
            "second" -> 1_000L
            "minute" -> 60_000L
            "hour" -> 3_600_000L
            "day" -> DAY_MS
            "week" -> 7 * DAY_MS
            "month" -> 30 * DAY_MS
            "year" -> 365 * DAY_MS
            else -> return null
        }
        return nowMs - amount * unitMs
    }

    private const val DAY_MS = 86_400_000L
    private val RELATIVE = Regex("""(\d+)\s+(second|minute|hour|day|week|month|year)s?\s+ago""")
}
