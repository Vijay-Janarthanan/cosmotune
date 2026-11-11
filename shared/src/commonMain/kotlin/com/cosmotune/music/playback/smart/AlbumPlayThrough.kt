package com.cosmotune.music.playback.smart

import com.cosmotune.music.data.model.PlaybackSourceType
import com.cosmotune.music.data.model.QueueTier
import com.cosmotune.music.data.model.Song

/**
 * Whether [next] is the following track of an album being played through from its own page, the
 * one case that earns a gapless handover instead of a crossfade or a mix.
 *
 * Shared adjacency is not enough: a playlist, a radio, a manual queue or a shuffle that lands two
 * album siblings back to back is a mix and gets mixed. So the album has to be where the queue
 * came from (a release page, by its browse id), both tracks have to belong to that section of the
 * queue, nothing may be shuffled, and [next] has to follow [current] directly rather than through
 * a repeat-all wrap. Where the tracks name their own album or number, those have to agree too.
 */
fun isAlbumPlayThrough(current: Song?, next: Song?, shuffled: Boolean, nextFollowsDirectly: Boolean): Boolean {
    if (current == null || next == null || shuffled || !nextFollowsDirectly) return false
    if (current.videoId == next.videoId) return false
    if (current.queueTier != QueueTier.CONTEXT || next.queueTier != QueueTier.CONTEXT) return false
    if (current.isVideoOrigin || next.isVideoOrigin) return false
    val album = current.playbackSourceId?.takeIf(::isReleaseId) ?: return false
    if (current.playbackSourceType != PlaybackSourceType.BROWSE || next.playbackSourceType != PlaybackSourceType.BROWSE) return false
    if (next.playbackSourceId != album) return false
    val ownAlbums = listOfNotNull(current.albumId, next.albumId).filter { it.isNotBlank() }
    if (ownAlbums.distinct().size > 1) return false
    val a = current.trackNumber
    val b = next.trackNumber
    if (a != null && b != null && b != a + 1) return false
    return true
}

/** A YouTube Music release page, or the album playlist that backs one. */
private fun isReleaseId(id: String): Boolean = id.startsWith("MPREb") || id.startsWith("OLAK5uy_")
