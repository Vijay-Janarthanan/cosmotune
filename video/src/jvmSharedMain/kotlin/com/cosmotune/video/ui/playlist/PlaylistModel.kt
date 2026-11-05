package com.cosmotune.video.ui.playlist

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cosmotune.video.model.PlaylistItem
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.source.VideoSource
import com.cosmotune.video.ui.channel.ModelCache
import com.cosmotune.video.ui.channel.PageLoads
import com.cosmotune.video.ui.channel.PagedItems
import com.cosmotune.video.ui.channel.PagedState
import kotlinx.coroutines.CoroutineScope

/*
 * What the playlist page loads and says, apart from any window (plan task Y-20).
 */

/** A remote playlist's header: the playlist as the first page gave it, and its description. */
internal class PlaylistInfo(val playlist: PlaylistItem, val description: String?)

/** The most a "Download all" reads (the library's own cap on a playlist's items, spec 8.1). */
internal const val ALL_VIDEOS_LIMIT = 5_000

/**
 * A remote playlist's data: the header and the videos, in pages ([videos]). The header comes with the first page, so [info] is null
 * until [PagedItems.start] has read it.
 */
@Stable
internal class PlaylistModel(private val source: VideoSource, scope: CoroutineScope, val playlistId: String) {
    var info: PlaylistInfo? by mutableStateOf(null)
        private set

    val videos: PagedItems<VideoItem> = PagedItems(scope) { token ->
        val page = source.playlist(playlistId, token)
        if (token == null) info = PlaylistInfo(page.playlist, page.description)
        page.first
    }

    /** Loads the first page, once. */
    fun start() = videos.start()

    /** Whether this model is worth keeping for the next visit: a first page that failed is not. */
    val reusable: Boolean get() = videos.state != PagedState.FAILED_FIRST

    /** Every video of the playlist, reading the pages that are not in yet (up to [ALL_VIDEOS_LIMIT]): what "Download all" offers. */
    suspend fun everything(): List<VideoItem> {
        videos.loadUntil(ALL_VIDEOS_LIMIT)
        return videos.items
    }
}

/** The playlist models in use. */
internal object PlaylistModels {
    private val cache = ModelCache<PlaylistModel>(capacity = 8, ttlMs = 10 * 60_000L, clock = System::currentTimeMillis, reusable = { it.reusable })

    fun obtain(source: VideoSource, playlistId: String): PlaylistModel =
        cache.obtain("playlist:$playlistId", source) { PlaylistModel(source, PageLoads.scope, playlistId) }
}

/** Words and numbers of the playlist page, apart from any window. */
internal object PlaylistText {
    /**
     * How long all of [items] last: the sum of their durations in seconds, or null when there is no item or one of them has no known
     * duration (a live stream, a premiere, a feed item).
     */
    fun totalSec(items: List<VideoItem>): Long? {
        if (items.isEmpty()) return null
        var sum = 0L
        for (item in items) {
            if (item.isLive || item.isUpcoming) return null
            sum += item.durationSec ?: return null
        }
        return sum
    }

    /** The first letter or digit of [name], upper case, for the owner's initial; empty when there is none. */
    fun initial(name: String): String = name.firstOrNull { it.isLetterOrDigit() }?.uppercase().orEmpty()
}
