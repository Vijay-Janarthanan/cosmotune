package com.cosmotune.video.ui.player

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
import com.cosmotune.design.ControlMetrics
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.components.CosmoButton
import com.cosmotune.ui.components.CosmoButtonKind
import com.cosmotune.ui.components.CosmoButtonSize
import com.cosmotune.ui.components.cosmoGlass
import com.cosmotune.ui.theme.CosmoMotion
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
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
 * SponsorBlock's "Skip" (spec 6.5): a C primary pill 40 dp ("Skip sponsor", 14 sp, padding 18) with a 2 dp line under the label that
 * shrinks with the segment's remaining time; 12 dp from the right, 48 dp above the bottom (96 in full screen; the caller places it);
 * shown until the segment ends (the controller clears `pendingSkip`). The line reads the playhead while drawing.
 */
@Composable
internal fun SkipSegmentButton(segment: SkipSegment, playback: VideoPlayback, playhead: Playhead, onSkip: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalCosmoColors.current
    val label = stringResource(Res.string.video_skip_segment, stringResource(segmentName(segment.category)))
    val span = (segment.endMs - segment.startMs).coerceAtLeast(1)
    CosmoButton(
        text = label,
        onClick = onSkip,
        kind = CosmoButtonKind.PRIMARY,
        size = CosmoButtonSize.SMALL,
        floating = true,
        modifier = modifier
            .padding(end = SKIP_END_DP.dp)
            .drawWithContent {
                drawContent()
                playhead.frame.longValue   // a new frame: draw again
                val left = (segment.endMs - playback.positionMs()).coerceIn(0, span).toFloat() / span
                val pad = ControlMetrics.buttonPaddingDp(VideoMetrics.SKIP_BUTTON_H_DP).dp.toPx()
                val y = size.height / 2f + SKIP_LINE_BELOW_CENTRE_DP.dp.toPx()
                val full = size.width - 2 * pad
                if (full > 0f && left > 0f) {
                    drawLine(
                        color = colors.live(CoverField.TEXT_PRIMARY),
                        start = Offset(pad, y),
                        end = Offset(pad + full * left, y),
                        strokeWidth = SKIP_LINE_DP.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            },
    )
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
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val reduced = LocalReducedMotion.current
    val message = stringResource(Res.string.video_skipped, stringResource(segmentName(skipped.category)))
    val style = remember(type, message) {
        type.forText(TextStyle(fontFamily = type.textFamily, fontWeight = FontWeight(TOAST_WEIGHT), fontSize = TOAST_SP.sp, lineHeight = TOAST_SP.sp * 1.3f), message)
    }
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
            .cosmoGlass(hazeState = null, shape = TOAST_SHAPE, overVideo = true)
            .heightIn(min = TOAST_MIN_H_DP.dp)
            .padding(start = TOAST_PAD_START_DP.dp, top = TOAST_PAD_DP.dp, end = TOAST_PAD_DP.dp, bottom = TOAST_PAD_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = message, style = style, color = Color(palette.textPrimary), modifier = Modifier.weight(1f))
        CosmoButton(
            text = stringResource(Res.string.video_undo),
            onClick = { shown = null; onUndo() },
            kind = CosmoButtonKind.TEXT,
            size = CosmoButtonSize.SMALL,
            floating = true,
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
