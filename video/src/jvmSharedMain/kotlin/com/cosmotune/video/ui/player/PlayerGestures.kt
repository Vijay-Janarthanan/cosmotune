package com.cosmotune.video.ui.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.components.cosmoGlass
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.player.PlaybackUiState
import com.cosmotune.video.player.VideoPlayback
import com.cosmotune.video.player.VideoScale
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_brightness
import com.cosmotune.video.resources.video_muted_segment
import com.cosmotune.video.resources.video_seek_back_pill
import com.cosmotune.video.resources.video_seek_forward_pill
import com.cosmotune.video.resources.video_speed_hold
import com.cosmotune.video.resources.video_volume
import com.cosmotune.video.resources.video_zoom_fill
import com.cosmotune.video.resources.video_zoom_fit
import com.cosmotune.video.ui.VideoIcons
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

/*
 * Numbers the spec leaves to the build, chosen here: a run of taps keeps adding up while its pill shows (700 ms after the last tap);
 * a page swipe or full screen's swipe down counts past a quarter of the box's height; the brightness and volume drag covers the whole
 * range over the box's height; the "Zoomed to fill" chip shows for 1.5 s (spec 6.5); the meter (spec 6.5: 36 x 160, radius 18) stands
 * 24 dp from its edge; the long press plays at 2x (spec 6.5).
 */
private const val SEEK_RUN_MS = 700L
private const val SWIPE_FRACTION = 0.25f
private const val ZOOM_CHIP_MS = 1_500L
private const val ZOOM_IN = 1.1f
private const val ZOOM_OUT = 0.9f
private const val METER_EDGE_DP = 24f
private const val METER_RADIUS_DP = 18f
private const val METER_ICON_DP = 20f
private const val METER_PAD_DP = 8f
private const val HOLD_SPEED = 2f
private const val HOVER_INPUT_MS = 250L

/** The left or right zone of a double tap (spec 6.5: 40 % each), or the middle between them. */
internal enum class TapZone { LEFT, MIDDLE, RIGHT }

internal fun tapZone(fraction: Float): TapZone = when {
    fraction < VideoMetrics.DOUBLE_TAP_ZONE -> TapZone.LEFT
    fraction > 1f - VideoMetrics.DOUBLE_TAP_ZONE -> TapZone.RIGHT
    else -> TapZone.MIDDLE
}

/** A run of double-tap seeks (spec 6.5: "adding up on repeated taps"): its direction, the seconds so far, when the last tap was. */
internal data class SeekRun(val forward: Boolean, val seconds: Int, val stamp: Long)

/** Which meter a full-screen drag shows (spec 6.5). */
internal enum class MeterKind { BRIGHTNESS, VOLUME }

/**
 * What the gestures show while they happen (spec 6.5): the seek pill of a run of double taps, the "2x speed" chip of a long press,
 * the brightness or volume meter of a full-screen drag and its level, the zoom chip of a pinch.
 */
@Stable
internal class GestureFeedback {
    var seekRun by mutableStateOf<SeekRun?>(null)
    var holding by mutableStateOf(false)
    var meter by mutableStateOf<MeterKind?>(null)
    val meterLevel = mutableFloatStateOf(0f)
    var zoomed by mutableStateOf<VideoScale?>(null)
    var zoomStamp by mutableStateOf(0L)
}

/**
 * The box's gestures (spec 6.5):
 * - a tap: [onTap] (show or hide the chrome); on the desktop ([PlayerLayout.WINDOW]) a click plays or pauses and a double click
 *   toggles the full window; the pointer moving over it is input;
 * - a double tap on the left or right 40 %: back or forward by [stepSeconds], and while its pill shows every further tap adds a step;
 * - a long press: 2x while held (not for live), the speed set back on release;
 * - [PlayerLayout.PAGE]: a swipe down calls [onCollapse], a swipe up [onToggleFullscreen];
 * - [PlayerLayout.FULLSCREEN]: with [PlayerPlatformControls.swipeControls] (a touch screen) a vertical drag on the left 40 % is the
 *   brightness and on the right 40 % the volume, a swipe down in the middle leaves full screen; without them a swipe down anywhere does;
 *   a pinch fills or fits ([onScale]).
 */
