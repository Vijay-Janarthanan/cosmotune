package com.cosmotune.video.cast

import com.cosmotune.music.data.cast.dlna.DlnaDevice
import com.cosmotune.music.data.cast.dlna.DlnaDiscovery
import com.cosmotune.music.data.cast.dlna.DlnaMedia
import com.cosmotune.music.data.cast.dlna.DlnaMediaRelay
import com.cosmotune.music.data.cast.dlna.DlnaRenderer
import com.cosmotune.video.player.VideoPlayback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Plays the video on [playback] on a DLNA TV. YouTube's addresses answer only requests dressed as the app that was given them,
 * so the TV is given an address on this device ([DlnaMediaRelay]: a random token, local network only, running only while the
 * cast lasts), which passes each request on with the right headers and honours the TV's Range requests.
 *
 * One session at a time. Switching to another video, or a TV that finishes, fails or leaves the network, ends it; ending hands
 * the video back to this device at the place the TV got to.
 */
class VideoCasting(
    private val playback: VideoPlayback,
    private val receiverOf: (DlnaDevice) -> DlnaRenderer = { DlnaRenderer(it) },
    private val relayOf: () -> DlnaMediaRelay = { DlnaMediaRelay() },
) {
    val discovery by lazy { DlnaDiscovery() }
    private val rendererFlow = MutableStateFlow<DlnaRenderer?>(null)
    val renderer: StateFlow<DlnaRenderer?> = rendererFlow.asStateFlow()
    private val qualityFlow = MutableStateFlow<String?>(null)

    /** The quality the TV gets ("720p"), while one is cast. */
    val quality: StateFlow<String?> = qualityFlow.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var relay: DlnaMediaRelay? = null
    private var videoId: String? = null
    private var followJob: kotlinx.coroutines.Job? = null

    /** Why this video cannot go to a TV, or null when it can. */
    fun whyNot(): String? = when {
        playback.state.value.item == null -> "Start a video first."
        TvCast.choose(playback.streamsForCast()) == null -> TvCast.NOTHING_PLAYABLE
        else -> null
    }

    suspend fun connect(device: DlnaDevice) = lock.withLock {
        val choice = TvCast.choose(playback.streamsForCast()) ?: throw IllegalArgumentException(TvCast.NOTHING_PLAYABLE)
        val state = playback.state.value
        val item = state.item ?: throw IllegalArgumentException("Start a video first.")
        endLocked(stopReceiver = true, handBack = false)
        val target = receiverOf(device)
        val serving = relayOf()
        try {
            target.connect()
            val url = when (choice) {
                is TvCastChoice.Hls -> choice.url
                is TvCastChoice.Muxed -> serving.publish(
                    DlnaMediaRelay.Source.Remote(choice.stream.url, choice.headers, TvCast.mimeOf(choice)),
                    DlnaMediaRelay.hostOf(device.avTransportUrl),
                )
            }
            target.load(
                DlnaMedia(
                    url, TvCast.mimeOf(choice), item.title, item.channelName.orEmpty(), artworkUrl = item.thumbnailUrl,
                    durationMs = state.durationMs, video = true,
                ),
                playback.positionMs(),
            )
            check(!target.state.value.failed) { "The TV could not play this video" }
        } catch (error: Throwable) {
            withContext(NonCancellable) { runCatching { target.close(true) }; serving.close() }
            throw error
        }
        if (state.playing) playback.togglePlayPause()
        relay = serving
        videoId = item.id
        qualityFlow.value = choice.label
        rendererFlow.value = target
        followJob = scope.launch { follow(target, item.id) }
    }

    /** "Stop playing on TV": stops the TV and carries on here from where it got to. */
    suspend fun disconnect() = lock.withLock { endLocked(stopReceiver = true, handBack = true) }

    fun close() {
        discovery.stop()
        scope.launch { try { disconnect() } finally { scope.cancel() } }
    }

    private suspend fun follow(target: DlnaRenderer, id: String) = coroutineScope {
        val session = this
        launch {
            // Another video on this device ends the cast; it is not handed back.
            playback.state.first { it.item?.id != id }
            end(target, stopReceiver = true, handBack = false)
            session.cancel()
        }
        launch {
            val remote = target.state.first { it.lost || it.failed || it.finished }
            end(target, stopReceiver = !remote.lost, handBack = !remote.failed)
            session.cancel()
        }
    }

    private suspend fun end(target: DlnaRenderer, stopReceiver: Boolean, handBack: Boolean) =
        lock.withLock { if (rendererFlow.value === target) endLocked(stopReceiver, handBack) }

    private suspend fun endLocked(stopReceiver: Boolean, handBack: Boolean) {
        val target = rendererFlow.value ?: return
        rendererFlow.value = null
        qualityFlow.value = null
        followJob?.cancel()
        followJob = null
        val at = target.state.value.positionMs
        val sameVideo = playback.state.value.item?.id == videoId
        withContext(NonCancellable) {
            runCatching { target.close(stopReceiver) }
            relay?.close()
            relay = null
        }
        videoId = null
        if (handBack && sameVideo && at > 0) playback.seekTo(at)
    }
}

/** The one [VideoCasting] of a player, so a cast started from a menu is still there when the menu is gone. */
object VideoCasts {
    private val all = java.util.WeakHashMap<VideoPlayback, VideoCasting>()

    @Synchronized
    fun of(playback: VideoPlayback): VideoCasting = all.getOrPut(playback) { VideoCasting(playback) }
}
