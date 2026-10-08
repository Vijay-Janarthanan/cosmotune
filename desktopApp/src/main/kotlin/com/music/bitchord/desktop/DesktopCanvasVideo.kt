package com.music.bitchord.desktop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.music.bitchord.data.canvas.CanvasArtwork
import com.opencanvas.core.sync.CanvasSyncDefaults
import com.opencanvas.core.sync.CanvasSyncPolicy
import com.music.bitchord.data.canvas.CanvasSource
import com.music.bitchord.ui.player.CanvasContentMode
import com.music.bitchord.ui.player.CanvasVideoSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
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
 */
@Composable
internal fun DesktopCanvasVideo(spec: CanvasVideoSpec, modifier: Modifier) {
    val canvas = spec.canvas
    var frame by remember(canvas) { mutableStateOf<ImageBitmap?>(null) }
    val rendered = frame != null
    val running by rememberUpdatedState(spec.isPlaying && !spec.pausedForTransition)
    val reportAspect by rememberUpdatedState(spec.onAspectRatioChanged)
    val reportRendered by rememberUpdatedState(spec.onRenderedChanged)
    val reportFrame by rememberUpdatedState(spec.onFrameCaptured)
    val reportCover by rememberUpdatedState(spec.onCoverChanged)
    val presentationAlpha by rememberUpdatedState(spec.presentationAlpha)

    LaunchedEffect(canvas) {
        // A music video that follows the song is driven by the effect below instead.
        if (spec.sync != null) return@LaunchedEffect
        // Round and round for as long as the clip is mounted: a canvas is a few
        // seconds long, and the decoder cannot be relied on to rewind an HLS
        // manifest itself, so each pass reopens it.
        while (isActive) {
            val decoder = DesktopCanvasDecoder()
            val attempt = withContext(Dispatchers.IO) {
                DesktopCanvasCache.open(decoder, listOfNotNull(canvas.url, canvas.fallbackUrl), canvas.headers)
            }
            val opened = attempt.result
            val streaming = attempt.streaming
            if (opened.isFailure) {
                DesktopTrackLog.log("canvas: ${opened.exceptionOrNull()?.message}")
                withContext(Dispatchers.IO) { decoder.close() }
                streaming?.close()
                return@LaunchedEffect
            }
            reportAspect(decoder.width.toFloat() / decoder.height.coerceAtLeast(1))
            var shown = 0
            try {
                val pixels = ByteArray(decoder.width * decoder.height * 4)
                val info = ImageInfo.makeN32(decoder.width, decoder.height, ColorAlphaType.OPAQUE)
                while (isActive) {
                    // Paused with the track, or for the length of the sleeve's
                    // collapse: the last frame stays up, nothing is decoded.
                    snapshotFlow { running }.first { it }
                    val started = System.currentTimeMillis()
                    val decoded = withContext(Dispatchers.IO) { decoder.nextFrame(pixels) }
                    if (!decoded) break
                    shown++
                    // Handed to Skia rather than copied, so the next frame
                    // cannot be decoded into the same array.
                    frame = Image.makeRaster(info, pixels.copyOf(), decoder.width * 4).toComposeImageBitmap()
                    val spent = System.currentTimeMillis() - started
                    delay((decoder.frameIntervalMillis - spent).coerceAtLeast(0L))
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    decoder.close()
                    streaming?.close()
                }
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
    LaunchedEffect(canvas) {
        val sync = spec.sync ?: return@LaunchedEffect
        val policy = CanvasSyncPolicy(sync.map, sync.durationMs)
        val decoder = DesktopCanvasDecoder()
        val startedAt = System.nanoTime()
        val attempt = withContext(Dispatchers.IO) {
            DesktopCanvasCache.open(decoder, listOfNotNull(canvas.url, canvas.fallbackUrl), canvas.headers)
        }
        val streaming = attempt.streaming
        try {
            if (attempt.result.isFailure) {
                DesktopTrackLog.log("canvas: ${attempt.result.exceptionOrNull()?.message}")
                return@LaunchedEffect
            }
            reportAspect(decoder.width.toFloat() / decoder.height.coerceAtLeast(1))
            val pixels = ByteArray(decoder.width * decoder.height * 4)
            val info = ImageInfo.makeN32(decoder.width, decoder.height, ColorAlphaType.OPAQUE)
            fun present() {
                // Handed to Skia rather than copied, so the next frame cannot be decoded into it.
                frame = Image.makeRaster(info, pixels.copyOf(), decoder.width * 4).toComposeImageBitmap()
            }
            var epoch = sync.epoch()
            var positioned = false
            var shownPts = Long.MIN_VALUE
            var firstFrameLogged = false
            while (isActive) {
                val songMs = sync.songMs()
                if (!policy.isVisible(songMs)) {
                    // Before the video starts or after it ends: the still cover shows through.
                    frame = null
                    positioned = false
                    shownPts = Long.MIN_VALUE
                    delay(CanvasSyncDefaults.CHECK_INTERVAL_MS / 2)
                    continue
                }
                var target = policy.targetVideoMs(songMs)
                val jumped = sync.epoch() != epoch
                epoch = sync.epoch()
                if (!positioned || jumped || kotlin.math.abs(decoder.lastPtsMs - target) > CanvasSyncDefaults.HARD_SEEK_MS) {
                    // The seek is what makes a progressive reader fetch the bytes at the target first.
                    withContext(Dispatchers.IO) { decoder.seekToMs(target) }
                    positioned = true
                    shownPts = Long.MIN_VALUE
                }
                if (!running) {
                    // Paused with the song: the picture stays at exactly where the song is, and a seek
                    // made while paused still moves it.
                    if (shownPts == Long.MIN_VALUE || kotlin.math.abs(shownPts - target) > policy.toleranceMs) {
                        if (withContext(Dispatchers.IO) { decoder.nextFrame(pixels, loop = false) }) {
                            shownPts = decoder.lastPtsMs
                            present()
                        }
                    }
                    delay(CanvasSyncDefaults.CHECK_INTERVAL_MS / 5)
                    continue
                }
                if (!withContext(Dispatchers.IO) { decoder.nextFrame(pixels, loop = false) }) {
                    delay(CanvasSyncDefaults.CHECK_INTERVAL_MS)
                    continue
                }
                val pts = decoder.lastPtsMs
                target = policy.targetVideoMs(sync.songMs())
                val lead = pts - target
                when {
                    // Early: hold the frame until the song gets there.
                    lead > FRAME_EARLY_MS -> delay(lead.coerceAtMost(CanvasSyncDefaults.CHECK_INTERVAL_MS))
                    // Late: skip ahead without converting what would be dropped anyway.
                    lead < -FRAME_LATE_MS -> {
                        decoder.fastForwardTo(target)
                        continue
                    }
                }
                shownPts = pts
                present()
                if (!firstFrameLogged) {
                    firstFrameLogged = true
                    val millis = (System.nanoTime() - startedAt) / 1_000_000
                    DesktopTrackLog.log(
                        "canvas: synced first frame in $millis ms (${attempt.how}) at video $pts ms, song ${sync.songMs()} ms",
                    )
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                decoder.close()
                streaming?.close()
            }
        }
    }

    LaunchedEffect(rendered) { reportRendered(rendered) }

    // One frame once there is one, and then — for a caller re-tinting off the
    // clip — another every so often.
    LaunchedEffect(rendered, spec.refreshFrameEveryMs) {
        if (!rendered) return@LaunchedEffect
        frame?.let(reportFrame)
        val interval = spec.refreshFrameEveryMs ?: return@LaunchedEffect
        while (isActive) {
            delay(interval)
            frame?.let(reportFrame)
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
        modifier = modifier.graphicsLayer {
            this.alpha = alpha * presentationAlpha()
            // Offscreen so the bottom dissolve masks the clip, not what is behind it.
            if (bottomFade > 0.001f) compositingStrategy = CompositingStrategy.Offscreen
        },
    ) {
        val image = frame ?: return@Canvas
        val clipAspect = image.width.toFloat() / image.height
        val viewAspect = size.width / size.height
        val fit = spec.contentMode == CanvasContentMode.FIT_PORTRAIT && clipAspect < 1f
        val (drawWidth, drawHeight) = when {
            fit && clipAspect > viewAspect -> size.width to size.width / clipAspect
            fit -> size.height * clipAspect to size.height
            clipAspect > viewAspect -> size.height * clipAspect to size.height
            else -> size.width to size.width / clipAspect
        }
        val left = (size.width - drawWidth) / 2f
        val top = if (fit && spec.alignPortraitTop) 0f else (size.height - drawHeight) / 2f
        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
            dstSize = IntSize(drawWidth.roundToInt(), drawHeight.roundToInt()),
        )
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
)

/** A frame this much ahead of the song waits for it; this much behind is dropped. */
private const val FRAME_EARLY_MS = 8L
private const val FRAME_LATE_MS = 90L
