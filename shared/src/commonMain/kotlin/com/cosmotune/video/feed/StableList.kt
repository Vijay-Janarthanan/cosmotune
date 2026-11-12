package com.cosmotune.video.feed

import com.cosmotune.video.model.VideoItem

/**
 * How a list that is already on screen takes in more (a page fetched while scrolling, a refresh in the background, the list
 * rebuilt on coming back): what is shown stays where it is, in the order it has, and only what is new is added. Pure.
 */
object StableList {
    /** [shown] as it is, then what [fetched] holds that [shown] does not (each once, in the order fetched). [shown] itself when nothing is new. */
    fun append(shown: List<VideoItem>, fetched: List<VideoItem>): List<VideoItem> {
        val seen = HashSet<String>(shown.size + fetched.size)
        for (item in shown) seen += item.id
        val fresh = fetched.filter { seen.add(it.id) }
        return if (fresh.isEmpty()) shown else shown + fresh
    }

    /**
     * Like [append] for a list that runs newest first: each new item goes before the first shown item that is older than it
     * (after the last shown one when none is), items without a time go last. The shown ones never change places among themselves.
     */
    fun byDate(shown: List<VideoItem>, fetched: List<VideoItem>): List<VideoItem> {
        val seen = HashSet<String>(shown.size + fetched.size)
        for (item in shown) seen += item.id
        val fresh = fetched.filter { seen.add(it.id) }.sortedWith(compareBy<VideoItem> { it.uploadedAtMs == null }.thenByDescending { it.uploadedAtMs ?: 0L })
        if (fresh.isEmpty()) return shown
        val out = ArrayList<VideoItem>(shown.size + fresh.size)
        var at = 0
        for (item in fresh) {
            val time = item.uploadedAtMs
            while (at < shown.size && !isOlder(shown[at], time)) out += shown[at++]
            out += item
        }
        while (at < shown.size) out += shown[at++]
        return out
    }

    private fun isOlder(existing: VideoItem, time: Long?): Boolean {
        val own = existing.uploadedAtMs
        return when {
            time == null -> false
            own == null -> true
            else -> own < time
        }
    }

    /** [shown] without the items [hidden] picks (a video the viewer hid), the rest in their places. */
    fun without(shown: List<VideoItem>, hidden: (VideoItem) -> Boolean): List<VideoItem> =
        if (shown.none(hidden)) shown else shown.filterNot(hidden)
}

/**
 * The list a page shows, held for as long as the page is: every update is folded into what is already shown ([StableList.append],
 * or [StableList.byDate] for a newest-first list), so loading more only adds, and the page can be left and come back to the same
 * list. A change of [update]'s `mode` (another chip) or [reset] (a refresh the viewer asked for) starts the list again.
 */
class StableFeed(private val newestFirst: Boolean = false) {
    var shown: List<VideoItem> = emptyList()
        private set
    private var mode: String? = null

    /** Folds [fresh] in and returns the list to draw. [hidden] picks videos that must go (the viewer hid them) wherever they stand. */
    fun update(mode: String, fresh: List<VideoItem>, hidden: (VideoItem) -> Boolean = { false }): List<VideoItem> {
        if (mode != this.mode) { this.mode = mode; shown = emptyList() }
        val kept = StableList.without(shown, hidden)
        val folded = if (newestFirst) StableList.byDate(kept, fresh.filterNot(hidden)) else StableList.append(kept, fresh.filterNot(hidden))
        shown = folded
        return folded
    }

    /** Forgets the list: the next [update] shows what it is given. */
    fun reset() { shown = emptyList(); mode = null }
}
