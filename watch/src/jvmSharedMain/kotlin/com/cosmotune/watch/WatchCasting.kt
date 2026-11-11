package com.cosmotune.watch

import com.cosmotune.music.data.cast.dlna.*
import com.cosmotune.watch.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Watch video casting to DLNA renderers on Android, Windows, Linux and macOS. Google Cast is not
 * supported for Watch.
 *
 * One cast session at a time, tied to the title and copy it was started with. Anything that
 * makes the phone or computer play something else (next episode, retry, another source, another
 * channel) ends the session and stops the receiver, rather than leaving it playing the old copy
 * under a screen that shows the new one. A receiver that finishes, fails or leaves the network
 * ends it too.
 *
 * The player's remote flag belongs to whichever [WatchCasting] last set it: one that is closing
 * late (its page left while a new page for the same player already casts) never clears another's.
 */
class WatchCasting(
    private val player: WatchPlayer,
    private val repository: WatchRepository,
    private val receiverOf: (DlnaDevice) -> DlnaRenderer = { DlnaRenderer(it) },
    private val now: () -> Long = System::currentTimeMillis,
) {
    val discovery by lazy { DlnaDiscovery() }
    private val mutable = MutableStateFlow<DlnaRenderer?>(null)
    val renderer: StateFlow<DlnaRenderer?> = mutable
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    @Volatile private var session: Session? = null

    private class Session(val renderer: DlnaRenderer, val lease: WatchPlaybackLease, val key: WatchKey?, val streamUrl: String) {
        var follow: Job? = null
        @Volatile var progress: WatchProgress? = null
    }

    fun whyNot(stream: Stream?): String? = when {
        stream == null -> "Choose a playable video first"
        stream.isTorrent || stream.headers.isNotEmpty() -> "This source needs a local relay. Choose a direct copy to cast."
        stream.protocol !in setOf(StreamProtocol.PROGRESSIVE, StreamProtocol.HLS) -> "This receiver needs a direct video or HLS stream"
        !stream.url.startsWith("https://") && !stream.url.startsWith("http://") -> "This video is only available on this device"
        else -> null
    }

    suspend fun connect(device: DlnaDevice) = lock.withLock {
        val state = player.state.value
        val stream = state.stream
        whyNot(stream)?.let { throw IllegalArgumentException(it) }
        stream ?: return@withLock
        endLocked(stopReceiver = true)
        val target = receiverOf(device)
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
        } catch (error: Throwable) {
            opened?.close?.invoke()
            withContext(NonCancellable) { runCatching { target.close(true) } }
            throw error
        }
        val started = Session(target, opened, state.target?.key, stream.url)
        claimRemote()
        player.engine.pause()
        session = started
        mutable.value = target
        started.follow = scope.launch { follow(started, state) }
    }

    /** Stops the receiver, saves where it got to and hands playback back to this device. */
    suspend fun disconnect() = lock.withLock { endLocked(stopReceiver = true) }

    fun close() {
        discovery.stop()
        scope.launch { try { disconnect() } finally { scope.cancel() } }
    }

    private suspend fun follow(active: Session, opened: WatchPlayerState): Unit = coroutineScope {
        // The local player moving to anything else ends the session.
        launch {
            player.state.first { it.target?.key != active.key || it.stream?.url != active.streamUrl }
            end(active, stopReceiver = true)
        }
        var savedAt = 0L
        active.renderer.state.collect { remote ->
            val key = opened.target?.key
            val duration = remote.durationMs.takeIf { it > 0 } ?: opened.durationMs
            if (key != null && !opened.isLive && duration > 0 && remote.positionMs > 0) {
                val progress = WatchProgress(key, remote.positionMs, duration, now(), opened.summary,
                    (opened.target as? PlayTarget.EpisodeOf)?.episodeName)
                active.progress = progress
                if (now() - savedAt > 10_000) {
                    savedAt = now()
                    repository.saveProgress(progress)
                }
            }
            when {
                remote.lost -> end(active, stopReceiver = false)
                remote.failed || remote.finished -> end(active, stopReceiver = true)
            }
        }
    }

    /** Ends [active] if it is still the session; called from the session's own watcher, so run off its job. */
    private fun end(active: Session, stopReceiver: Boolean) {
        scope.launch { lock.withLock { if (session === active) endLocked(stopReceiver) } }
    }

    private suspend fun endLocked(stopReceiver: Boolean) {
        val ending = session ?: return
        session = null
        mutable.value = null
        withContext(NonCancellable) {
            ending.follow?.cancelAndJoin()
            // A newer cast on the same TV has already replaced this one's video there; stopping it
            // now would stop the newer cast's playback, not this one's.
            val successor = synchronized(owners) { owners[player]?.takeIf { it !== this@WatchCasting } }
            val replacedOnSameDevice = successor?.session?.renderer?.device?.id == ending.renderer.device.id
            runCatching { ending.renderer.close(stopReceiver && !replacedOnSameDevice) }
            runCatching { ending.lease.close() }
            val owned = releaseRemote()
            ending.progress?.let { progress ->
                repository.saveProgress(progress)
                // Picks up where the receiver was, but only for the same copy of the same title and
                // only if no other cast has taken the player over meanwhile.
                val now = player.state.value
                if (owned && now.target?.key == progress.key && now.stream?.url == ending.streamUrl) {
                    player.engine.seekTo(progress.positionMs)
                }
                WatchSyncSignals.checkpoint()
            }
        }
    }

    private fun claimRemote() {
        synchronized(owners) { owners[player] = this }
        player.setRemotePlayback(true)
    }

    /** Clears the player's remote flag if this instance still holds it; true when it did. */
    private fun releaseRemote(): Boolean {
        val owned = synchronized(owners) { (owners[player] === this).also { if (it) owners.remove(player) } }
        if (owned) player.setRemotePlayback(false)
        return owned
    }

    private companion object {
        val owners = java.util.WeakHashMap<WatchPlayer, WatchCasting>()
    }
}
