package com.cosmotune.watch

import com.cosmotune.music.data.cast.dlna.DlnaMedia
import com.cosmotune.music.data.cast.dlna.DlnaMediaRelay
import com.cosmotune.music.data.cast.dlna.DlnaRenderer
import com.cosmotune.music.data.cast.dlna.DlnaState
import kotlinx.coroutines.flow.StateFlow

/**
 * A TV or stick the Watch player can hand a video to: a DLNA renderer, or a Google Cast device on
 * Android phones. It reports what it is doing the way a DLNA renderer does ([DlnaState]).
 */
interface WatchReceiver {
    val id: String
    val name: String
    val state: StateFlow<DlnaState>
    /** The host a relay on this device must be reachable from, or null when this receiver cannot use one. */
    val relayHost: String?
    suspend fun connect()
    suspend fun load(media: DlnaMedia, startMs: Long)
    suspend fun play()
    suspend fun pause()
    suspend fun seek(positionMs: Long)
    suspend fun setVolume(volume: Float)
    suspend fun close(stopPlayback: Boolean)
}

class DlnaWatchReceiver(val renderer: DlnaRenderer) : WatchReceiver {
    override val id get() = renderer.device.id
    override val name get() = renderer.device.name
    override val state get() = renderer.state
    override val relayHost get() = DlnaMediaRelay.hostOf(renderer.device.avTransportUrl)
    override suspend fun connect() = renderer.connect()
    override suspend fun load(media: DlnaMedia, startMs: Long) = renderer.load(media, startMs)
    override suspend fun play() = renderer.play()
    override suspend fun pause() = renderer.pause()
    override suspend fun seek(positionMs: Long) = renderer.seek(positionMs)
    override suspend fun setVolume(volume: Float) = renderer.setVolume(volume)
    override suspend fun close(stopPlayback: Boolean) = renderer.close(stopPlayback)
}

/** Receivers found some other way than DLNA, such as Google Cast on Android phones. */
interface WatchReceiverFinder {
    data class Found(val id: String, val name: String)

    /** Receivers seen while a [search] runs. */
    val found: StateFlow<List<Found>>

    /** Starts looking; the returned function stops. */
    fun search(): () -> Unit

    fun open(found: Found): WatchReceiver
}

/** Extra receiver finders a platform adds (the Android app sets [googleCast] when Play Services is there). */
object WatchReceiverFinders {
    @Volatile var googleCast: WatchReceiverFinder? = null
}
