package com.cosmotune.music.playback.cast

import android.content.Context
import android.net.Uri
import com.cosmotune.music.data.cast.dlna.DlnaMedia
import com.cosmotune.music.data.cast.dlna.DlnaState
import com.cosmotune.watch.WatchReceiver
import com.cosmotune.watch.WatchReceiverFinder
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Google Cast devices for Watch's Play on a TV, found through [CastController]'s router. */
class GoogleCastWatchFinder(private val context: Context) : WatchReceiverFinder {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow<List<WatchReceiverFinder.Found>>(emptyList())
    override val found: StateFlow<List<WatchReceiverFinder.Found>> = mutable.asStateFlow()
    private val hosts = HashMap<String, String?>()

    init {
        scope.launch {
            CastController.state.collect { state ->
                state.devices.forEach { hosts[it.id] = it.host }
                mutable.value = state.devices.map { WatchReceiverFinder.Found(it.id, it.name) }
            }
        }
    }

    override fun search(): () -> Unit {
        CastController.ensureStarted(context)
        var stop: (() -> Unit)? = null
        // The framework comes up on its own thread; keep asking until it is there.
        val waiting = scope.launch {
            while (isActive && !CastController.state.value.supported) delay(500)
            stop = CastController.discover(active = true)
        }
        return { waiting.cancel(); stop?.invoke() }
    }

    override fun open(found: WatchReceiverFinder.Found): WatchReceiver = GoogleCastWatchReceiver(found.id, found.name, hosts[found.id])
}

/** One Google Cast device playing a Watch video on the stock media receiver. */
private class GoogleCastWatchReceiver(override val id: String, override val name: String, private val host: String?) :
    WatchReceiver, CastController.Guest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(DlnaState())
    override val state: StateFlow<DlnaState> = mutable.asStateFlow()
    override val relayHost: String? get() = host
    private val started = CompletableDeferred<CastSession?>()
    @Volatile private var session: CastSession? = null
    @Volatile private var closing = false
    private var sawPlaying = false
    private var ticker: Job? = null

    private val callback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() = publish()
    }

    override fun onSessionBegan(session: CastSession) {
        this.session = session
        started.complete(session)
    }

    override fun onSessionEnded() {
        started.complete(null)
        val was = session
        session = null
        ticker?.cancel()
        runCatching { was?.remoteMediaClient?.unregisterCallback(callback) }
        // Not asked for here: the TV went off, left the network, or someone else took it.
        if (!closing) mutable.update { it.copy(playing = false, lost = true) }
    }

    override suspend fun connect() {
        withContext(Dispatchers.Main) { CastController.connectGuest(id, this@GoogleCastWatchReceiver) }
        val live = try { withTimeout(30_000) { started.await() } } catch (timeout: Exception) {
            withContext(Dispatchers.Main) { CastController.endGuest(this@GoogleCastWatchReceiver) }
            null
        }
        checkNotNull(live) { "$name did not answer" }
    }

    override suspend fun load(media: DlnaMedia, startMs: Long) = withContext(Dispatchers.Main) {
        val remote = checkNotNull(session?.remoteMediaClient) { "$name is not connected" }
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, media.title)
            if (media.artist.isNotBlank()) putString(MediaMetadata.KEY_SUBTITLE, media.artist)
            media.artworkUrl?.takeIf { it.startsWith("http") }?.let { addImage(WebImage(Uri.parse(it))) }
        }
        val info = MediaInfo.Builder(media.url)
            .setStreamType(if (media.durationMs > 0) MediaInfo.STREAM_TYPE_BUFFERED else MediaInfo.STREAM_TYPE_LIVE)
            .setContentType(media.mimeType)
            .setMetadata(metadata)
            .apply { if (media.durationMs > 0) setStreamDuration(media.durationMs) }
            .build()
        remote.registerCallback(callback)
        val request = MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true)
            .setCurrentTime(if (media.durationMs > 0) startMs else 0).build()
        val result = remote.load(request).awaitResult()
        if (!result.status.isSuccess) {
            mutable.update { it.copy(failed = true) }
            error("$name could not play this copy")
        }
        publish()
        ticker = scope.launch { while (isActive) { delay(1_000); publish() } }
    }

    private fun publish() {
        val remote = session?.remoteMediaClient ?: return
        val status = remote.mediaStatus ?: return
        val playing = status.playerState == MediaStatus.PLAYER_STATE_PLAYING
        if (playing) sawPlaying = true
        val idle = status.playerState == MediaStatus.PLAYER_STATE_IDLE
        mutable.value = DlnaState(
            playing = playing,
            buffering = status.playerState == MediaStatus.PLAYER_STATE_BUFFERING || status.playerState == MediaStatus.PLAYER_STATE_LOADING,
            positionMs = remote.approximateStreamPosition.coerceAtLeast(0),
            durationMs = remote.streamDuration.coerceAtLeast(0),
            volume = session?.volume?.toFloat()?.coerceIn(0f, 1f),
            finished = idle && sawPlaying && status.idleReason == MediaStatus.IDLE_REASON_FINISHED,
            failed = idle && status.idleReason == MediaStatus.IDLE_REASON_ERROR,
        )
    }

    override suspend fun play() = withContext(Dispatchers.Main) { session?.remoteMediaClient?.play(); Unit }
    override suspend fun pause() = withContext(Dispatchers.Main) { session?.remoteMediaClient?.pause(); Unit }
    override suspend fun seek(positionMs: Long) = withContext(Dispatchers.Main) {
        session?.remoteMediaClient?.seek(MediaSeekOptions.Builder().setPosition(positionMs).build()); Unit
    }

    override suspend fun setVolume(volume: Float) = withContext(Dispatchers.Main) {
        runCatching { session?.volume = volume.toDouble().coerceIn(0.0, 1.0) }
        mutable.update { it.copy(volume = volume) }
    }

    override suspend fun close(stopPlayback: Boolean) = withContext(Dispatchers.Main) {
        closing = true
        ticker?.cancel()
        runCatching { session?.remoteMediaClient?.unregisterCallback(callback) }
        // A receiver that went away has nothing left to stop.
        if (stopPlayback || session != null) CastController.endGuest(this@GoogleCastWatchReceiver)
        session = null
    }
}
