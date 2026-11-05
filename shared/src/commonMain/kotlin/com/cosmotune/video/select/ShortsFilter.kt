package com.cosmotune.video.select

import com.cosmotune.video.model.VideoItem

/**
 * Hide Shorts (spec 7.4): a pure filter on lists. Shorts are shown by default (the owner's decision, Q6); with the switch on
 * (`VideoSettings.hideShorts`) every list drops them.
 *
 * An item is a Short when the source marks it ([VideoItem.isShort]: the list item's `isShortFormContent`, a channel's Shorts
 * tab, a feed entry whose link is a `/shorts/` address, which the feed itself already drops while hiding), or when it lasts a
 * minute or less (1 to 60 s) and its picture is a Shorts one: an address with `/shorts/` in it, or the source's names for a
 * vertical picture, `oardefault` and `oar2`.
 */
object ShortsFilter {
    /** The longest a video can be and still count as a Short by its length and picture (spec 7.4: 60 s or less). */
    const val MAX_SHORT_SEC = 60L

    private val shortsPictures = listOf("/shorts/", "oardefault", "oar2")

    /** Whether [item] is a Short by the rules above. */
    fun isShort(item: VideoItem): Boolean {
        if (item.isShort) return true
        val seconds = item.durationSec ?: return false
        if (seconds !in 1..MAX_SHORT_SEC) return false
        val picture = item.thumbnailUrl ?: return false
        return shortsPictures.any { picture.contains(it, ignoreCase = true) }
    }

    /** Whether a list keeps [item]: always while Shorts are shown, only a video that is not a Short while [hideShorts]. */
    fun keep(item: VideoItem, hideShorts: Boolean): Boolean = !hideShorts || !isShort(item)

    /**
     * [items] without their Shorts while [hideShorts]; [item] gives the video of an entry, or null for one that is no video (a
     * channel, a playlist), which is kept. The list comes back as it was (the same instance) when nothing is removed.
     */
    fun <T> filter(items: List<T>, hideShorts: Boolean, item: (T) -> VideoItem?): List<T> {
        if (!hideShorts || items.isEmpty()) return items
        val kept = items.filter { entry -> item(entry)?.let { keep(it, hideShorts = true) } ?: true }
        return if (kept.size == items.size) items else kept
    }
}
