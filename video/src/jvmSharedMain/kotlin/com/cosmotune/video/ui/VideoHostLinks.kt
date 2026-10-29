package com.cosmotune.video.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable

/**
 * What the host gives Video (spec section 9): leave for Music, play a video as a song, pause Music, and a video that is waiting
 * to be opened. The phone's host and the desktop's each build one; Video never reaches into Music's own state.
 *
 * [pendingVideo] is read when Video appears and whenever a link arrives; [onPendingHandled] tells the host that the request
 * was opened, so the same one is not opened twice.
 */
@Stable
class VideoHostLinks(
    /** Leaves for Music (the profile drawer's switch). */
    val switchToMusic: () -> Unit,
    /** "Play as a song": Video pauses, the mode becomes Music, and Music plays [videoId] as a song with this title, channel and picture. */
    val playAsSong: (videoId: String, title: String, channel: String, thumbnailUrl: String?) -> Unit,
    /** Music must not play under a video: asked when a video starts. */
    val pauseMusic: () -> Unit,
    /** The video to open on arrival, or null. */
    val pendingVideo: () -> PendingVideo?,
    /** The pending video was opened. */
    val onPendingHandled: () -> Unit,
)

/**
 * A video to open on arrival: a shared link (no start) or Music's "Watch the music video" (the song's moment, spec section 9).
 * [startMs] is null when the video starts from where it was left or from the beginning. A file on the device that another app handed
 * over ("Open with") has [localSource] (its path or `content://` address, [videoId] is then empty) and its [localName]; it opens in the
 * local player.
 */
@Immutable
data class PendingVideo(val videoId: String, val startMs: Long?, val localSource: String? = null, val localName: String? = null)
