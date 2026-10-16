package com.cosmotune.video.home

import com.cosmotune.video.library.HistoryEntry
import com.cosmotune.video.model.ListItem
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.select.ShortsFilter

/** The shelves Home can hold above its kiosk (spec 5.2), in the names of [HomePlanner.shelfOrder]. */
enum class HomeShelfKind { CONTINUE, SUBSCRIPTIONS, FOR_YOU, SHORTS }

/**
 * What Home's shelves hold and in which order (spec 5.2), decided on plain data: no clock, no source, no screen. The screen reads
 * the library, the feed and the kiosk, hands the lists over, and draws what comes back; a shelf with nothing in it is not drawn.
 *
 * Every list here is "newest first" where the spec says so, free of repeats (a video is shown once in a shelf), and capped.
 */
object HomePlanner {
    /** "Continue watching" shows at most 10 videos (spec 5.2). */
    const val CONTINUE_MAX = 10

    /** "From your subscriptions" shows the 10 newest feed items (spec 5.2). */
    const val SUBSCRIPTIONS_MAX = 10

    /** "Up next for you" shows at most 12 videos (spec 5.2). */
    const val FOR_YOU_MAX = 12

    /** "Up next for you" and the Shorts shelf draw on the last 3 videos watched (spec 5.2). */
    const val RECENT_WATCHES = 3

    /** The Shorts shelf shows at most 12 Shorts (spec 5.2). */
    const val SHORTS_MAX = 12

    /** The Shorts shelf is shown from 3 Shorts (spec 5.2: "shown from 3 items"). */
    const val SHORTS_MIN = 3

    /**
     * "Continue watching": the entries of [history] that are between 10 % and 90 % watched (10 % itself counts, 90 % is watched
     * and does not) with a known duration (the entry's, else the video's own), that are not Shorts, newest first, at most
     * [CONTINUE_MAX]. [history] may come in any order.
     */
    fun continueWatching(history: List<HistoryEntry>): List<HistoryEntry> =
        history.filter(::inProgress).sortedByDescending { it.lastWatchedMs }.take(CONTINUE_MAX)

    private fun inProgress(entry: HistoryEntry): Boolean {
        if (ShortsFilter.isShort(entry.item)) return false
        val total = entry.item.effectiveDurationMs(entry.durationMs) ?: return false
        return entry.positionMs * 10 >= total && !entry.isWatched
    }

    /**
     * The [count] videos watched last, newest first, each video once (at its newest time). [history] may come in any order; it is
     * read once, without sorting it, so a full history of 10,000 entries costs one pass.
     */
    fun recentWatches(history: List<HistoryEntry>, count: Int = RECENT_WATCHES): List<HistoryEntry> {
        if (count <= 0) return emptyList()
        val top = ArrayList<HistoryEntry>(count + 1)
        for (entry in history) {
            val same = top.indexOfFirst { it.item.id == entry.item.id }
            if (same >= 0) {
                if (top[same].lastWatchedMs >= entry.lastWatchedMs) continue
                top.removeAt(same)
            } else if (top.size == count && top[top.lastIndex].lastWatchedMs >= entry.lastWatchedMs) {
                continue
            }
            var at = top.size
            while (at > 0 && top[at - 1].lastWatchedMs < entry.lastWatchedMs) at--
            top.add(at, entry)
            if (top.size > count) top.removeAt(top.lastIndex)
        }
        return top
    }

    /**
     * "Up next for you": the related videos of the last three distinct videos in [lastWatched] ([related] holds a watched video's
     * related list by its id), taken one from each in turn, minus every video that is in [history] (watched, or started), minus
     * the repeats, minus the Shorts while [hideShorts], at most [FOR_YOU_MAX]. Channels and playlists in a related list are not
     * videos and are left out. A watched video with no related list yet gives nothing; the others carry on.
     */
    fun forYou(
        lastWatched: List<HistoryEntry>,
        related: Map<String, List<ListItem>>,
        history: List<HistoryEntry>,
        hideShorts: Boolean,
    ): List<VideoItem> {
        val seen = HashSet<String>(history.size * 2)
        for (entry in history) seen.add(entry.item.id)
        val lists = recentWatches(lastWatched).map { watched ->
            related[watched.item.id].orEmpty().mapNotNull { (it as? ListItem.Video)?.item }
                .filter { it.id !in seen && ShortsFilter.keep(it, hideShorts) }
        }
        return interleave(lists, FOR_YOU_MAX)
    }

    /** One from each list in turn, a video only once, until [max] are taken or the lists run out. */
    private fun interleave(lists: List<List<VideoItem>>, max: Int): List<VideoItem> {
        val out = ArrayList<VideoItem>()
        val taken = HashSet<String>()
        val at = IntArray(lists.size)
        var progressed = true
        while (out.size < max && progressed) {
            progressed = false
            for (i in lists.indices) {
                if (out.size >= max) break
                val list = lists[i]
                while (at[i] < list.size && list[at[i]].id in taken) at[i]++
                if (at[i] < list.size) {
                    val next = list[at[i]++]
                    taken.add(next.id)
                    out.add(next)
                    progressed = true
                }
            }
        }
        return out
    }

    /**
     * The Shorts shelf: the Shorts of the subscription feed (newest first), then those of the related lists of recent watches, then
     * those of the selected kiosk; each list is cut down to its Shorts, a Short is shown once (at its first place), at most
     * [SHORTS_MAX]. Fewer than [SHORTS_MIN] give an empty list: the shelf is not worth showing.
     */
    fun shorts(feedShorts: List<VideoItem>, relatedShorts: List<VideoItem>, kioskShorts: List<VideoItem>): List<VideoItem> {
        val all = newestFirst(feedShorts.filter(ShortsFilter::isShort)) +
            relatedShorts.filter(ShortsFilter::isShort) + kioskShorts.filter(ShortsFilter::isShort)
        val picked = all.distinctBy { it.id }.take(SHORTS_MAX)
        return if (picked.size < SHORTS_MIN) emptyList() else picked
    }

    /**
     * "From your subscriptions": the [SUBSCRIPTIONS_MAX] newest of [feed] (newest first; an item with no date comes last), a video
     * once, without the Shorts while [hideShorts].
     */
    fun subscriptionsShelf(feed: List<VideoItem>, hideShorts: Boolean): List<VideoItem> =
        newestFirst(feed.filter { ShortsFilter.keep(it, hideShorts) }).distinctBy { it.id }.take(SUBSCRIPTIONS_MAX)

    /**
     * The shelves to draw above the kiosk, top to bottom, from which ones have something to show: Continue watching, From your
     * subscriptions, Up next for you, and the Shorts shelf after the first of those (first of all when there is no other).
     */
    fun shelfOrder(hasContinue: Boolean, hasSubscriptions: Boolean, hasForYou: Boolean, hasShorts: Boolean): List<HomeShelfKind> {
        val shelves = ArrayList<HomeShelfKind>(4)
        if (hasContinue) shelves.add(HomeShelfKind.CONTINUE)
        if (hasSubscriptions) shelves.add(HomeShelfKind.SUBSCRIPTIONS)
        if (hasForYou) shelves.add(HomeShelfKind.FOR_YOU)
        if (hasShorts) shelves.add(minOf(1, shelves.size), HomeShelfKind.SHORTS)
        return shelves
    }

    private fun newestFirst(items: List<VideoItem>): List<VideoItem> =
        items.sortedByDescending { it.uploadedAtMs ?: Long.MIN_VALUE }
}
