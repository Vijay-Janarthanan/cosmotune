package com.cosmotune.video.ui.player

import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitActionPill
import com.cosmotune.ui.orbit.orbitFloatGroundColor
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.ui.theme.CosmoMotion
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalReducedMotion
import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.player.VideoPlayback
import com.cosmotune.video.player.controller.VideoPlayerController
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_seg_exclusive_access
import com.cosmotune.video.resources.video_seg_filler
import com.cosmotune.video.resources.video_seg_hook
import com.cosmotune.video.resources.video_seg_interaction
import com.cosmotune.video.resources.video_seg_intro
import com.cosmotune.video.resources.video_seg_music_offtopic
import com.cosmotune.video.resources.video_seg_outro
import com.cosmotune.video.resources.video_seg_poi_highlight
import com.cosmotune.video.resources.video_seg_preview
import com.cosmotune.video.resources.video_seg_selfpromo
import com.cosmotune.video.resources.video_seg_sponsor
import com.cosmotune.video.resources.video_skip_segment
import com.cosmotune.video.resources.video_skipped
import com.cosmotune.video.resources.video_undo
import com.cosmotune.video.segments.SegmentCategory
import com.cosmotune.video.segments.SkipSegment
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The line under the "Skip" label (spec 6.5: 2 dp, the segment's remaining time), just under the 14 sp label. */
private const val SKIP_LINE_DP = 2f
private const val RING_TRACK_ALPHA = 0.22f
private const val SKIP_RING_GAP_DP = 8f
private const val SKIP_LINE_BELOW_CENTRE_DP = 10f

/** The toast (`.toast3`, as `CosmoToast`): at least 52 dp, padding 8 8 8 18, 14.5 sp 500; it rises 16 dp into place, 8 dp above what is under it. */
private const val TOAST_MIN_H_DP = 52f
private const val TOAST_PAD_START_DP = 18f
private const val TOAST_PAD_DP = 8f
private const val TOAST_SP = 14.5f
private const val TOAST_WEIGHT = 500
private const val TOAST_RISE_DP = 16f
private val TOAST_SHAPE = RoundedCornerShape(percent = 50)

/**
 * SponsorBlock's "Skip" (spec 6.5): the player's Orbit round button (the next glyph) ringed by the segment's remaining time, which
 * runs down as the segment plays; 12 dp from the right, 48 dp above the bottom (96 in full screen; the caller places it); shown until
 * the segment ends (the controller clears `pendingSkip`). "Skip sponsor" is its content description. The ring reads the playhead
 * while drawing.
 */
@Composable
internal fun SkipSegmentButton(segment: SkipSegment, playback: VideoPlayback, playhead: Playhead, onSkip: () -> Unit, modifier: Modifier = Modifier) {
    val span = (segment.endMs - segment.startMs).coerceAtLeast(1)
    Box(modifier.padding(end = SKIP_END_DP.dp), contentAlignment = Alignment.Center) {
        PlayerButton(
            icon = CosmoIcons.Next,
            contentDescription = stringResource(Res.string.video_skip_segment, stringResource(segmentName(segment.category))),
            onClick = onSkip,
            size = VideoMetrics.SKIP_BUTTON_H_DP.dp,
        )
        // the orbit round the button: the segment's remaining time as an arc with a moon at its end, read while drawing
        Box(
            Modifier
                .size((VideoMetrics.SKIP_BUTTON_H_DP + SKIP_RING_GAP_DP).dp)
                .drawBehind {
                    playhead.frame.longValue   // a new frame: draw again
                    val left = (segment.endMs - playback.positionMs()).coerceIn(0, span).toFloat() / span
                    val stroke = SKIP_LINE_DP.dp.toPx()
                    val inset = stroke / 2f + 1.dp.toPx()
                    val arcSize = Size(size.width - 2 * inset, size.height - 2 * inset)
                    drawArc(Color.White.copy(alpha = RING_TRACK_ALPHA), 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                    if (left > 0f) {
                        drawArc(Color.White, -90f, 360f * left, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                        val angle = Math.toRadians((-90f + 360f * left).toDouble())
                        val radius = arcSize.width / 2f
                        drawCircle(
                            Color.White, 3.dp.toPx(),
                            Offset(size.width / 2f + radius * kotlin.math.cos(angle).toFloat(), size.height / 2f + radius * kotlin.math.sin(angle).toFloat()),
                        )
                    }
                },
        )
    }
}

/**
 * "Skipped sponsor" with "Undo" (spec 6.5: `CosmoToast`, the C tertiary action, 4 s): the toast of `CosmoToast` with an action, which
 * `CosmoToast` does not take, so it is built here with the same numbers. It shows while the controller keeps [segment] as its last
 * skip, for at most [VideoPlayerController.LAST_SKIP_MS].
 */
@Composable
internal fun SegmentSkippedToast(segment: SkipSegment?, onUndo: () -> Unit, bottomDp: Float, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf<SkipSegment?>(null) }
    LaunchedEffect(segment?.uuid) {
        shown = segment
        if (segment != null) {
            delay(VideoPlayerController.LAST_SKIP_MS)
            shown = null
        }
    }
    val skipped = shown ?: return
    val look = orbitLook()
    val fonts = orbitFonts()
    val reduced = LocalReducedMotion.current
    val message = stringResource(Res.string.video_skipped, stringResource(segmentName(skipped.category)))
    val style = remember(fonts) { fonts.title.copy(fontSize = TOAST_SP.sp, lineHeight = TOAST_SP.sp * 1.3f) }
    val arrival = remember(skipped.uuid) { Animatable(0f) }
    LaunchedEffect(skipped.uuid) { arrival.animateTo(1f, CosmoMotion.move(reduced)) }
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = CosmoSpacing.margin)
            .padding(bottom = TOAST_PAD_DP.dp + bottomDp.dp)
            .graphicsLayer {
                val progress = arrival.value
                alpha = progress.coerceIn(0f, 1f)
                translationY = if (reduced) 0f else (1f - progress) * TOAST_RISE_DP.dp.toPx()
            }
            .semantics { liveRegion = LiveRegionMode.Polite }
            .clip(TOAST_SHAPE)
            .background(orbitFloatGroundColor(look))
            .heightIn(min = TOAST_MIN_H_DP.dp)
            .padding(start = TOAST_PAD_START_DP.dp, top = TOAST_PAD_DP.dp, end = TOAST_PAD_DP.dp, bottom = TOAST_PAD_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = message, style = style, color = look.ink, modifier = Modifier.weight(1f))
        OrbitActionPill(
            label = stringResource(Res.string.video_undo),
            onClick = { shown = null; onUndo() },
            fonts = fonts,
            look = look,
            primary = true,
        )
    }
}

/** A category's name in running text ("sponsor", "self-promotion"; the `video_seg_*` keys). */
internal fun segmentName(category: SegmentCategory): StringResource = when (category) {
    SegmentCategory.SPONSOR -> Res.string.video_seg_sponsor
    SegmentCategory.SELF_PROMO -> Res.string.video_seg_selfpromo
    SegmentCategory.INTERACTION -> Res.string.video_seg_interaction
    SegmentCategory.INTRO -> Res.string.video_seg_intro
    SegmentCategory.OUTRO -> Res.string.video_seg_outro
    SegmentCategory.PREVIEW -> Res.string.video_seg_preview
    SegmentCategory.HOOK -> Res.string.video_seg_hook
    SegmentCategory.MUSIC_OFFTOPIC -> Res.string.video_seg_music_offtopic
    SegmentCategory.FILLER -> Res.string.video_seg_filler
    SegmentCategory.POI_HIGHLIGHT -> Res.string.video_seg_poi_highlight
    SegmentCategory.EXCLUSIVE_ACCESS -> Res.string.video_seg_exclusive_access
}
