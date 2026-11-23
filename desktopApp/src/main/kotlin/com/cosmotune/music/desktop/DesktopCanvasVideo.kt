package com.cosmotune.music.desktop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.cosmotune.music.data.canvas.CanvasArtwork
import com.opencanvas.core.sync.CanvasSyncDefaults
import com.opencanvas.core.sync.CanvasSyncPolicy
import com.opencanvas.core.sync.CanvasStallWatch
import com.opencanvas.core.sync.CanvasStreamChoice
import kotlinx.coroutines.async
import com.cosmotune.music.data.canvas.CanvasSource
import com.cosmotune.music.ui.player.CanvasContentMode
import com.cosmotune.music.ui.player.CanvasVideoSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Frame
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import kotlin.math.roundToInt

/**
 * The desktop's decoder behind the shared player's Canvas clip.
 *
 * The phone plays the clip in a silent ExoPlayer on a TextureView; here FFmpeg
 * decodes it into frames that Compose draws. Everything the shared player asks
 * of the clip is answered the same way as on the phone: nothing until a first
 * frame, then a 320ms fade reported through `onCoverChanged`, the aspect once
 * known, frames for the backdrop to re-tint off, the portrait fit, the bottom
 * dissolve, and a pause that keeps the last frame on screen.
 *
 * Frames are decoded at the size they are drawn, straight into one of two reused bitmaps ([CanvasFrameRing]), so a frame costs
 * no allocation at all; and nothing is decoded while the video cannot be seen (window minimised or in the tray, the video out
 * of the window or faded out), the synced video seeking back to the song when it is seen again.
 */