internal fun Modifier.playerGestures(
    playback: VideoPlayback,
    layout: PlayerLayout,
    platform: PlayerPlatformControls,
    feedback: GestureFeedback,
    stepSeconds: Int,
    live: Boolean,
    onTap: () -> Unit,
    onInput: () -> Unit,
    onCollapse: (() -> Unit)?,
    onToggleFullscreen: () -> Unit,
    onScale: (VideoScale) -> Unit,
): Modifier = composed {
    val tap by rememberUpdatedState(onTap)
    val input by rememberUpdatedState(onInput)
    val collapse by rememberUpdatedState(onCollapse)
    val fullscreen by rememberUpdatedState(onToggleFullscreen)
    val scale by rememberUpdatedState(onScale)
    val step by rememberUpdatedState(stepSeconds)
    val isLive by rememberUpdatedState(live)
    var savedSpeed by remember { mutableFloatStateOf(1f) }

    fun seek(forward: Boolean, steps: Int) {
        val run = feedback.seekRun
        val seconds = (if (run != null && run.forward == forward) run.seconds else 0) + steps * step
        playback.seekBy((if (forward) steps else -steps) * step * 1000L)
        feedback.seekRun = SeekRun(forward, seconds, chromeNowMs())
    }

    fun runGoing(zone: TapZone): Boolean {
        val run = feedback.seekRun ?: return false
        return zone != TapZone.MIDDLE && (zone == TapZone.RIGHT) == run.forward && chromeNowMs() - run.stamp < SEEK_RUN_MS
    }

    var chain = this.pointerInput(layout, playback) {
        detectTapGestures(
            onPress = {
                tryAwaitRelease()
                if (feedback.holding) {
                    feedback.holding = false
                    playback.setSpeed(savedSpeed)
                }
            },
            onLongPress = {
                if (!isLive) {
                    savedSpeed = playback.state.value.speed
                    playback.setSpeed(HOLD_SPEED)
                    feedback.holding = true
                }
            },
            onTap = { offset ->
                val zone = tapZone(offset.x / size.width.coerceAtLeast(1))
                when {
                    runGoing(zone) -> { seek(zone == TapZone.RIGHT, 1); input() }
                    layout == PlayerLayout.WINDOW -> { playback.togglePlayPause(); tap() }
                    else -> tap()
                }
            },
            onDoubleTap = { offset ->
                val zone = tapZone(offset.x / size.width.coerceAtLeast(1))
                when {
                    layout == PlayerLayout.WINDOW -> { fullscreen(); input() }
                    zone == TapZone.MIDDLE -> tap()
                    runGoing(zone) -> seek(zone == TapZone.RIGHT, 2)
                    else -> seek(zone == TapZone.RIGHT, 1)
                }
            },
        )
    }

    if (layout != PlayerLayout.WINDOW) {
        // After the taps in the chain, so its drags are consumed before the tap detector sees them (and cancel the tap).
        chain = chain.pointerInput(layout, platform, playback) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val height = size.height.coerceAtLeast(1).toFloat()
                val zone = tapZone(down.position.x / size.width.coerceAtLeast(1))
                val swipeControls = layout == PlayerLayout.FULLSCREEN && platform.swipeControls
                var vertical = false
                var pinch = false
                var zoom = 1f
                var dy = 0f
                var startLevel = 0f
                var meter: MeterKind? = null
                while (true) {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.count { it.pressed }
                    if (pressed == 0) break
                    if (layout == PlayerLayout.FULLSCREEN && pressed >= 2) {
                        pinch = true
                        zoom *= event.calculateZoom()
                        event.changes.forEach { it.consume() }
                        continue
                    }
                    if (pinch) continue
                    val change = event.changes.firstOrNull { it.id == down.id } ?: continue
                    val moveY = change.position.y - down.position.y
                    val moveX = change.position.x - down.position.x
                    if (!vertical && abs(moveY) > viewConfiguration.touchSlop && abs(moveY) > abs(moveX)) {
                        vertical = true
                        if (swipeControls && zone != TapZone.MIDDLE) {
                            meter = if (zone == TapZone.LEFT) MeterKind.BRIGHTNESS else MeterKind.VOLUME
                            if (meter == MeterKind.BRIGHTNESS && !platform.brightnessSupported) meter = null
                            startLevel = when (meter) {
                                MeterKind.BRIGHTNESS -> platform.brightness()
                                MeterKind.VOLUME -> platform.volume()
                                null -> 0f
                            }
                            feedback.meterLevel.floatValue = startLevel
                            feedback.meter = meter
                        }
                    }
                    if (vertical) {
                        change.consume()
                        dy = moveY
                        if (meter != null) {
                            val level = (startLevel - moveY / height).coerceIn(0f, 1f)
                            feedback.meterLevel.floatValue = level
                            if (meter == MeterKind.BRIGHTNESS) platform.setBrightness(level) else platform.setVolume(level)
                        }
                    }
                }
                when {
                    pinch -> {
                        val next = when {
                            zoom > ZOOM_IN -> VideoScale.FILL
                            zoom < ZOOM_OUT -> VideoScale.FIT
                            else -> null
                        }
                        if (next != null) {
                            scale(next)
                            feedback.zoomed = next
                            feedback.zoomStamp = chromeNowMs()
                        }
                    }
                    meter != null -> feedback.meter = null
                    vertical && layout == PlayerLayout.PAGE -> {
                        if (dy > height * SWIPE_FRACTION) collapse?.invoke()
                        else if (dy < -height * SWIPE_FRACTION) fullscreen()
                    }
                    vertical && layout == PlayerLayout.FULLSCREEN && dy > height * SWIPE_FRACTION -> fullscreen()
                }
            }
        }
    } else {
        // The desktop: moving the pointer over the box is input (at most four times a second).
        chain = chain.pointerInput(Unit) {
            var last = 0L
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Move || event.type == PointerEventType.Enter) {
                        val now = chromeNowMs()
                        if (now - last >= HOVER_INPUT_MS) {
                            last = now
                            input()
                        }
                    }
                }
            }
        }
    }
    chain
}

