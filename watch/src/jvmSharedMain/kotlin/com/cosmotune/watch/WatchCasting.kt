package com.cosmotune.watch

import com.cosmotune.music.data.cast.dlna.*
import com.cosmotune.watch.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Watch video casting to DLNA renderers on Android, Windows, Linux and macOS. */
class WatchCasting(private val player: WatchPlayer, private val repository: WatchRepository) {
    val discovery = DlnaDiscovery()
    private val mutable = MutableStateFlow<DlnaRenderer?>(null)
    val renderer: StateFlow<DlnaRenderer?> = mutable
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var follow: Job? = null
    private var lease: WatchPlaybackLease? = null
    private var progress: WatchProgress? = null

    fun whyNot(stream: Stream?): String? = when {
        stream == null -> "Choose a playable video first"
        stream.isTorrent || stream.headers.isNotEmpty() -> "This source needs a local relay. Choose a direct copy to cast."
        stream.protocol !in setOf(StreamProtocol.PROGRESSIVE, StreamProtocol.HLS) -> "This receiver needs a direct video or HLS stream"
        !stream.url.startsWith("https://") && !stream.url.startsWith("http://") -> "This video is only available on this device"
        else -> null
    }

    suspend fun connect(device: DlnaDevice) {
        val state = player.state.value
        val stream = state.stream
        require(whyNot(stream) == null) { whyNot(stream).orEmpty() }
        stream ?: return
        disconnect()
        val target = DlnaRenderer(device)
        var opened: WatchPlaybackLease? = null
        try {
            target.connect()
            opened = repository.openPlayback(stream)
            val mime = when {
                stream.protocol == StreamProtocol.HLS -> "application/vnd.apple.mpegurl"
                stream.container == Container.WEBM -> "video/webm"
                stream.container == Container.MKV -> "video/x-matroska"
                else -> "video/mp4"
            }
            target.load(DlnaMedia(opened.url, mime, state.summary?.name ?: stream.label, state.subtitleLine.orEmpty(),
                artworkUrl = state.summary?.posterUrl, durationMs = state.durationMs, video = true), player.engine.positionMs())
            check(!target.state.value.failed) { "The receiver could not play this copy" }
            player.setRemotePlayback(true)
            player.engine.pause()
            lease = opened
            mutable.value = target
            var savedAt = 0L
            follow = scope.launch {
                target.state.collect { remote ->
                    val key = state.target?.key ?: return@collect
                    val duration = remote.durationMs.takeIf { it > 0 } ?: state.durationMs
                    if (!state.isLive && duration > 0 && remote.positionMs > 0) {
                        progress = WatchProgress(key, remote.positionMs, duration, System.currentTimeMillis(), state.summary,
                            (state.target as? PlayTarget.EpisodeOf)?.episodeName)
                        if (System.currentTimeMillis() - savedAt > 10_000) {
                            savedAt = System.currentTimeMillis()
                            repository.saveProgress(progress!!)
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            opened?.close?.invoke()
            target.close(true)
            throw error
        }
    }

    suspend fun disconnect() {
        follow?.cancelAndJoin(); follow = null
        val previous = mutable.value
        mutable.value = null
        previous?.close(true)
        lease?.close?.invoke(); lease = null
        progress?.let { repository.saveProgress(it); if (player.state.value.target?.key == it.key) player.engine.seekTo(it.positionMs); WatchSyncSignals.checkpoint() }
        progress = null
        player.setRemotePlayback(false)
    }
    fun close() { discovery.stop(); scope.launch { try { disconnect() } finally { scope.cancel() } } }
}