@Composable
internal fun DesktopCanvasVideo(spec: CanvasVideoSpec, modifier: Modifier) {
    val canvas = spec.canvas
    val automaticFraming = com.opencanvas.core.OpenCanvas.SUBJECT_FRAMING && canvas.videoId != null && spec.sync != null
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var sourceAspect by remember(canvas) { mutableFloatStateOf(0f) }
    var frameTime by remember(canvas) { mutableStateOf(0.0) }
    var analysis by remember(canvas) { mutableStateOf<com.opencanvas.core.reframer.FrameReframingWorker?>(null) }
    LaunchedEffect(canvas.videoId, sourceAspect, viewport) {
        if (!automaticFraming || sourceAspect <= 0 || viewport.width <= 0 || viewport.height <= 0) return@LaunchedEffect
        delay(160) // Coalesce animated layout changes before creating detector sessions.
        val worker = com.opencanvas.core.OpenCanvas.observeFrames(this, canvas.videoId!!, sourceAspect,
            viewport.width.toFloat() / viewport.height)
        analysis = worker
        try { kotlinx.coroutines.awaitCancellation() } finally { analysis = null; worker.close() }
    }
    val liveFrame = analysis?.framing?.collectAsState()?.value ?: com.opencanvas.core.filter.FramingDecision()
    val recorded = canvas.trajectory?.frameAt(frameTime)
    val framing = if (!automaticFraming) com.opencanvas.core.filter.FramingDecision(fitFullFrame = false)
        else recorded?.let { com.opencanvas.core.filter.FramingDecision(it.cx, it.cy, it.fitFullFrame, it.isSceneCut) } ?: liveFrame
    val focalX by animateFloatAsState(framing.cx, tween(if (framing.sceneCut) 0 else 180), label = "canvasSubjectX")
    val focalY by animateFloatAsState(framing.cy, tween(if (framing.sceneCut) 0 else 180), label = "canvasSubjectY")
    // The frame on screen: one of [frames]' two bitmaps. Read only in draw, so a new frame repaints the clip and recomposes
    // nothing; [rendered] changes only when there comes to be a frame or stops being one.
    var frame by remember(canvas) { mutableStateOf<ImageBitmap?>(null) }
    val frames = remember(canvas) { CanvasFrameRing() }
    // Freed once its decoder lets go of it, whenever that is.
    DisposableEffect(frames) { onDispose { frames.close() } }
    val rendered by remember(canvas) { derivedStateOf { frame != null } }
    val running by rememberUpdatedState(spec.isPlaying && !spec.pausedForTransition)
    val latestSync by rememberUpdatedState(spec.sync)
    val reportAspect by rememberUpdatedState(spec.onAspectRatioChanged)
    val reportRendered by rememberUpdatedState(spec.onRenderedChanged)
    val reportFrame by rememberUpdatedState(spec.onFrameCaptured)
    val reportCover by rememberUpdatedState(spec.onCoverChanged)
    val presentationAlpha by rememberUpdatedState(spec.presentationAlpha)
    val captureEdge by rememberUpdatedState(spec.frameCapturePx)

    // What the video is decoded at: the size it is drawn at, settled for a moment so an animated resize does not rebuild the
    // scaler and the bitmaps on every step of it.
    var decodeSize by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(viewport) {
        if (viewport.width <= 0 || viewport.height <= 0) return@LaunchedEffect
        if (decodeSize != IntSize.Zero) delay(RESIZE_SETTLE_MS)
        decodeSize = viewport
    }
    val windowShown by rememberWindowShown()
    var onScreen by remember { mutableStateOf(true) }
    val seen: () -> Boolean = {
        CanvasVideoGate.shouldDecode(windowShown, onScreen, frame != null, presentationAlpha())
    }

    /** Shows the frame just decoded. */
    fun present() {
        frames.present()?.let { frame = it }
    }

    /** The cover shows instead of the video. */
    fun hide() {
        if (frame == null) return
        frame = null
        frames.hidden()
    }

    LaunchedEffect(canvas) {
        // A music video that follows the song is driven by the effect below instead.
        if (spec.sync != null) return@LaunchedEffect
        // Round and round for as long as the clip is mounted: a canvas is a few
        // seconds long, and the decoder cannot be relied on to rewind an HLS
        // manifest itself, so each pass reopens it.
        while (isActive) {
            val playback = DesktopCanvasPlayback(decorative = true)
            val decoder = playback.decoder
            var shown = 0
            try {
                decoder.showAt(decodeSize.width, decodeSize.height)
                val attempt = playback.open(listOfNotNull(canvas.url, canvas.fallbackUrl), canvas.headers)
                if (attempt.result.isFailure) {
                    DesktopTrackLog.log("canvas: ${attempt.result.exceptionOrNull()?.message}")
                    return@LaunchedEffect
                }
                reportAspect(decoder.sourceAspect)
                sourceAspect = decoder.sourceAspect
                while (isActive) {
                    // Paused with the track, or for the length of the sleeve's
                    // collapse, or out of sight: the last frame stays up, nothing is decoded.
                    snapshotFlow { running && seen() }.first { it }
                    val started = System.nanoTime()
                    decoder.showAt(decodeSize.width, decodeSize.height)
                    if (!frames.ensure(decoder.width, decoder.height)) {
                        CanvasFrameFailures.noteNoMemory()
                        delay(NO_MEMORY_RETRY_MS)
                        continue
                    }
                    val decoded = withContext(Dispatchers.IO) { frames.write { decoder.nextFrame(it) } } ?: false
                    if (!decoded) break
                    delay((decoder.artworkDelayMillis - (System.nanoTime() - started) / 1_000_000).coerceAtLeast(0L))
                    shown++
                    present()
                }
            } finally {
                playback.close()
            }
            // A pass that drew nothing would spin: reopening cannot fix a clip
            // with no frames in it.
            if (shown == 0) {
                DesktopTrackLog.log("canvas: the clip decoded no frames; not looping it")
                return@LaunchedEffect
            }
        }
    }

    // A music video that follows the song: the picture is held at the song's position plus the
    // video's own offset, so it jumps when the song does and never loops. Frames are paced to the
    // song's clock rather than to the decoder's, so a slow frame is dropped, not carried as drift.
    //
    // A stream can stop while the song plays on (its signed address runs out, a range is refused with HTTP 403, the
    // connection stalls): a watch beside the decoding notices when no frame has come for a moment while one is due, says why,
    // and the video is opened again at the place the map gives for the song, with a fresh address. Until it comes back the
    // cover shows (the picture fades out) rather than a frozen frame.
    LaunchedEffect(canvas) {
        val sync = spec.sync ?: return@LaunchedEffect
        var policy = CanvasSyncPolicy(sync.map, sync.durationMs)
        var playback = DesktopCanvasPlayback()
        val startedAt = System.nanoTime()
        val watch = CanvasStallWatch()
        // Set by the watch, read by the decoding: the stream is to be opened again.
        var reopenWanted = false
        var stallReason = ""
        // Only for the subject framing, which samples frames as bytes; reused, and made only when it is on.
        var analysisPixels: ByteArray? = null
        // A taller stream being opened beside the one playing, when the box needs more than it has (see CanvasStreamChoice).
        var upgrade: kotlinx.coroutines.Deferred<Pair<DesktopCanvasPlayback, String>?>? = null
        val watching = launch {
            while (isActive) {
                delay(CanvasSyncDefaults.CHECK_INTERVAL_MS)
                val now = System.currentTimeMillis()
                if (!reopenWanted && watch.stalled(now)) {
                    val songMs = sync.songMs()
                    stallReason = CanvasStallWatch.reason(playback.streamFailure?.message, playback.isStreaming,
                        canvas.url.takeIf { playback.isStreaming }?.let { com.opencanvas.core.stream.isMediaStreamExpired(it) } == true)
                    DesktopTrackLog.log("canvas: video stalled at video ${playback.decoder.lastPtsMs} ms, song $songMs ms: " +
                        "no new frame for ${watch.sinceLastFrameMs(now) ?: 0} ms ($stallReason); opening it again")
                    reopenWanted = true
                    // A decode waiting on the network returns now; the cover shows until a frame comes again.
                    playback.abortStream()
                    hide()
                }
            }
        }
        try {
            playback.decoder.showAt(decodeSize.width, decodeSize.height)
            val attempt = playback.open(listOfNotNull(canvas.url, canvas.fallbackUrl), canvas.headers)
            if (attempt.result.isFailure) {
                DesktopTrackLog.log("canvas: ${attempt.result.exceptionOrNull()?.message}")
                return@LaunchedEffect
            }
            reportAspect(playback.decoder.sourceAspect)
            sourceAspect = playback.decoder.sourceAspect
            var how = attempt.how

            /** The next frame, into the bitmap not on screen, at the size the video is shown; false when there is none. */
            suspend fun decodeNext(): Boolean {
                val decoder = playback.decoder
                decoder.showAt(decodeSize.width, decodeSize.height)
                if (!frames.ensure(decoder.width, decoder.height)) {
                    CanvasFrameFailures.noteNoMemory()
                    return false
                }
                val worker = analysis
                return withContext(Dispatchers.IO) {
                    frames.write { target ->
                        decoder.nextFrame(target, loop = false).also { decoded ->
                            if (decoded && worker != null) {
                                val bytes = analysisPixels?.takeIf { it.size == target.width * target.height * 4 }
                                    ?: ByteArray(target.width * target.height * 4).also { analysisPixels = it }
                                if (target.copyTo(bytes)) {
                                    worker.submitBgra(bytes, target.width, target.height, decoder.lastPtsMs / 1000.0)
                                }
                            }
                        }
                    }
                } ?: false
            }
            fun showDecoded() {
                // Only a recorded framing path follows the frame's time; nothing else recomposes per frame.
                if (canvas.trajectory != null) frameTime = playback.decoder.lastPtsMs / 1000.0
                present()
            }

            /**
             * Opens the video again, from a fresh address when one can be had, at the place the map gives for the song now.
             * False when it would not open; the watch then backs off and the cover stays.
             */
            suspend fun reopen(): Boolean {
                playback.close()
                playback = DesktopCanvasPlayback()
                val fresh = canvas.videoId?.let { id ->
                    withContext(Dispatchers.IO) {
                        runCatching {
                            com.opencanvas.core.OpenCanvas.freshVideoStream(id,
                                com.opencanvas.core.models.OpenCanvasResolution.fromLabel(DesktopAppearanceSettings.openCanvasResolution.value))
                        }.getOrNull()
                    }
                }
                playback.decoder.showAt(decodeSize.width, decodeSize.height)
                val again = playback.open(listOfNotNull(fresh?.url ?: canvas.url, canvas.fallbackUrl).distinct(), fresh?.headers ?: canvas.headers)
                if (again.result.isFailure) {
                    DesktopTrackLog.log("canvas: the video would not open again (${again.result.exceptionOrNull()?.message})")
                    return false
                }
                how = again.how + if (fresh != null) ", fresh address" else ""
                val at = CanvasStallWatch.reopenAtMs(policy, sync.songMs())
                withContext(Dispatchers.IO) { playback.decoder.seekToMs(at) }
                return true
            }

            var epoch = sync.epoch()
            var positioned = false
            var shownPts = Long.MIN_VALUE
            var firstFrameLogged = false
            var stalledAtMs = 0L
            var upgradeAskedFor = IntSize.Zero
            watch.repositioned(System.currentTimeMillis())
            while (isActive) {
                // The taller stream is ready: it takes over at the place the map gives for the song, the last frame of the
                // shorter one staying up until its first, so the picture sharpens without a jump.
                upgrade?.takeIf { it.isCompleted && !reopenWanted }?.let { ready ->
                    upgrade = null
                    ready.getCompleted()?.let { (next, label) ->
                        val previous = playback
                        playback = next
                        previous.close()
                        how = label
                        positioned = false
                        watch.repositioned(System.currentTimeMillis())
                        val (w, h) = next.decoder.sourceSize
                        DesktopTrackLog.log("canvas: switched to the ${w}x$h stream ($label) at song ${sync.songMs()} ms, " +
                            "decoded at ${next.decoder.width}x${next.decoder.height}")
                    }
                }
                if (reopenWanted) {
                    val now = System.currentTimeMillis()
                    if (stalledAtMs == 0L) stalledAtMs = now
                    if (!watch.mayReopen(now)) {
                        delay(CanvasSyncDefaults.CHECK_INTERVAL_MS)
                        continue
                    }
                    if (reopen()) {
                        reopenWanted = false
                        positioned = true
                        shownPts = Long.MIN_VALUE
                        watch.repositioned(System.currentTimeMillis())
                    } else {
                        watch.reopenFailed(System.currentTimeMillis())
                        if (watch.givenUp && watch.failures == watch.maxReopens) {
                            DesktopTrackLog.log("canvas: the video could not be opened again after ${watch.failures} tries ($stallReason); " +
                                "the cover shows, trying again now and then")
                        }
                        continue
                    }
                }
                // The listener moved the picture from the video menu: the map changes, the drift check below seeks to it.
                latestSync?.takeIf { it.map !== policy.syncMap }?.let { policy = CanvasSyncPolicy(it.map, it.durationMs) }
                val songMs = sync.songMs()
                if (!policy.isVisible(songMs)) {
                    // Before the video starts or after it ends: the still cover shows through.
                    hide()
                    watch.idle()
                    positioned = false
                    shownPts = Long.MIN_VALUE
                    delay(CanvasSyncDefaults.CHECK_INTERVAL_MS / 2)
                    continue
                }
                if (!seen()) {
                    // Minimised, in the tray, out of the window or faded out: nothing is decoded until it is seen again, and
                    // then the video seeks to wherever the song has got to.
                    watch.idle()
                    snapshotFlow { seen() }.first { it }
                    positioned = false
                    shownPts = Long.MIN_VALUE
                    continue
                }
                var target = policy.targetVideoMs(songMs)
                val jumped = sync.epoch() != epoch
                epoch = sync.epoch()
                if (!positioned || jumped || kotlin.math.abs(playback.decoder.lastPtsMs - target) > CanvasSyncDefaults.HARD_SEEK_MS) {
                    // The seek is what makes a progressive reader fetch the bytes at the target first.
                    withContext(Dispatchers.IO) { playback.decoder.seekToMs(target) }
                    positioned = true
                    shownPts = Long.MIN_VALUE
                    watch.repositioned(System.currentTimeMillis())
                }
                if (!running) {
                    // Paused with the song: the picture stays at exactly where the song is, and a seek
                    // made while paused still moves it.
                    watch.idle()
                    if (shownPts == Long.MIN_VALUE || kotlin.math.abs(shownPts - target) > policy.toleranceMs) {
                        if (decodeNext()) {
                            shownPts = playback.decoder.lastPtsMs
                            showDecoded()
                        }
                    }
                    delay(CanvasSyncDefaults.CHECK_INTERVAL_MS / 5)
                    // Playing again: the next frame has the settling time to come.
                    if (running) watch.repositioned(System.currentTimeMillis())
                    continue
                }
                if (!decodeNext()) {
                    delay(CanvasSyncDefaults.CHECK_INTERVAL_MS)
                    continue
                }
                if (reopenWanted) continue // the frame came from a stream already given up on
                val pts = playback.decoder.lastPtsMs
                target = policy.targetVideoMs(sync.songMs())
                val lead = pts - target
                when {
                    // Early: hold the frame until the song gets there.
                    lead > FRAME_EARLY_MS -> delay(lead.coerceAtMost(CanvasSyncDefaults.CHECK_INTERVAL_MS))
                    // Late: skip ahead without converting what would be dropped anyway.
                    lead < -FRAME_LATE_MS -> {
                        playback.decoder.fastForwardTo(target)
                        continue
                    }
                }
                shownPts = pts
                showDecoded()
                watch.framePresented(System.currentTimeMillis())
                if (upgrade == null && decodeSize != upgradeAskedFor && canvas.videoId != null && playback.isStreaming) {
                    upgradeAskedFor = decodeSize
                    val (w, h) = playback.decoder.sourceSize
                    val cap = com.opencanvas.core.models.OpenCanvasResolution.fromLabel(DesktopAppearanceSettings.openCanvasResolution.value)
                    CanvasStreamChoice.upgradeHeight(decodeSize.width, decodeSize.height, w, h, cap.maxHeight)?.let { wanted ->
                        DesktopTrackLog.log("canvas: the ${w}x$h stream is drawn ${CanvasStreamChoice.drawnHeight(decodeSize.width, decodeSize.height, w, h)} px " +
                            "tall; asking for ${wanted}p (setting ${cap.label})")
                        upgrade = async(Dispatchers.IO) { openTaller(canvas.videoId!!, wanted, h) }
                    }
                }
                if (stalledAtMs != 0L) {
                    DesktopTrackLog.log("canvas: video back after ${System.currentTimeMillis() - stalledAtMs} ms ($how) at video $pts ms, " +
                        "song ${sync.songMs()} ms")
                    stalledAtMs = 0L
                }
                if (!firstFrameLogged) {
                    firstFrameLogged = true
                    val millis = (System.nanoTime() - startedAt) / 1_000_000
                    val (w, h) = playback.decoder.sourceSize
                    DesktopTrackLog.log(
                        "canvas: synced first frame in $millis ms (${attempt.how}) at video $pts ms, song ${sync.songMs()} ms, " +
                            "from the ${w}x$h stream, decoded at ${playback.decoder.width}x${playback.decoder.height} for a " +
                            "${decodeSize.width}x${decodeSize.height} px box",
                    )
                }
            }
        } finally {
            watching.cancel()
            upgrade?.cancel()
            upgrade?.takeIf { it.isCompleted && !it.isCancelled }?.getCompleted()?.first?.close()
            playback.close()
        }
    }

    LaunchedEffect(rendered) { reportRendered(rendered) }

    // One frame once there is one, and then — for a caller re-tinting off the
    // clip — another every so often. A small copy each time: the bitmaps on screen are rewritten with every frame.
    LaunchedEffect(rendered, spec.refreshFrameEveryMs) {
        if (!rendered) return@LaunchedEffect
        fun capture() {
            frame?.let { canvasFrameSnapshot(it, captureEdge) }?.let(reportFrame)
        }
        capture()
        val interval = spec.refreshFrameEveryMs ?: return@LaunchedEffect
        while (isActive) {
            delay(interval)
            capture()
        }
    }

    val alpha by animateFloatAsState(
        targetValue = if (rendered) 1f else 0f,
        animationSpec = tween(durationMillis = 320),
        label = "canvasAlpha",
    )
    LaunchedEffect(Unit) {
        snapshotFlow { alpha * presentationAlpha() }.collect { reportCover(it) }
    }
    DisposableEffect(Unit) {
        onDispose {
            reportRendered(false)
            reportCover(0f)
        }
    }

    val bottomFade = spec.bottomFade
    val bottomFadeEndPx = spec.bottomFadeEndPx
    Canvas(
        modifier = modifier.onSizeChanged { viewport = it }
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow()
                onScreen = bounds.width >= 1f && bounds.height >= 1f
            }
            .clipToBounds().graphicsLayer {
                this.alpha = alpha * presentationAlpha()
                // Offscreen so the bottom dissolve masks the clip, not what is behind it.
                if (bottomFade > 0.001f) compositingStrategy = CompositingStrategy.Offscreen
            },
    ) {
        val image = frame ?: return@Canvas
        val placement = com.opencanvas.core.filter.videoViewport(image.width.toFloat(), image.height.toFloat(),
            size.width, size.height, framing.copy(cx = focalX, cy = focalY),
            spec.contentMode == CanvasContentMode.FIT_PORTRAIT, spec.alignPortraitTop)
        // Skia turns the frame into a picture as it draws; when the PC is short of memory that fails ("Failed to
        // Image::makeFromBitmap"), and unguarded it took the whole window down. The frame is skipped instead: the cover
        // stays behind it, and the next frame is tried as usual.
        try {
            drawImage(
                image = image,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(image.width, image.height),
                dstOffset = IntOffset(placement.left.roundToInt(), placement.top.roundToInt()),
                dstSize = IntSize(placement.width.roundToInt(), placement.height.roundToInt()),
            )
        } catch (failure: RuntimeException) {
            CanvasFrameFailures.note(failure)
        }
        if (bottomFade > 0.001f) {
            val endY = bottomFadeEndPx?.coerceIn(0f, size.height) ?: size.height
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Black,
                    1f to Color.Transparent,
                    startY = endY * (1f - bottomFade.coerceAtMost(1f)),
                    endY = endY,
                ),
                topLeft = Offset.Zero,
                size = size,
                blendMode = BlendMode.DstIn,
            )
        }
    }
}

