package com.cosmotune.music.playback

import com.cosmotune.music.data.model.BrowseType
import com.cosmotune.music.data.model.DetailPage
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.model.UiState

/**
 * How a tap (or Shuffle) on an album or playlist page starts playback: the UI entry point of the bounded contexts (BC-05), as
 * upstream `edc1e6f3` wrote it inline in its `MainActivity` (`playDetailSongs` and `playFrom`). Pulled out here so the decision is
 * one tested function rather than a lambda in the activity.
 *
 * An unfiltered album or playlist in its canonical occurrence order starts a bounded context: the service is handed the page's newest
 * snapshot (the one the service itself may have grown, taken through [plan]'s `latest`) and its continuation, and from then on
 * owns the loading. Anything else (an artist's songs, a suggested row, a Cast session) plays the whole list as a queue.
 */
object ReleasePagePlayback {

    /** What to play: the queue, the row to start at, whether it is a bounded context, and the continuation behind the list. */
    data class Plan(val songs: List<Song>, val index: Int, val bounded: Boolean, val continuationToken: String?)

    /** The three ways a start is carried out, decided again at the moment of playing (a Cast session or a party may have begun). */
    enum class Route {
        /** In a Listen Together party the party's queue is what plays. */
        PARTY,
        /** The service-owned bounded context window (`MediaController.playBoundedContext`). */
        BOUNDED,
        /** The whole list as an ordinary queue (`QueueCoordinator.buildContextQueue`). */
        WHOLE,
    }

    /**
     * The plan for a tap on row [index] of [tapped] (the rows as the page shows them, possibly filtered or sorted) on [page].
     * [latest] is asked only for a bounded start, and returns the newest snapshot of the page (upstream's `takePlaybackContext`);
     * [prepare] fills in what a row lacks for the queue (an album's own name). Null when there is nothing to play.
     */
    fun plan(
        page: DetailPage,
        tapped: List<Song>,
        index: Int,
        casting: Boolean,
        latest: (browseId: String) -> DetailPage?,
        prepare: (Song) -> Song = { it },
    ): Plan? {
        val selected = tapped.getOrNull(index)
        val pageKeys = (page.songs as? UiState.Success)?.data.orEmpty().map { it.contextEntryKey() }
        val bounded = (page.type == BrowseType.ALBUM || page.type == BrowseType.PLAYLIST) &&
            selected != null && tapped.map { it.contextEntryKey() } == pageKeys &&
            !casting
        val newest = if (bounded) latest(page.browseId) else null
        val newestSongs = newest?.let { (it.songs as? UiState.Success)?.data.orEmpty() }?.map(prepare)?.takeIf { it.isNotEmpty() }
        val queue = newestSongs ?: tapped.map(prepare)
        if (queue.isEmpty()) return null
        // Matching only the first ID loses a duplicate occurrence. Manual views keep
        // their exact index; a grown canonical snapshot preserves the nth occurrence.
        val queueIndex = if (bounded && selected != null) {
            val key = selected.contextEntryKey()
            val occurrence = tapped.take(index + 1).count { it.contextEntryKey() == key }
            queue.indices.filter { queue[it].contextEntryKey() == key }.getOrNull(occurrence - 1) ?: index
        } else index
        return Plan(
            songs = queue,
            index = queueIndex.coerceIn(queue.indices),
            bounded = bounded,
            continuationToken = if (bounded) newest?.continuation ?: page.continuation else null,
        )
    }

    /** Which path a start takes: a party first, then the bounded window only when asked for and no Cast receiver owns the queue. */
    fun route(bounded: Boolean, inParty: Boolean, casting: Boolean): Route = when {
        inParty -> Route.PARTY
        bounded && !casting -> Route.BOUNDED
        else -> Route.WHOLE
    }
}
