package com.cosmotune.ui.nowplaying

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import com.cosmotune.ui.fullscreen.pinchToFill
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
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
import com.cosmotune.ui.orbit.OrbitMenuItem
import com.cosmotune.ui.theme.LocalCosmoColors

/**
 * The song's video in the box it is given ([modifier]), shown as [view] says (the Orbit board, section 4); nothing is drawn on it.
 *  - [VideoView.WHOLE]: the video at its own shape ([aspect], 16:9 until the clip says), as large as fits and centred, never
 *    cut; with [glow] the space round it holds a soft blur of the video's own frames (the cover's until the first frame is
 *    caught) over the cover's glow colour, so the bars are never black.
 *  - [VideoView.FILL] (and anything else): the video cut to fill the box.
 * The clip is told [CanvasContentMode.CROP] in a box of its own shape, so Whole shows every pixel. [reveal] is the clip's alpha
 * (0 = not there yet, 1 = in) and the glow's. [onDoubleTap] (Cinema) and [onPinch] (out: Fill, in: Whole) are the gestures the
 * picture takes, on the video and its glow alike; a single finger passes through, so a drag on it still reaches the page.
 * [onFramePlaced] gets the video's rectangle in root coordinates (where the player's open and close morph lands).
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
    BoxWithConstraints(modifier.videoGestures(onDoubleTap, onPinch)) {
        val box = if (whole) VideoView.whole(maxWidth.value, maxHeight.value, if (aspect > 0f) aspect else VideoView.DEFAULT_ASPECT)
        else floatArrayOf(0f, 0f, maxWidth.value, maxHeight.value)
        // The glow is cut to the frame's own corners: unclipped, its blurred square showed past the rounded video (a pale square
        // in the corners on a light page).
        if (whole && glow) VideoGlow(still, coverUrl, reveal, Modifier.matchParentSize().clip(RoundedCornerShape(radius)))
        Box(
            Modifier.offset(box[0].dp, box[1].dp).size(box[2].dp, box[3].dp)
                .then(if (onFramePlaced != null) Modifier.onGloballyPositioned { onFramePlaced(it.boundsInRoot()) } else Modifier)
                .clip(RoundedCornerShape(radius)),
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
    if (onPinch != null) m = m.pinchToFill { fill -> onPinch(if (fill) VideoView.FILL else VideoView.WHOLE) }
    return m
}

/**
 * The song's square cover where the video would be (Off, or a song without a video; the Now Playing board, B2, V9 and D2). On the
 * phone ([centred] false) the cover is a square of the box's width at its top, cut at the box's foot when the box is shorter;
 * the page's ground fades over its edges (see [NowPlayingPhone]). In a wide window ([centred]) the cover sits centred at its own
 * size over a blurred copy of itself that fills the box. With [loop] (the cover's animated loop) the loop plays over the cover,
 * cut to it. [onPlaced] gets the cover's rectangle in root coordinates (where the player's open and close morph lands).
 */
@Composable
internal fun NowPlayingCover(
    coverUrl: String?,
    modifier: Modifier = Modifier,
    centred: Boolean = false,
    onPlaced: ((Rect) -> Unit)? = null,
    loop: (@Composable (Modifier) -> Unit)? = null,
) {
    val placed = if (onPlaced != null) Modifier.onGloballyPositioned { onPlaced(it.boundsInRoot()) } else Modifier
    if (!centred) {
        Box(modifier.clipToBounds().testTag("nowplaying:cover")) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f).align(Alignment.TopCenter).then(placed)) {
                AsyncImage(coverUrl?.artworkAt(COVER_PX), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
                loop?.invoke(Modifier.matchParentSize())
            }
        }
        return
    }
    val colors = LocalCosmoColors.current
    BoxWithConstraints(modifier.clipToBounds().testTag("nowplaying:cover"), contentAlignment = Alignment.Center) {
        // The blurred copy where the platform blurs; under it (and alone elsewhere) the cover's glow colour.
        Box(Modifier.matchParentSize().drawBehind {
            val glow = colors.live(CoverField.GLOW)
            drawRect(Brush.radialGradient(listOf(glow.copy(alpha = glow.alpha * 0.8f), glow.copy(alpha = 0f)), center, size.maxDimension * 0.6f))
        })
        if (renderEffectBlurSupported && coverUrl != null) {
            AsyncImage(coverUrl.artworkAt(160), null, Modifier.matchParentSize().graphicsLayer { alpha = 0.7f }.blur(44.dp, BlurredEdgeTreatment.Unbounded), contentScale = ContentScale.Crop)
        }
        val side = (minOf(maxWidth, maxHeight) - 48.dp).coerceIn(0.dp, COVER_MAX)
        if (side > 0.dp) Box(
            Modifier.size(side).then(placed)
                .shadow(28.dp, RoundedCornerShape(16.dp), clip = false, ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.35f))
                .clip(RoundedCornerShape(16.dp)),
        ) {
            AsyncImage(coverUrl?.artworkAt(COVER_PX), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
            loop?.invoke(Modifier.matchParentSize())
        }
    }
}

