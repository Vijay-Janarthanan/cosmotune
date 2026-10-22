package com.cosmotune.design

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem

/**
 * What the new Home draws at the top of its feed, decided from the shelves the feed sent (pure, so it is tested): the hero is the
 * first item of the first shelf (Recents leads the feed when it exists, so the hero then continues the last song), and the mix
 * tiles are the next two shelves that have at least three items. Every shelf is still drawn below; ordinary rails omit their
 * repeated hero when other items remain, while Recents retains the complete history.
 */
object HomePlan {
    /** The feed's own title of the shelf of recently played songs (`HomeScreen.RECENTS_TITLE`). */
    const val RECENTS_TITLE = "Recents"

    /** A shelf needs this many items to be a mix: its tile fans three covers. */
    const val MIX_MIN_ITEMS = 3

    /** The bento holds at most this many mix tiles. */
    const val MIX_MAX = 2

    /** One mix tile: the [shelf] it stands for and that shelf's place in the feed ([shelfIndex]). */
    class Mix(val shelfIndex: Int, val shelf: HomeShelf)

    /** The hero [hero] (null when the feed has no item at all) of the shelf [heroShelfIndex], and the [mixes] in feed order. */
    class Plan(val hero: ShelfItem?, val heroShelfIndex: Int, val mixes: List<Mix>)

    fun of(shelves: List<HomeShelf>): Plan {
        val heroShelfIndex = shelves.indexOfFirst { it.items.isNotEmpty() }
        if (heroShelfIndex < 0) return Plan(hero = null, heroShelfIndex = -1, mixes = emptyList())
        val mixes = ArrayList<Mix>(MIX_MAX)
        for (index in heroShelfIndex + 1 until shelves.size) {
            if (mixes.size == MIX_MAX) break
            if (shelves[index].items.size >= MIX_MIN_ITEMS) mixes += Mix(index, shelves[index])
        }
        return Plan(hero = shelves[heroShelfIndex].items.first(), heroShelfIndex = heroShelfIndex, mixes = mixes)
    }

    /** Whether the shelf at [index] is the recents shelf: the first shelf, titled "Recents" (the old screen's rule, case ignored). */
    fun isRecents(shelves: List<HomeShelf>, index: Int): Boolean =
        index == 0 && shelves.getOrNull(0)?.title.equals(RECENTS_TITLE, ignoreCase = true)

    /** The line under a mix title when the shelf has no strapline of its own: its first two item titles. */
    fun fallbackSubtitle(shelf: HomeShelf): String = shelf.items.take(2).joinToString(", ") { it.title }

    /** Recents deliberately keeps its full history; ordinary browse rails need not repeat their hero. */
    fun shelfItems(shelves: List<HomeShelf>, plan: Plan?, index: Int): List<ShelfItem> {
        val items = shelves.getOrNull(index)?.items.orEmpty()
        if (plan == null || items.size <= 1 || index != plan.heroShelfIndex || isRecents(shelves, index)) return items
        return items.drop(1)
    }

    /**
     * The place of the first shelf in the list's items: the header comes first, then the sign-in [banner], the [moods] row, the
     * [bento] and the [recentsSkeleton] (shown while Recents is still loading), whichever of them are drawn. A tile scrolls to a
     * shelf at this index plus the shelf's own place.
     */
    fun firstShelfItemIndex(banner: Boolean, moods: Boolean, bento: Boolean, recentsSkeleton: Boolean): Int =
        1 + (if (banner) 1 else 0) + (if (moods) 1 else 0) + (if (bento) 1 else 0) + (if (recentsSkeleton) 1 else 0)
}
