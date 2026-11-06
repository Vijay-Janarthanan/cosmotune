package com.cosmotune.music.desktop

import com.cosmotune.desktop.DesktopWatch
import com.cosmotune.music.data.listentogether.PartyTrack
import com.cosmotune.watch.WatchPlayerStatus
import com.cosmotune.watch.WatchRoom
import com.cosmotune.watch.player.EngineWatchPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * A Watch film, episode or channel as a room drives it ([DesktopPartySync]), through the same steps as a room video: this
 * device finds its own copy in its own Watch plugins (the host's release ranked first), or plays the room's public stream when
 * it has none, on Video mode's libmpv engine. A copy whose length differs from the host's by more than a minute is noted in the
 * player's state ([com.cosmotune.watch.WatchPlayerState.lengthNote]).
 */
internal object DesktopRoomWatch : RoomVideoPlayer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var openedKey: String? = null

    private val player: EngineWatchPlayer? get() = runCatching { DesktopWatch.player }.getOrNull()

    override fun local(): DesktopRoomPlayback.LocalPlayer {
        val p = player ?: return DesktopRoomPlayback.LocalPlayer(null, false, false, false, 0, 0)
        val state = p.state.value
        val snapshot = p.engine.snapshot.value
        val key = state.target?.key?.value ?: openedKey?.takeIf { state.status == WatchPlayerStatus.RESOLVING || state.status == WatchPlayerStatus.IDLE }
        return DesktopRoomPlayback.LocalPlayer(
            videoId = key,
            isPlaying = snapshot.playing && state.status == WatchPlayerStatus.PLAYING,
            isLoading = state.status in setOf(WatchPlayerStatus.RESOLVING, WatchPlayerStatus.LOADING, WatchPlayerStatus.BUFFERING),
            failed = state.status == WatchPlayerStatus.NOT_FOUND || state.status == WatchPlayerStatus.ERROR,
            positionMs = p.engine.positionMs(),
            durationMs = state.durationMs,
            speed = snapshot.speed,
        )
    }

    override fun shown(): Boolean = openedKey != null

    /** Whether the room opened [key] in Watch's player here (the player page then leaves it to the room). */
    fun holds(key: String): Boolean = openedKey == key

    override fun open(track: PartyTrack, startMs: Long): Boolean {
        if (!WatchRoom.canPlay(track)) return false
        val p = player ?: return false
        openedKey = track.videoId
        p.roomDurationMs = track.watch?.durationMs ?: track.durationMs
        scope.launch {
            val target = WatchRoom.targetOf(track, DesktopWatch.repository) ?: return@launch
            p.open(target, WatchRoom.summaryOf(track), startMs = startMs.coerceAtLeast(0), hint = WatchRoom.hintOf(track), fallback = WatchRoom.fallbackOf(track))
        }
        DesktopTrackLog.log("room: finding ${track.videoId} in this device's Watch plugins at ${startMs}ms")
        return true
    }

    override fun play() { player?.engine?.play() }
    override fun pause() { player?.engine?.pause() }
    override fun seekTo(ms: Long) { player?.engine?.seekTo(ms.coerceAtLeast(0)) }

    override fun stop() {
        if (openedKey == null) return
        openedKey = null
        player?.let { it.roomDurationMs = null; it.stop() }
    }
}

/**
 * The room's picture player on the desktop: Video mode's player for a room video, Watch's for a Watch item, each opened for
 * its own kind and the other closed, so one plays at a time.
 */
internal object DesktopRoomMedia : RoomVideoPlayer {
    @Volatile private var watchActive = false

    override fun local(): DesktopRoomPlayback.LocalPlayer = if (watchActive) DesktopRoomWatch.local() else DesktopRoomVideo.local()
    override fun shown(): Boolean = if (watchActive) DesktopRoomWatch.shown() else DesktopRoomVideo.shown()

    override fun open(track: PartyTrack, startMs: Long): Boolean {
        val watch = track.kind == PartyTrack.KIND_WATCH
        if (watch != watchActive) (if (watchActive) DesktopRoomWatch else DesktopRoomVideo).stop()
        watchActive = watch
        return if (watch) DesktopRoomWatch.open(track, startMs) else DesktopRoomVideo.open(track, startMs)
    }

    override fun play() = if (watchActive) DesktopRoomWatch.play() else DesktopRoomVideo.play()
    override fun pause() = if (watchActive) DesktopRoomWatch.pause() else DesktopRoomVideo.pause()
    override fun seekTo(ms: Long) = if (watchActive) DesktopRoomWatch.seekTo(ms) else DesktopRoomVideo.seekTo(ms)

    override fun stop() {
        if (watchActive) DesktopRoomWatch.stop() else DesktopRoomVideo.stop()
        watchActive = false
    }
}
