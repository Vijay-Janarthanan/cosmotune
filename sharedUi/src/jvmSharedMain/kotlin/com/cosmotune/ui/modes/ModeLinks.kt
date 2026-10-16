package com.cosmotune.ui.modes

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import com.music.bitchord.data.canvas.CanvasArtwork
import com.opencanvas.core.sync.CanvasSyncPolicy
import com.opencanvas.core.sync.SyncMap

/**
 * What Music may ask of the other modes (Video mode's spec, section 9). Music knows only this interface: the hosts of Video mode
 * (the phone's `PhoneModeHost`, the desktop's `rememberDesktopModeLinks`) implement it and provide it through [LocalModeLinks], so
 * Music never depends on the Video module.
 */
@Stable
interface ModeLinks {
    /**
     * "Watch the music video": the mode becomes Video and its watch page opens [videoId] (the music video OpenCanvas found for the
     * song [title] by [artist]) at [atVideoMs], the video's moment that matches the song's, or from where it was left when null.
     */
    fun watchMusicVideo(videoId: String, title: String, artist: String, atVideoMs: Long?)
}

/**
 * The links to the other modes, or null: provided by Video mode's hosts only while Video is available and the new look is on, so a
 * null means Music offers no way into Video.
 */
val LocalModeLinks: ProvidableCompositionLocal<ModeLinks?> = staticCompositionLocalOf { null }

/**
 * The action of the "Watch the music video" tool for [canvas], or null when the tool is not drawn: it needs [links] (Video is
 * available), the music video's id ([CanvasArtwork.videoId], which only OpenCanvas fills) and the song-to-video map
 * ([CanvasArtwork.syncMap], a music video that follows the song). The song's position is read by [songPositionMs] when the action
 * runs, never while composing.
 */
fun musicVideoLink(
    links: ModeLinks?,
    canvas: CanvasArtwork?,
    title: String,
    artist: String,
    songPositionMs: () -> Long,
): (() -> Unit)? {
    if (links == null) return null
    val videoId = canvas?.videoId ?: return null
    val syncMap = canvas.syncMap ?: return null
    val durationMs = canvas.videoDurationMs
    return { links.watchMusicVideo(videoId, title, artist, musicVideoMomentMs(syncMap, durationMs, songPositionMs())) }
}

/**
 * The music video's moment that matches the song's [songPositionMs] ([CanvasSyncPolicy.targetVideoMs]: the offset of the segment
 * in force there, or of the nearest one where the song has no picture), or null when the song is outside the video: before its
 * start, at or past its end ([videoDurationMs] when known, `<= 0` when not), or a map with no segment at all.
 */
fun musicVideoMomentMs(syncMap: SyncMap, videoDurationMs: Long, songPositionMs: Long): Long? {
    if (syncMap.isEmpty) return null
    val target = CanvasSyncPolicy(syncMap, videoDurationMs).targetVideoMs(songPositionMs.coerceAtLeast(0L))
    return target.takeIf { it >= 0L && (videoDurationMs <= 0L || it < videoDurationMs) }
}