/** The cover's request size, and the largest a centred cover is drawn. */
private const val COVER_PX = 1200
private val COVER_MAX = 440.dp

/**
 * The cover's animated loop (a short clip that does not follow the song), cut to fill the box it is given, which the cover
 * clips to. It fades in over the still cover as [reveal] comes up, through the clip's own alpha.
 */
@Composable
internal fun NowPlayingCoverLoop(canvas: CanvasArtwork, isPlaying: Boolean, reveal: State<Float>, onRenderedChanged: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    CanvasArtworkPlayer(
        canvas = canvas,
        isPlaying = isPlaying,
        modifier = modifier,
        contentMode = CanvasContentMode.CROP,
        onRenderedChanged = onRenderedChanged,
        presentationAlpha = { reveal.value.coerceIn(0f, 1f) },
    )
}

/** Gone as far as [reveal] has come in: the cover under a video that is arriving. Read in the layer, so nothing recomposes. */
internal fun Modifier.fadedBy(reveal: State<Float>): Modifier = graphicsLayer { alpha = 1f - reveal.value.coerceIn(0f, 1f) }

/**
 * The screen button's menu (board section V1): Whole, Fill, Cinema, Picture in picture and Off (the cover). [view]
 * is the kept view, [cinema] whether Cinema is showing, [videoOn] whether the video is on at all. [pip] takes the video into
 * picture-in-picture (the system's small window on the phone, the small always-on-top window on the desktop), [inPip] whether it is
 * there now; with no [pip] (a device that has none) the item is shown, and not offered.
 */
internal fun videoViewItems(
    view: VideoView,
    cinema: Boolean,
    videoOn: Boolean,
    onView: (VideoView) -> Unit,
    onCinema: (Boolean) -> Unit,
    onVideoOn: (Boolean) -> Unit,
    pip: (() -> Unit)? = null,
    inPip: Boolean = false,
): List<OrbitMenuItem> = listOf(
    OrbitMenuItem("Whole", "Nothing cut", selected = videoOn && !cinema && !inPip && view == VideoView.WHOLE) { onVideoOn(true); onCinema(false); onView(VideoView.WHOLE) },
    OrbitMenuItem("Fill", "Fills the frame, cuts the edges", selected = videoOn && !cinema && !inPip && view == VideoView.FILL) { onVideoOn(true); onCinema(false); onView(VideoView.FILL) },
    // The key hint only where there is a keyboard to press it on (the desktop); a phone has none.
    OrbitMenuItem("Cinema", if (com.cosmotune.ui.components.platformSdkInt == null) "The whole window · F" else "The whole window", selected = videoOn && cinema && !inPip) { onVideoOn(true); onCinema(true) },
    if (pip != null) OrbitMenuItem("Picture in picture", "A small floating video, the music plays on", selected = inPip) { onVideoOn(true); onCinema(false); pip() }
    else OrbitMenuItem("Picture in picture", "Not on this device", enabled = false) {},
    OrbitMenuItem("Off", "The cover. Saves data and battery", selected = !videoOn) { onCinema(false); onVideoOn(false) },
)

/** The screen button's icon. */
internal val VIDEO_VIEW_ICON = CosmoIcons.Canvas
