package com.cosmotune.ui.nowplaying

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.cosmotune.design.VideoView
import com.cosmotune.music.data.canvas.CanvasArtwork
import com.cosmotune.music.data.model.artworkAt
import com.cosmotune.music.ui.player.CanvasArtworkPlayer
import com.cosmotune.music.ui.player.CanvasContentMode
import com.cosmotune.music.ui.player.renderEffectBlurSupported
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.Disc
import com.cosmotune.ui.orbit.OrbitMenuItem
import com.cosmotune.ui.orbit.OrbitRing
import com.cosmotune.ui.theme.LocalCosmoColors
import kotlin.math.PI
import kotlin.math.atan2

/**
 * The song's video in the box it is given ([modifier]), shown as [view] says (the Orbit board, section 4); nothing is drawn on it.
 *  - [VideoView.WHOLE]: the video at its own shape ([aspect], 16:9 until the clip says), as large as fits and centred, never
 *    cut; with [glow] the space round it holds a soft blur of the video's own frames (the cover's until the first frame is
 *    caught) over the cover's glow colour, so the bars are never black.
 *  - [VideoView.FILL] (and anything else): the video cut to fill the box.
 * The clip is told [CanvasContentMode.CROP] in a box of its own shape, so Whole shows every pixel. [reveal] is the clip's alpha
 * (0 = not there yet, 1 = in) and the glow's. [onDoubleTap] (Cinema) and [onPinch] (out: Fill, in: Whole) are the gestures the
 * video takes; a single finger passes through, so a drag on the video still reaches the page. [onFramePlaced] gets the video's
 * rectangle in root coordinates (where the player's open and close morph lands).
 */
@Composable
internal fun NowPlayingVideo(
    canvas: CanvasArtwork,
    isPlaying: Boolean,
    view: VideoView,
    aspect: Float,
    onAspect: (Float) -> Unit,
    reveal: State<Float>,
    onRenderedChanged: (Boolean) -> Unit,
    coverUrl: String?,
    radius: Dp,
    modifier: Modifier = Modifier,
    glow: Boolean = true,
    onDoubleTap: (() -> Unit)? = null,
    onPinch: ((VideoView) -> Unit)? = null,
    onFramePlaced: ((Rect) -> Unit)? = null,
) {
    var still by remember(canvas) { mutableStateOf<ImageBitmap?>(null) }
    val whole = view != VideoView.FILL
    BoxWithConstraints(modifier) {
        val box = if (whole) VideoView.whole(maxWidth.value, maxHeight.value, if (aspect > 0f) aspect else VideoView.DEFAULT_ASPECT)
        else floatArrayOf(0f, 0f, maxWidth.value, maxHeight.value)
        if (whole && glow) VideoGlow(still, coverUrl, reveal, Modifier.matchParentSize())
        Box(
            Modifier.offset(box[0].dp, box[1].dp).size(box[2].dp, box[3].dp)
                .then(if (onFramePlaced != null) Modifier.onGloballyPositioned { onFramePlaced(it.boundsInRoot()) } else Modifier)
                .clip(RoundedCornerShape(radius))
                .videoGestures(onDoubleTap, onPinch),
        ) {
            CanvasArtworkPlayer(
                canvas = canvas,
                isPlaying = isPlaying,
                modifier = Modifier.fillMaxSize(),
                contentMode = CanvasContentMode.CROP,
                onAspectRatioChanged = { if (it > 0f && it.isFinite()) onAspect(it) },
                onRenderedChanged = onRenderedChanged,
                presentationAlpha = { reveal.value.coerceIn(0f, 1f) },
                onFrameCaptured = { if (whole && glow) still = it },
                refreshFrameEveryMs = if (whole && glow) GLOW_FRAME_EVERY_MS else null,
            )
        }
    }
}

/** How often the glow takes a new frame of the video (where the platform can hand one over; the desktop keeps the cover's). */
private const val GLOW_FRAME_EVERY_MS = 4_000L

/** The glow round a whole video: the cover's glow colour, and over it a heavy blur of the video's frame (or the cover). */
@Composable
private fun VideoGlow(still: ImageBitmap?, coverUrl: String?, reveal: State<Float>, modifier: Modifier) {
    val colors = LocalCosmoColors.current
    Box(modifier.graphicsLayer { alpha = reveal.value.coerceIn(0f, 1f) }) {
        Box(
            Modifier.matchParentSize().drawBehind {
                val glow = colors.live(CoverField.GLOW)
                drawRect(Brush.radialGradient(listOf(glow.copy(alpha = glow.alpha * 0.7f), glow.copy(alpha = 0f)), center, size.maxDimension * 0.62f))
            },
        )
        if (renderEffectBlurSupported) {
            val blurred = Modifier.matchParentSize().graphicsLayer { alpha = 0.72f }.blur(60.dp, BlurredEdgeTreatment.Unbounded)
            if (still != null) Image(still, null, blurred, contentScale = ContentScale.Crop)
            else if (coverUrl != null) AsyncImage(coverUrl.artworkAt(160), null, blurred, contentScale = ContentScale.Crop)
        }
    }
}

