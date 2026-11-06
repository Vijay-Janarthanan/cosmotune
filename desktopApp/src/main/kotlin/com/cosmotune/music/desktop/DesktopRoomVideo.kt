package com.cosmotune.music.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.cosmotune.design.AppMode
import com.cosmotune.design.AppModes
import com.cosmotune.music.data.listentogether.PartyTrack
import com.cosmotune.settings.CosmoLookSettings

/**
 * Video mode's player as a room drives it ([DesktopPartySync]): what it holds, whether Video mode is on screen, and the few things a
 * room does to it. Quality, captions, audio track and speed are never touched here; they stay the person's own.
 */
internal interface RoomVideoPlayer {
    /** The player as the room sees it; a null video id while it holds nothing. */
    fun local(): DesktopRoomPlayback.LocalPlayer

    /** Whether Video mode is the mode drawn, so a video the person closed is told apart from one not opened yet. */
    fun shown(): Boolean

    /** Opens [track] at [startMs] in Video mode, switching to it; false when Video cannot play here (it is switched off, or no look). */
    fun open(track: PartyTrack, startMs: Long): Boolean

    fun play()
    fun pause()
    fun seekTo(ms: Long)

    /** Closes the room's video and, if the room switched the person to Video mode and they are still there, goes back. */
    fun stop()
}

/**
 * The desktop's [RoomVideoPlayer]: Video mode's own controller from the process-wide graph, the one its pages drive. Nothing builds
 * the graph until a room opens a video, so a room that only plays music never starts Video's player. [look] is the window's look
 * settings (the stored mode), handed over by the window once it has them.
 */
internal object DesktopRoomVideo : RoomVideoPlayer {
    @Volatile var look: CosmoLookSettings? = null

    /** The mode the room took the person from when it opened a video, to go back to when the room moves on to something else. */
    private var modeBefore: AppMode? = null
    private var openedId: String? = null

    private val playback get() = com.cosmotune.video.graph.desktopVideoGraph(build = false)?.playback

    override fun local(): DesktopRoomPlayback.LocalPlayer {
        val player = playback ?: return DesktopRoomPlayback.LocalPlayer(null, false, false, false, 0, 0)
        val state = player.state.value
        return DesktopRoomPlayback.LocalPlayer(
            videoId = state.item?.id,
            isPlaying = state.playing,
            isLoading = state.status == com.cosmotune.video.player.PlayerStatus.LOADING || state.status == com.cosmotune.video.player.PlayerStatus.BUFFERING,
            failed = state.error != null || state.status == com.cosmotune.video.player.PlayerStatus.ERROR,
            positionMs = player.positionMs(),
            durationMs = state.durationMs,
            speed = state.speed,
        )
    }

    override fun shown(): Boolean = look?.mode?.value == AppMode.VIDEO

    override fun open(track: PartyTrack, startMs: Long): Boolean {
        val settings = look ?: return false
        if (!AppModes.available(AppMode.VIDEO)) return false
        val player = com.cosmotune.video.graph.desktopVideoGraph()?.playback ?: return false
        val mode = settings.mode.value
        // Taken from wherever the person is now; already in Video, the way back is the one an earlier room video kept, if any.
        if (mode != AppMode.VIDEO) {
            modeBefore = mode
            settings.setMode(AppMode.VIDEO)
        }
        // Video's page opens its watch page on this once it is drawn, and finds the video already on, so it leaves it going.
        com.cosmotune.video.host.DesktopModeHost.askedVideo = com.cosmotune.video.ui.PendingVideo(track.videoId, null)
        openedId = track.videoId
        player.play(track.videoId, startMs.coerceAtLeast(0))
        DesktopTrackLog.log("room: opening the video ${track.videoId} in Video mode at ${startMs}ms")
        return true
    }

    override fun play() {
        val player = playback ?: return
        if (!player.state.value.playing) player.togglePlayPause()
    }

    override fun pause() {
        val player = playback ?: return
        if (player.state.value.playing) player.togglePlayPause()
    }

    override fun seekTo(ms: Long) {
        playback?.seekTo(ms.coerceAtLeast(0))
    }

    override fun stop() {
        playback?.stop()
        if (com.cosmotune.video.host.DesktopModeHost.askedVideo?.videoId == openedId) com.cosmotune.video.host.DesktopModeHost.askedVideo = null
        openedId = null
        val before = modeBefore
        modeBefore = null
        val settings = look ?: return
        if (before != null && settings.mode.value == AppMode.VIDEO) settings.setMode(before)
    }
}

/**
 * What the room screen shows in place of the artwork while the room plays [track] and it is a video: Video mode's own picture of it,
 * once this device's player holds it. Null otherwise (music, a station, an episode, or a video still opening), which draws the
 * artwork. The surface is the same one Video's watch page draws; the room screen stands in the page's place, so only one is drawn.
 */
@Composable
internal fun rememberRoomVideoMedia(track: PartyTrack?): (@Composable () -> Unit)? {
    if (track?.kind != PartyTrack.KIND_VIDEO && track?.kind != PartyTrack.KIND_WATCH) return null
    val graph = com.cosmotune.video.graph.desktopVideoGraph(build = false) ?: return null
    if (track.kind == PartyTrack.KIND_WATCH) {
        // Watch plays on the same engine, so the picture is the same surface, once this device holds the room's item.
        val surface = graph.surface ?: return null
        val held = DesktopRoomWatch.local().videoId == track.videoId
        val media: @Composable () -> Unit = remember(surface) { { surface.Surface(Modifier.fillMaxSize(), com.cosmotune.video.player.VideoScale.FIT) } }
        return media.takeIf { held }
    }
    val state by graph.playback.state.collectAsState()
    val surface = graph.surface ?: return null
    val media: @Composable () -> Unit = remember(surface) { { surface.Surface(Modifier.fillMaxSize(), com.cosmotune.video.player.VideoScale.FIT) } }
    return media.takeIf { state.item?.id == track.videoId }
}

/**
 * This device's own options for the room's video: Video mode's player settings menu (quality, captions, audio track, speed), opened
 * under the room's strip. They change this player only; the room never hears of them. Nothing opens while no video is loaded here.
 */
@Composable
internal fun DesktopRoomVideoOptions(onDismiss: () -> Unit) {
    val graph = com.cosmotune.video.graph.desktopVideoGraph(build = false) ?: return
    val state by graph.playback.state.collectAsState()
    val settings by graph.settings.settings.collectAsState()
    if (state.item == null) return
    // The stats overlay belongs to the watch page's player box, which is not on screen here.
    com.cosmotune.video.ui.player.PlayerSettingsMenu(graph.playback, state, settings, statsShown = false, onToggleStats = {}, onDismiss = onDismiss)
}