/**
 * What the gestures and the segments show over the video (spec 6.5): the seek pill in its half, the chips at the top centre ("2x
 * speed", "Muted: sponsor", "Zoomed to fill" for 1.5 s), and the brightness or volume meter by its edge.
 */
@Composable
internal fun BoxScope.GestureOverlays(feedback: GestureFeedback, metrics: ChromeMetrics, state: PlaybackUiState, layout: PlayerLayout) {
    val run = feedback.seekRun
    if (run != null) {
        LaunchedEffect(run) {
            delay(SEEK_RUN_MS)
            if (feedback.seekRun == run) feedback.seekRun = null
        }
        Row(Modifier.matchParentSize()) {
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                if (!run.forward) SeekPill(stringResource(Res.string.video_seek_back_pill, run.seconds))
            }
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                if (run.forward) SeekPill(stringResource(Res.string.video_seek_forward_pill, run.seconds))
            }
        }
    }

    val zoomed = feedback.zoomed
    if (zoomed != null) {
        LaunchedEffect(feedback.zoomStamp) {
            delay(ZOOM_CHIP_MS)
            feedback.zoomed = null
        }
    }
    val muted = state.mutedSegment
    if (feedback.holding || muted != null || zoomed != null) {
        Column(
            Modifier.align(Alignment.TopCenter).padding(top = metrics.chipTopDp.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(METER_PAD_DP.dp),
        ) {
            if (feedback.holding) ChromePill(stringResource(Res.string.video_speed_hold))
            if (muted != null) ChromePill(stringResource(Res.string.video_muted_segment, stringResource(segmentName(muted.category))))
            if (zoomed != null) ChromePill(stringResource(if (zoomed == VideoScale.FILL) Res.string.video_zoom_fill else Res.string.video_zoom_fit))
        }
    }

    val meter = feedback.meter
    if (meter != null && layout == PlayerLayout.FULLSCREEN) {
        PlayerMeter(
            kind = meter,
            level = feedback.meterLevel,
            modifier = Modifier
                .align(if (meter == MeterKind.BRIGHTNESS) Alignment.CenterStart else Alignment.CenterEnd)
                .padding(horizontal = METER_EDGE_DP.dp),
        )
    }
}

/**
 * The brightness or volume meter (spec 6.5: a C glass meter 36 x 160, radius 18): the level filled from the bottom in ink, the icon at
 * the foot. The level is read while drawing.
 */
@Composable
private fun PlayerMeter(kind: MeterKind, level: androidx.compose.runtime.MutableFloatState, modifier: Modifier) {
    val colors = LocalCosmoColors.current
    val shape = remember { RoundedCornerShape(METER_RADIUS_DP.dp) }
    val label = stringResource(if (kind == MeterKind.BRIGHTNESS) Res.string.video_brightness else Res.string.video_volume)
    Box(
        modifier
            .size(VideoMetrics.METER_W_DP.dp, VideoMetrics.METER_H_DP.dp)
            .semantics { contentDescription = label }
            .cosmoGlass(hazeState = null, shape = shape, overVideo = true)
            .drawBehind {
                val ink = colors.live(CoverField.TEXT_PRIMARY)
                val inset = METER_PAD_DP.dp.toPx()
                val trackTop = inset
                val trackBottom = size.height - inset - METER_ICON_DP.dp.toPx() - inset
                val trackH = (trackBottom - trackTop).coerceAtLeast(0f)
                val w = size.width - 2 * inset
                val corner = CornerRadius(w / 2f)
                drawRoundRect(ink.copy(alpha = ink.alpha * REST_TRACK_ALPHA), Offset(inset, trackTop), Size(w, trackH), corner)
                val filled = trackH * level.floatValue.coerceIn(0f, 1f)
                if (filled > 0f) drawRoundRect(ink, Offset(inset, trackBottom - filled), Size(w, filled), corner)
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Icon(
            if (kind == MeterKind.BRIGHTNESS) VideoIcons.Brightness else CosmoIcons.Volume,
            contentDescription = null,
            tint = Color(colors.palette.textPrimary),
            modifier = Modifier.padding(bottom = METER_PAD_DP.dp).size(METER_ICON_DP.dp),
        )
    }
}

/** The meter's empty track: ink at 25 %. */
private const val REST_TRACK_ALPHA = 0.25f