/**
 * The video's own gestures: a double tap ([onDoubleTap]) and a two-finger pinch ([onPinch]: spread for Fill, pinch for Whole).
 * A single finger's drag is left alone for the page under it.
 */
private fun Modifier.videoGestures(onDoubleTap: (() -> Unit)?, onPinch: ((VideoView) -> Unit)?): Modifier {
    var m: Modifier = this
    if (onDoubleTap != null) m = m.pointerInput(onDoubleTap) { detectTapGestures(onDoubleTap = { onDoubleTap() }) }
    if (onPinch != null) m = m.pointerInput(onPinch) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var zoom = 1f
            var pinched = false
            do {
                val event = awaitPointerEvent()
                if (event.changes.count { it.pressed } >= 2) {
                    pinched = true
                    zoom *= event.calculateZoom()
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            } while (event.changes.any { it.pressed })
            if (pinched && zoom > PINCH_STEP) onPinch(VideoView.FILL)
            else if (pinched && zoom < 1f / PINCH_STEP) onPinch(VideoView.WHOLE)
        }
    }
    return m
}

private const val PINCH_STEP = 1.12f

/**
 * The record (no video, board section 3): the cover as a large disc with its shadow, and round it the orbit of the song: the ring
 * is the progress ([progress], read in draw), the moon in [moon] at the current second. Dragging round the ring moves the moon,
 * and letting go seeks there ([onSeek]). [disc] is the disc's side; the ring is [RECORD_RING_GAP] wider all round. [onPlaced]
 * gets the disc's rectangle in root coordinates.
 */
@Composable
internal fun NowPlayingRecord(
    coverUrl: String?,
    disc: Dp,
    ink: Color,
    moon: Color,
    progress: () -> Float?,
    playing: Boolean,
    onSeek: ((Float) -> Unit)?,
    modifier: Modifier = Modifier,
    onPlaced: ((Rect) -> Unit)? = null,
) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val seek by rememberUpdatedState(onSeek)
    val ring = disc + RECORD_RING_GAP * 2
    Box(modifier.size(ring), contentAlignment = Alignment.Center) {
        Disc(
            coverUrl, disc,
            Modifier.shadow(36.dp, CircleShape, clip = false, ambientColor = Color.Black.copy(alpha = 0.5f), spotColor = Color.Black.copy(alpha = 0.6f))
                .then(if (onPlaced != null) Modifier.onGloballyPositioned { onPlaced(it.boundsInRoot()) } else Modifier),
        )
        OrbitRing(
            ring, ink, progress = { drag ?: progress() }, spinning = playing,
            stroke = 2.5.dp, moon = 8.dp, moonColor = moon,
            modifier = if (onSeek == null) Modifier else Modifier.pointerInput(Unit) {
                fun at(p: Offset): Float {
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val a = atan2(p.y - c.y, p.x - c.x) + PI / 2
                    return (((a / (2 * PI)) + 1.0) % 1.0).toFloat()
                }
                detectDragGestures(
                    onDragStart = { drag = at(it) },
                    onDragEnd = { drag?.let { f -> seek?.invoke(f) }; drag = null },
                    onDragCancel = { drag = null },
                ) { change, _ -> change.consume(); drag = at(change.position) }
            },
        )
    }
}

/** How far the record's orbit sits outside the disc, all round. */
internal val RECORD_RING_GAP = 26.dp

/**
 * The screen button's menu (board section 4): Whole, Fill, Cinema, Mini (coming) and Off (the record). [view] is the kept view,
 * [cinema] whether Cinema is showing, [videoOn] whether the video is on at all.
 */
internal fun videoViewItems(
    view: VideoView,
    cinema: Boolean,
    videoOn: Boolean,
    onView: (VideoView) -> Unit,
    onCinema: (Boolean) -> Unit,
    onVideoOn: (Boolean) -> Unit,
): List<OrbitMenuItem> = listOf(
    OrbitMenuItem("Whole", "Nothing cut", selected = videoOn && !cinema && view == VideoView.WHOLE) { onVideoOn(true); onCinema(false); onView(VideoView.WHOLE) },
    OrbitMenuItem("Fill", "Fills the frame, cuts the edges", selected = videoOn && !cinema && view == VideoView.FILL) { onVideoOn(true); onCinema(false); onView(VideoView.FILL) },
    OrbitMenuItem("Cinema", "The whole window · F", selected = videoOn && cinema) { onVideoOn(true); onCinema(true) },
    OrbitMenuItem("Mini", "A small floating video · coming soon", enabled = false) {},
    OrbitMenuItem("Off", "The record. Saves data and battery", selected = !videoOn) { onCinema(false); onVideoOn(false) },
)

/** The screen button's icon. */
internal val VIDEO_VIEW_ICON = CosmoIcons.Canvas