/**
 * Whether the window this is composed in is on screen: false while it is minimised or hidden (closed to the tray). True where
 * there is no window to ask.
 */
@Composable
private fun rememberWindowShown(): State<Boolean> {
    val window = AwtWindowLocal?.current
    return produceState(initialValue = window?.let(::isWindowShown) ?: true, window) {
        val shown = window ?: return@produceState
        val update = { value = isWindowShown(shown) }
        val windowEvents = object : WindowAdapter() {
            override fun windowIconified(e: WindowEvent) = update()
            override fun windowDeiconified(e: WindowEvent) = update()
            override fun windowStateChanged(e: WindowEvent) = update()
            override fun windowOpened(e: WindowEvent) = update()
            override fun windowClosed(e: WindowEvent) = update()
        }
        val componentEvents = object : ComponentAdapter() {
            override fun componentShown(e: ComponentEvent) = update()
            override fun componentHidden(e: ComponentEvent) = update()
        }
        shown.addWindowListener(windowEvents)
        shown.addWindowStateListener(windowEvents)
        shown.addComponentListener(componentEvents)
        update()
        try {
            kotlinx.coroutines.awaitCancellation()
        } finally {
            shown.removeWindowListener(windowEvents)
            shown.removeWindowStateListener(windowEvents)
            shown.removeComponentListener(componentEvents)
        }
    }
}

