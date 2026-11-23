package com.cosmotune.video.host

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import com.cosmotune.video.player.PlayerStatus
import kotlinx.coroutines.delay

/** What Video plays, for the tray, its menu and the mini player: the video, its channel and picture, and where it is. */
data class DesktopVideoNow(
    val title: String,
    val channel: String,
    val thumbnailUrl: String?,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
)

/**
 * The tray, its menu and the mini player follow Video while a video is on and Music is not playing (Music keeps them while it plays):
 * Play and Pause, Next and Previous then drive Video's player.
 */
object DesktopVideoTray {
    private fun playback() = com.cosmotune.video.graph.desktopVideoGraph(build = false)?.playback

    /** The video on now, or null when Video plays nothing (or Music is playing, [musicPlaying]). */
    fun now(musicPlaying: Boolean): DesktopVideoNow? {
        if (musicPlaying) return null
        val p = playback() ?: return null
        val s = p.state.value
        val item = s.item ?: return null
        if (s.status == PlayerStatus.IDLE || s.status == PlayerStatus.ENDED || s.status == PlayerStatus.ERROR) return null
        return DesktopVideoNow(item.title, item.channelName.orEmpty(), item.thumbnailUrl, s.playing, p.positionMs(), s.durationMs)
    }

    fun active(musicPlaying: Boolean): Boolean = now(musicPlaying) != null

    fun playPause() { playback()?.togglePlayPause() }
    fun next() { playback()?.playNext() }
    fun previous() { playback()?.playPrevious() }
}

/** [DesktopVideoTray.now] as state, read once a second (the tray and the mini player need no finer step). */
@Composable
fun rememberDesktopVideoNow(musicPlaying: Boolean): State<DesktopVideoNow?> = produceState<DesktopVideoNow?>(null, musicPlaying) {
    while (true) {
        value = DesktopVideoTray.now(musicPlaying)
        delay(1_000)
    }
}
