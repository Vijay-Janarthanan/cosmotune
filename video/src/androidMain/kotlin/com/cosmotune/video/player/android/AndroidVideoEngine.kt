package com.cosmotune.video.player.android

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.view.SurfaceView
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import com.cosmotune.video.model.SubtitleTrack
import com.cosmotune.video.player.EngineCapabilities
import com.cosmotune.video.player.LocalSubtitle
import com.cosmotune.video.player.MediaInput
import com.cosmotune.video.player.PlaybackRequest
import com.cosmotune.video.player.PlayerError
import com.cosmotune.video.player.PlayerErrorKind
import com.cosmotune.video.player.PlayerSnapshot
import com.cosmotune.video.player.PlayerStatus
import com.cosmotune.video.player.SubtitleCue
import com.cosmotune.video.player.VideoPlayerEngine
import com.cosmotune.video.player.VideoScale
import com.cosmotune.video.player.VideoSurfaceProvider
import com.cosmotune.video.select.BandwidthWarmUp
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * The Android engine (spec 6.2; plan task Y-11): Media3 behind [VideoPlayerEngine], and the picture behind [VideoSurfaceProvider].
 *
 * **Threads.** Every method may be called from any thread (the controller calls under its own lock, possibly on
 * `Dispatchers.Default`): nothing here blocks the caller or calls back into it. The Media3 work is posted to the main thread,
 * in call order. What a caller reads straight after a call is already true when the call returns: [load] publishes a LOADING
 * snapshot (not playing, no error, nothing of the previous video) at once, and [setMuted], [setAudioOnly] and [setSpeed] show in
 * [snapshot] at once; until the main thread has applied a load, the previous video's state (its end, its error) is never
 * published as this one's.
 *
 * **Media3.** It connects a `MediaController` to [VideoPlaybackService] (`SessionToken(context, ComponentName(context,
 * VideoPlaybackService))`, `buildAsync`) when it is first asked to do something, and queues the commands until it is connected.
 * What the controller does not carry comes from the service's player in the same process ([VideoPlayerHolder]): the bandwidth
 * estimate, the dropped frames (live decoder counters), the decoder's name and a playback error with its cause chain
 * (googlevideo's 403 becomes [PlayerErrorKind.SOURCE_EXPIRED]).
 *
 * - [load]: sets [VideoRequestHeaders.current] (the stream set's headers), builds the item (the video id; the title, the channel as
 *   artist, the thumbnail as artwork; DASH as a base64 `data:` address with `MimeTypes.APPLICATION_MPD`; progressive streams and
 *   downloaded files as [VideoMediaSourceFactory]'s pair scheme; HLS as it is; the subtitles as `SubtitleConfiguration`s with
 *   their type and language, a downloaded subtitle as a `file:` address), then `setMediaItem(item, startMs)` and `prepare()`;
 *   it never starts playback by itself (the previous video's play state is cleared).
 * - [setAudioOnly] disables the video track and keeps the source (decision W4); [setMuted] sets the volume to 0 and back;
 *   [selectSubtitle] prefers a text language, or disables text for null; [unload] stops, clears and disconnects.
 * - [snapshot] follows the controller's events, and once a second while playing (the buffer, the bandwidth, the dropped
 *   frames): never per frame. [positionMs] is `controller.currentPosition` on the main thread (cheap, no allocation); from
 *   another thread it is worked out from the last known position, the time since and the speed.
 * - [Surface] is `ContentFrame` from media3-ui-compose on a `SurfaceView`, with an `AndroidView` over a plain [SurfaceView] kept
 *   ready as the fallback ([surfaceKind]).
 */
@OptIn(UnstableApi::class)
class AndroidVideoEngine(context: Context) : VideoPlayerEngine, VideoSurfaceProvider {

    /** How [Surface] draws the picture: media3-ui-compose's `ContentFrame` (the default), or an `AndroidView` over a `SurfaceView`. */
    enum class SurfaceKind { CONTENT_FRAME, SURFACE_VIEW }

    private val appContext = context.applicationContext
    private val mainLooper = Looper.getMainLooper()
    private val main = Handler(mainLooper)

    private val snapshotValues = MutableStateFlow(PlayerSnapshot.Idle)
    private val cueFlow = MutableStateFlow<List<SubtitleCue>>(emptyList())

    override val snapshot: StateFlow<PlayerSnapshot> = snapshotValues.asStateFlow()
    override val cues: StateFlow<List<SubtitleCue>> = cueFlow.asStateFlow()

    /** What this phone decodes (spec 6.2): [AndroidCapabilities.of]; the decoder list is asked once, ahead of time (see `init`). */
    override val capabilities: EngineCapabilities
        get() = AndroidCapabilities.of(appContext)

    // ---- what the engine was asked (any thread writes, the main thread applies; applied again to a new connection) ----------

    @Volatile private var volume = 1f
    @Volatile private var muted = false
    @Volatile private var speed = 1f
    @Volatile private var audioOnly = false
    @Volatile private var subtitleLanguage: String? = null
    @Volatile private var released = false

    /** Loads and unloads asked; the main thread counts the ones it has applied. While they differ, nothing old is published. */
    private val requestedLoads = AtomicInteger()

    // ---- main-thread state ----------------------------------------------------------------------------------------------------

    private var appliedLoads = 0
    private var controller: MediaController? = null
    private var connecting: ListenableFuture<MediaController>? = null

    /** The connected controller, for the picture: composition reads it, so the surface attaches once the controller is there. */
    private var surfacePlayer by mutableStateOf<MediaController?>(null)

    private val pending = ArrayDeque<(MediaController) -> Unit>()
    private var attached: ExoPlayer? = null
    private var current: PlaybackRequest? = null

    /** A failure of the engine itself (no service, nothing to play), shown until the next load. */
    private var engineError: PlayerError? = null

    /** The player's error when the last load was applied: the previous item's, ignored until the player lets go of it. */
    private var staleError: PlaybackException? = null

    private var seenReady = false
    private var videoDecoder: String? = null
    private var ticking = false

    /** The meter whose estimate is reported, and its first 5 s (spec 6.4: the bandwidth counts after them; Y-5 relies on null). */
    private var measuredMeter: DefaultBandwidthMeter? = null
    private val bandwidthWarmUp = BandwidthWarmUp(clock = SystemClock::elapsedRealtime)

    // Dropped frames since the load, from the video decoder's live counters (one counters object per decoder session).
    private var droppedBefore = 0
    private var countedCounters: DecoderCounters? = null
    private var countedFrom = 0

    /** The last position seen on the main thread, for [positionMs] from other threads; replaced on events, ticks and seeks. */
    private class Anchor(val positionMs: Long, val atRealtimeMs: Long, val advancing: Boolean, val speed: Float, val durationMs: Long)

    @Volatile
    private var anchor = Anchor(0, 0, advancing = false, speed = 1f, durationMs = 0)

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish()

        override fun onCues(cueGroup: CueGroup) = publishCues(cueGroup)
    }

    private val controllerListener = object : MediaController.Listener {
        override fun onDisconnected(controller: MediaController) = lost(controller)
    }

    private val analytics = object : AnalyticsListener {
        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) = publish()

        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            videoDecoder = decoderName
            publish()
        }

        override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) = publish()
    }

    private val tick = Runnable {
        ticking = false
        publish()
    }

    init {
        // The decoder list takes a moment the first time; asked here, off the main thread, so the controller's first look at
        // [capabilities] (under its lock) finds it ready.
        Thread({ runCatching { AndroidCapabilities.of(appContext) } }, "video-decoder-caps").apply { isDaemon = true }.start()
    }

    // ---- VideoPlayerEngine ----------------------------------------------------------------------------------------------------

    override fun positionMs(): Long {
        if (Looper.myLooper() === mainLooper) {
            val c = controller
            return if (c != null && c.isConnected && !loadPending()) c.currentPosition else anchor.positionMs
        }
        val a = anchor
        if (!a.advancing) return a.positionMs
        val moved = a.positionMs + ((SystemClock.elapsedRealtime() - a.atRealtimeMs) * a.speed).toLong()
        return if (a.durationMs > 0) moved.coerceAtMost(a.durationMs) else moved
    }

    override fun load(request: PlaybackRequest) {
        if (released) return
        val start = request.startMs.coerceAtLeast(0)
        val item = try {
            mediaItemOf(request)
        } catch (e: IllegalArgumentException) {
            // Nothing to play (a local request without a file): the previous video stops and the error shows at once.
            val error = PlayerError(PlayerErrorKind.UNSUPPORTED, e.message ?: "nothing to play")
            requestedLoads.incrementAndGet()
            snapshotValues.update { it.copy(status = PlayerStatus.ERROR, playing = false, durationMs = 0, bufferedMs = 0, error = error) }
            cueFlow.value = emptyList()
            onMain {
                appliedLoads++
                if (released) return@onMain
                current = null
                controller?.takeIf { it.isConnected }?.let { c ->
                    c.stop()
                    c.clearMediaItems()
                }
                fail(error)
            }
            return
        }
        VideoRequestHeaders.current = VideoRequestHeaders.StreamSetRequests(request.headers, rangeRules = request.input !is MediaInput.Hls)
        // True as soon as this returns: loading, not playing, no error, nothing of the previous video.
        requestedLoads.incrementAndGet()
        anchor = Anchor(start, SystemClock.elapsedRealtime(), advancing = false, speed = speed, durationMs = 0)
        snapshotValues.update {
            it.copy(
                status = PlayerStatus.LOADING, playing = false, durationMs = 0, bufferedMs = 0, videoWidth = 0, videoHeight = 0,
                error = null, droppedFrames = 0,
            )
        }
        cueFlow.value = emptyList()
        onMain {
            appliedLoads++
            if (released) return@onMain
            current = request
            engineError = null
            staleError = attached.let { holderPlayer -> if (holderPlayer != null) holderPlayer.playerError else controller?.playerError }
            seenReady = false
            videoDecoder = null
            droppedBefore = 0
            countedCounters = attached?.videoDecoderCounters
            countedFrom = countedCounters?.droppedBufferCount ?: 0
            command { c ->
                // load() never starts playback: the previous video's play state does not carry over
                c.pause()
                c.setMediaItem(item, start)
                c.prepare()
            }
            publish()
        }
    }

    override fun play() = command { it.play() }

    override fun pause() = command { it.pause() }

    override fun seekTo(ms: Long) {
        val target = ms.coerceAtLeast(0)
        val a = anchor
        anchor = Anchor(target, SystemClock.elapsedRealtime(), a.advancing, a.speed, a.durationMs)
        command { it.seekTo(target) }
    }

    override fun setSpeed(speed: Float) {
        val wanted = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        this.speed = wanted
        snapshotValues.update { it.copy(speed = wanted) }
        command { it.setPlaybackSpeed(wanted) }
    }

    override fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
        command { it.volume = effectiveVolume() }
    }

    override fun setMuted(muted: Boolean) {
        this.muted = muted
        snapshotValues.update { it.copy(muted = muted) }
        command { it.volume = effectiveVolume() }
    }

    override fun setAudioOnly(audioOnly: Boolean) {
        this.audioOnly = audioOnly
        snapshotValues.update { it.copy(audioOnly = audioOnly) }
        command { c ->
            c.trackSelectionParameters = c.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, audioOnly)
                .build()
        }
    }

    override fun selectSubtitle(language: String?) {
        subtitleLanguage = language
        command { c -> c.trackSelectionParameters = withText(c.trackSelectionParameters.buildUpon()).build() }
    }

    /**
     * Stops, clears the media item and lets go of the service (the controller disconnects; the service, now idle with nothing to
     * show, removes its notification and stops), so nothing lingers after the controller's `stop()` or the end of its queue.
     * The engine stays usable: the next [load] connects again.
     */
    override fun unload() {
        if (released) return
        requestedLoads.incrementAndGet()
        anchor = Anchor(0, SystemClock.elapsedRealtime(), advancing = false, speed = speed, durationMs = 0)
        snapshotValues.value = PlayerSnapshot.Idle.copy(speed = speed, audioOnly = audioOnly, muted = muted)
        cueFlow.value = emptyList()
        onMain {
            appliedLoads++
            if (released) return@onMain
            current = null
            engineError = null
            staleError = null
            pending.clear()
            stopTicker()
            disconnect()
            publish()
        }
    }

    override fun release() {
        released = true
        onMain {
            pending.clear()
            stopTicker()
            disconnect()
            current = null
            snapshotValues.value = PlayerSnapshot.Idle
            cueFlow.value = emptyList()
        }
    }

    // ---- VideoSurfaceProvider -------------------------------------------------------------------------------------------------

    @Composable
    override fun Surface(modifier: Modifier, scale: VideoScale) {
        val player = surfacePlayer
        when (surfaceKind) {
            SurfaceKind.CONTENT_FRAME -> ContentFrame(
                player = player,
                modifier = modifier,
                surfaceType = SURFACE_TYPE_SURFACE_VIEW,
                contentScale = if (scale == VideoScale.FIT) ContentScale.Fit else ContentScale.Crop,
            )
            SurfaceKind.SURFACE_VIEW -> ViewSurface(player, modifier, scale)
        }
    }

    /**
     * The fallback picture: an `AndroidView` over a plain [SurfaceView] attached to the player, sized to the video's shape inside
     * a black box (fit, or fill and crop). Kept for the case media3-ui-compose (built against Compose 1.11, run on the app's 1.10)
     * misbehaves on a device; the black is the player's own, behind the picture.
     */
    @Composable
    private fun ViewSurface(player: Player?, modifier: Modifier, scale: VideoScale) {
        val videoSize by remember {
            snapshotValues.map { IntSize(it.videoWidth, it.videoHeight) }.distinctUntilChanged()
        }.collectAsState(IntSize.Zero)
        var view by remember { mutableStateOf<SurfaceView?>(null) }
        Box(modifier.clipToBounds().background(Color.Black)) {
            AndroidView(
                factory = { SurfaceView(it).also { created -> view = created } },
                modifier = Modifier.videoFrame(videoSize, scale),
            )
        }
        val surface = view
        DisposableEffect(player, surface) {
            if (player != null && surface != null) player.setVideoSurfaceView(surface)
            onDispose { if (player != null && surface != null) player.clearVideoSurfaceView(surface) }
        }
    }

    // ---- connection ---------------------------------------------------------------------------------------------------------------

    /** Runs [block] with the connected controller on the main thread, now or once connected; never waits. */
    private fun command(block: (MediaController) -> Unit) {
        if (Looper.myLooper() !== mainLooper) {
            main.post { command(block) }
            return
        }
        if (released) return
        val c = controller
        if (c != null && c.isConnected) {
            block(c)
        } else {
            pending.addLast(block)
            connect()
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() === mainLooper) block() else main.post(block)
    }

    private fun loadPending(): Boolean = requestedLoads.get() != appliedLoads

    @MainThread
    private fun connect() {
        if (connecting != null || released) return
        val token = SessionToken(appContext, ComponentName(appContext, VideoPlaybackService::class.java))
        val future = MediaController.Builder(appContext, token)
            .setApplicationLooper(mainLooper)
            .setListener(controllerListener)
            .buildAsync()
        connecting = future
        future.addListener({ connected(future) }, ContextCompat.getMainExecutor(appContext))
    }

    @MainThread
    private fun connected(future: ListenableFuture<MediaController>) {
        if (connecting !== future) return
        connecting = null
        val c = try {
            future.get()
        } catch (e: Exception) {
            if (!released) {
                pending.clear()
                fail(PlayerError(PlayerErrorKind.OTHER, "the player service did not answer: ${e.message}"))
            }
            return
        }
        if (released) {
            c.release()
            return
        }
        controller = c
        surfacePlayer = c
        c.addListener(playerListener)
        attachHolder()
        applyState(c)
        while (pending.isNotEmpty()) pending.removeFirst()(c)
        publish()
    }

    @MainThread
    private fun lost(lostController: MediaController) {
        if (controller !== lostController) return
        lostController.removeListener(playerListener)
        detachHolder()
        controller = null
        surfacePlayer = null
        stopTicker()
        // What was loaded went with the service: say so, so the controller offers "Try again" (a new load connects again).
        if (current != null) fail(PlayerError(PlayerErrorKind.OTHER, "the player service stopped")) else publish()
    }

    /** Stops what plays, clears it and lets go of the controller (and so of the service). */
    @MainThread
    private fun disconnect() {
        detachHolder()
        connecting?.let { MediaController.releaseFuture(it) }
        connecting = null
        controller?.let { c ->
            c.removeListener(playerListener)
            if (c.isConnected) {
                c.stop()
                c.clearMediaItems()
            }
            c.release()
        }
        controller = null
        surfacePlayer = null
    }

    /** What the engine was asked before this connection: the volume, the speed, audio-only and the subtitles. */
    @MainThread
    private fun applyState(c: MediaController) {
        c.volume = effectiveVolume()
        c.setPlaybackSpeed(speed)
        c.trackSelectionParameters = withText(
            c.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, audioOnly),
        ).build()
    }

    private fun effectiveVolume(): Float = if (muted) 0f else volume

    /** Text off for no language; else the language preferred, and among its tracks the ones written by people (role subtitle). */
    private fun withText(builder: TrackSelectionParameters.Builder): TrackSelectionParameters.Builder {
        val language = subtitleLanguage ?: return builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        return builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setPreferredTextLanguage(language)
            .setPreferredTextRoleFlags(C.ROLE_FLAG_SUBTITLE)
    }

    @MainThread
    private fun attachHolder() {
        val player = VideoPlayerHolder.player
        if (attached === player) return
        detachHolder()
        player?.addAnalyticsListener(analytics)
        attached = player
    }

    @MainThread
    private fun detachHolder() {
        attached?.removeAnalyticsListener(analytics)
        attached = null
        countedCounters = null
    }

    // ---- the snapshot -----------------------------------------------------------------------------------------------------------

    @MainThread
    private fun publish() {
        // A load or unload asked but not applied yet: what the player says is still about the previous video.
        if (released || loadPending()) return
        val c = controller?.takeIf { it.isConnected }
        if (c != null) attachHolder()
        // The service's own player when it is there: its error has the whole cause chain (a controller's copy may not) and is
        // cleared at once by a prepare. The error of the previous load is not this load's: it stays until the new item is prepared.
        val holderPlayer = attached
        val playerError = if (holderPlayer != null) holderPlayer.playerError else c?.playerError
        if (playerError == null) staleError = null
        val failure = playerError?.takeUnless { it === staleError }
        val error = engineError ?: failure?.let(::errorOf)
        val state = c?.playbackState ?: Player.STATE_IDLE
        if (state == Player.STATE_READY) seenReady = true
        val status = when {
            error != null -> PlayerStatus.ERROR
            state == Player.STATE_READY -> PlayerStatus.READY
            state == Player.STATE_BUFFERING -> if (seenReady) PlayerStatus.BUFFERING else PlayerStatus.LOADING
            state == Player.STATE_ENDED -> PlayerStatus.ENDED
            current != null -> PlayerStatus.LOADING
            else -> PlayerStatus.IDLE
        }
        val live = current?.live == true || c?.isCurrentMediaItemLive == true
        val durationMs = if (c == null || live || c.duration == C.TIME_UNSET) 0L else c.duration.coerceAtLeast(0)
        val playing = c != null && error == null && c.playWhenReady && state != Player.STATE_ENDED && state != Player.STATE_IDLE
        val nowSpeed = c?.playbackParameters?.speed ?: speed
        // A new meter (the service built a new player) starts from a table of typical speeds: it counts after 5 s (spec 6.4).
        val meter = VideoPlayerHolder.bandwidthMeter
        if (meter !== measuredMeter) {
            measuredMeter = meter
            bandwidthWarmUp.restart()
        }
        val bitrate = meter?.bitrateEstimate ?: 0L
        val size = c?.videoSize
        snapshotValues.value = PlayerSnapshot(
            status = status,
            playing = playing,
            durationMs = durationMs,
            bufferedMs = c?.bufferedPosition?.coerceAtLeast(0) ?: 0L,
            speed = nowSpeed,
            videoWidth = size?.width ?: 0,
            videoHeight = size?.height ?: 0,
            audioOnly = audioOnly,
            muted = muted,
            error = error,
            bandwidthKbps = bandwidthWarmUp.gate(if (bitrate > 0) (bitrate / 1000).toInt() else null),
            decoderName = videoDecoder,
            droppedFrames = droppedSinceLoad(holderPlayer),
        )
        anchor = Anchor(
            positionMs = c?.currentPosition ?: anchor.positionMs,
            atRealtimeMs = SystemClock.elapsedRealtime(),
            advancing = playing && status == PlayerStatus.READY,
            speed = nowSpeed,
            durationMs = durationMs,
        )
        if (playing) startTicker() else stopTicker()
    }

    /**
     * The frames the video decoder dropped since the load, from its live counters (not the renderer's reports, which come in
     * batches of 50): one counters object per decoder session, so a session that ends (audio-only, a new decoder) is added up.
     */
    @MainThread
    private fun droppedSinceLoad(player: ExoPlayer?): Int {
        val counters = player?.videoDecoderCounters
        if (counters !== countedCounters) {
            countedCounters?.let { ended ->
                ended.ensureUpdated()
                droppedBefore += (ended.droppedBufferCount - countedFrom).coerceAtLeast(0)
            }
            countedCounters = counters
            countedFrom = 0
        }
        if (counters == null) return droppedBefore
        counters.ensureUpdated()
        return droppedBefore + (counters.droppedBufferCount - countedFrom).coerceAtLeast(0)
    }

    @MainThread
    private fun fail(error: PlayerError) {
        engineError = error
        if (loadPending()) return
        publish()
    }

    @MainThread
    private fun publishCues(group: CueGroup) {
        if (loadPending()) return
        val startMs = if (group.presentationTimeUs == C.TIME_UNSET) positionMs() else group.presentationTimeUs / 1000
        // A cue shows until the next cue list replaces it, so it has no end of its own here.
        cueFlow.value = group.cues.mapNotNull { cue ->
            cue.text?.toString()?.takeIf { it.isNotBlank() }?.let { SubtitleCue(it, startMs, Long.MAX_VALUE) }
        }
    }

    private fun startTicker() {
        if (ticking) return
        ticking = true
        main.postDelayed(tick, TICK_MS)
    }

    private fun stopTicker() {
        if (!ticking) return
        ticking = false
        main.removeCallbacks(tick)
    }

    companion object {
        /** The prefix of a side-loaded DASH manifest's address (decision W3), read by `DataSchemeDataSource`. */
        const val DASH_DATA_PREFIX = "data:application/dash+xml;base64,"

        /** How often the snapshot is refreshed while playing (the buffer, the bandwidth, the drops): once a second, never per frame. */
        private const val TICK_MS = 1_000L

        /** The speeds Media3's audio sink takes (it clamps to 0.1 to 8); the chrome offers 0.25 to 4 (spec 6.5). */
        private const val MIN_SPEED = 0.1f
        private const val MAX_SPEED = 8f

        /**
         * How [Surface] draws: `ContentFrame` unless switched to the `SurfaceView` fallback (if media3-ui-compose misbehaves on the
         * app's Compose; the controller checks it on the emulator in Y-29). Read when the picture is composed.
         */
        @Volatile
        var surfaceKind: SurfaceKind = SurfaceKind.CONTENT_FRAME

        /** The item [request] plays: id, metadata, the input as an address, the subtitles. Throws for an input with nothing to play. */
        internal fun mediaItemOf(request: PlaybackRequest): MediaItem {
            val metadata = MediaMetadata.Builder()
                .setTitle(request.title)
                .setArtist(request.channel)
                .setArtworkUri(request.thumbnailUrl?.let(Uri::parse))
                .setMediaType(MediaMetadata.MEDIA_TYPE_VIDEO)
                .setIsPlayable(true)
                .build()
            val builder = MediaItem.Builder().setMediaId(request.videoId).setMediaMetadata(metadata)
            when (val input = request.input) {
                is MediaInput.Dash -> builder
                    .setUri(DASH_DATA_PREFIX + Base64.encodeToString(input.manifest.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
                    .setMimeType(MimeTypes.APPLICATION_MPD)
                is MediaInput.Hls -> builder.setUri(input.url).setMimeType(MimeTypes.APPLICATION_M3U8)
                is MediaInput.Progressive -> builder.setUri(VideoMediaSourceFactory.pairUri(input.video?.url, input.audio?.url))
                is MediaInput.LocalFiles -> builder.setUri(
                    input.muxedPath?.let { muxed -> VideoMediaSourceFactory.pairUri(localAddress(muxed), null) }
                        ?: VideoMediaSourceFactory.pairUri(input.videoPath?.let(::localAddress), input.audioPath?.let(::localAddress)),
                )
            }
            // An online video brings its tracks, a download its files; both are side-loaded, and selectSubtitle finds either by
            // language (the controller asks for the one that matches the caption setting).
            builder.setSubtitleConfigurations(request.subtitles.map(::subtitleOf) + request.localSubtitles.map(::localSubtitleOf))
            return builder.build()
        }

        /** A downloaded file as an address: a path becomes `file:`, an address (`file:/`, `content:`, `https:/`) stays as it is. */
        private fun localAddress(path: String): String =
            if (path.contains(":/") || path.startsWith("content:")) path else Uri.fromFile(File(path)).toString()

        /** A side-loaded subtitle: its type and language; people's tracks carry the role subtitle, machine ones none. */
        private fun subtitleOf(track: SubtitleTrack): MediaItem.SubtitleConfiguration =
            subtitleConfiguration(track.url, track.mimeType, track.language, track.name, track.autoGenerated)

        /**
         * A subtitle file saved beside a download ([LocalSubtitle]: its path, language, label and whether a machine made it) as a
         * side-loaded subtitle with a `file:` address, for offline playback; its type from the file name (WebVTT unless it says
         * otherwise). A `SubtitleTrack` whose `url` is a plain path is read the same way.
         */
        internal fun localSubtitleOf(subtitle: LocalSubtitle): MediaItem.SubtitleConfiguration =
            subtitleConfiguration(subtitle.path, mimeType = "", subtitle.language, subtitle.label, subtitle.autoGenerated)

        private fun subtitleConfiguration(
            address: String,
            mimeType: String,
            language: String,
            label: String,
            autoGenerated: Boolean,
        ): MediaItem.SubtitleConfiguration {
            val uri = localAddress(address)
            return MediaItem.SubtitleConfiguration.Builder(Uri.parse(uri))
                .setMimeType(mimeType.ifBlank { subtitleTypeOf(uri) })
                .setLanguage(language)
                .setLabel(label)
                .setId(if (autoGenerated) "$language:auto" else language)
                .setRoleFlags(if (autoGenerated) 0 else C.ROLE_FLAG_SUBTITLE)
                .build()
        }

        /** A subtitle's type from its file name when none was given: SubRip, TTML, else WebVTT (what the source and the downloads write). */
        private fun subtitleTypeOf(address: String): String {
            val name = address.substringBefore('?').lowercase()
            return when {
                name.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
                name.endsWith(".ttml") || name.endsWith(".dfxp") || name.endsWith(".xml") -> MimeTypes.APPLICATION_TTML
                else -> MimeTypes.TEXT_VTT
            }
        }

        /** A playback failure as the controller needs it: googlevideo's 403 is [PlayerErrorKind.SOURCE_EXPIRED]. */
        internal fun errorOf(error: PlaybackException): PlayerError =
            PlayerError(kindOf(error), listOfNotNull(error.errorCodeName, error.message).joinToString(": "))

        /**
         * [PlayerErrorKind.SOURCE_EXPIRED] when the cause chain holds a 403 response (the controller's copy of an error may carry
         * its cause as text only, hence the message check for a bad HTTP status); then by Media3's numbering of error codes:
         * an unsupported format is [PlayerErrorKind.UNSUPPORTED], the other 2xxx (input and output) are
         * [PlayerErrorKind.NETWORK], the other 4xxx (decoding) are [PlayerErrorKind.DECODER], anything else
         * [PlayerErrorKind.OTHER].
         */
        internal fun kindOf(error: PlaybackException): PlayerErrorKind {
            var cause: Throwable? = error
            var depth = 0
            while (cause != null && depth++ < MAX_CAUSES) {
                if (cause is HttpDataSource.InvalidResponseCodeException && cause.responseCode == 403) return PlayerErrorKind.SOURCE_EXPIRED
                if (error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS && cause.message?.contains("Response code: 403") == true) {
                    return PlayerErrorKind.SOURCE_EXPIRED
                }
                cause = cause.cause
            }
            return when (error.errorCode) {
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
                -> PlayerErrorKind.UNSUPPORTED
                else -> when (error.errorCode / 1000) {
                    2 -> PlayerErrorKind.NETWORK
                    4 -> PlayerErrorKind.DECODER
                    else -> PlayerErrorKind.OTHER
                }
            }
        }

        private const val MAX_CAUSES = 16
    }
}

/** Sizes the picture to the video's shape inside its box: [VideoScale.FIT] shows all of it, [VideoScale.FILL] covers and crops. */
private fun Modifier.videoFrame(video: IntSize, scale: VideoScale): Modifier = layout { measurable, constraints ->
    if (video.width <= 0 || video.height <= 0 || !constraints.hasBoundedWidth || !constraints.hasBoundedHeight) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val boxWidth = constraints.maxWidth
    val boxHeight = constraints.maxHeight
    val videoRatio = video.width.toFloat() / video.height
    val boxRatio = boxWidth.toFloat() / boxHeight.coerceAtLeast(1)
    val byWidth = (videoRatio > boxRatio) == (scale == VideoScale.FIT)
    val width = if (byWidth) boxWidth else (boxHeight * videoRatio).roundToInt()
    val height = if (byWidth) (boxWidth / videoRatio).roundToInt() else boxHeight
    val placeable = measurable.measure(Constraints.fixed(width.coerceAtLeast(0), height.coerceAtLeast(0)))
    layout(boxWidth, boxHeight) { placeable.place((boxWidth - width) / 2, (boxHeight - height) / 2) }
}