/**
 * Compose's own record of the AWT window a composable is in (`androidx.compose.ui.window.LocalWindow`, provided by every
 * `Window` and `DialogWindow`), which Compose keeps internal. Read once by name; without it every video counts as on screen,
 * as before. The app's own window locals would not do: a picture-in-picture window composed from the main one inherits them,
 * and its video would stop with the main window minimised, which is exactly when it is shown.
 */
@Suppress("UNCHECKED_CAST")
private val AwtWindowLocal: androidx.compose.runtime.CompositionLocal<Window?>? = runCatching {
    Class.forName("androidx.compose.ui.window.LocalWindowKt").getMethod("getLocalWindow").invoke(null)
        as androidx.compose.runtime.CompositionLocal<Window?>
}.getOrNull()

private fun isWindowShown(window: Window): Boolean =
    window.isShowing && !(window is Frame && (window.extendedState and Frame.ICONIFIED) != 0)

/** The desktop's clip, in the shared player's terms. */
internal fun DesktopCanvasArtwork.toShared(): CanvasArtwork = CanvasArtwork(
    url = url,
    fallbackUrl = fallbackUrl,
    title = title,
    artist = artist,
    album = album,
    source = if (source == DesktopCanvasSource.SPOTIFY) CanvasSource.SPOTIFY else CanvasSource.OTHER,
    syncMap = syncMap,
    videoDurationMs = videoDurationMs,
    videoId = videoId,
    headers = headers,
    trajectory = trajectory,
    genres = genres,
)

/** A frame this much ahead of the song waits for it; this much behind is dropped. */
private const val FRAME_EARLY_MS = 8L
private const val FRAME_LATE_MS = 90L

/** How long the shown size has to hold before the video is decoded at it. */
private const val RESIZE_SETTLE_MS = 250L

/** How long a decorative clip waits to try again when there was no memory for its bitmaps. */
private const val NO_MEMORY_RETRY_MS = 1_000L

/** Frames the canvas could not draw, logged at most once a minute so a PC short of memory does not fill the log. */
internal object CanvasFrameFailures {
    @Volatile private var lastLoggedMs = 0L
    @Volatile private var skipped = 0

    fun note(failure: RuntimeException) = skip(failure.message)

    /** No memory for the video's bitmaps: the frame is skipped, and tried again with the next one. */
    fun noteNoMemory() = skip("no memory for the frame bitmaps")

    private fun skip(why: String?) {
        skipped++
        val now = System.currentTimeMillis()
        if (now - lastLoggedMs < 60_000L) return
        lastLoggedMs = now
        DesktopTrackLog.log("canvas: could not draw a video frame ($why); skipped $skipped so far, the cover shows meanwhile")
    }
}

/**
 * A taller stream of [videoId] ([wanted]p at most), opened and ready to decode, with how it was opened; null when none taller
 * than [currentHeight] can be had. The caller seeks it to the song's place and closes the one it replaces.
 */
private suspend fun openTaller(videoId: String, wanted: Int, currentHeight: Int): Pair<DesktopCanvasPlayback, String>? {
    val resolution = com.opencanvas.core.models.OpenCanvasResolution.fromLabel("${wanted}p")
    val fresh = runCatching { com.opencanvas.core.OpenCanvas.freshVideoStream(videoId, resolution) }.getOrNull() ?: return null
    if (fresh.height in 1..currentHeight) {
        DesktopTrackLog.log("canvas: no taller stream than ${currentHeight}p (got ${fresh.height}p); staying")
        return null
    }
    val next = DesktopCanvasPlayback()
    val opened = next.open(listOf(fresh.url), fresh.headers)
    if (opened.result.isFailure) {
        DesktopTrackLog.log("canvas: the ${fresh.height}p stream would not open (${opened.result.exceptionOrNull()?.message}); staying")
        next.close()
        return null
    }
    return next to "${opened.how}, ${fresh.height}p"
}
