package com.cosmotune.video.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.player.SubtitleCue
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * The subtitles' look (spec 6.5): Hanken Grotesk 500; black 60 % behind the text, radius 6, padding 2 8; at most 2 lines and 80 % of
 * the width. Without the background (the setting "Background behind the text" off) a black shadow keeps the white text readable.
 */
private const val SUB_WEIGHT = 500
private const val SUB_LINE = 1.3f
private const val SUB_BACKGROUND_ALPHA = 0.60f
private const val SUB_RADIUS_DP = 6f
private const val SUB_PAD_H_DP = 8f
private const val SUB_PAD_V_DP = 2f
private const val SUB_MAX_WIDTH = 0.80f
private const val SUB_MAX_LINES = 2
private const val SUB_SHADOW_BLUR = 6f

/**
 * The subtitles, drawn by the app from the engine's [cues] (spec 6.5): centred, 12 % of the box above its bottom, rising above the
 * bottom row while the chrome shows ([lift] is the chrome's visibility, 0..1, read in the layout phase; [liftDp] where the bottom row
 * ends); white Hanken Grotesk 500 at [fontSp] times the size setting [scale] (0.8, 1, 1.25), on black 60 % when [background].
 */
@Composable
internal fun BoxScope.SubtitleOverlay(
    cues: List<SubtitleCue>,
    fontSp: Float,
    scale: Float,
    background: Boolean,
    lift: () -> Float,
    liftDp: Dp,
) {
    if (cues.isEmpty()) return
    val type = LocalCosmoType.current
    val text = remember(cues) { cues.joinToString("\n") { it.text.trim() } }
    val size = fontSp * scale
    val style = remember(type, size, background, text) {
        type.forText(
            TextStyle(
                fontFamily = type.textFamily,
                fontWeight = FontWeight(SUB_WEIGHT),
                fontSize = size.sp,
                lineHeight = (size * SUB_LINE).sp,
                // The black of a media scrim, the documented exception, as a shadow when there is no background.
                shadow = if (background) null else Shadow(Color.Black, Offset.Zero, SUB_SHADOW_BLUR),
            ),
            text,
        )
    }
    val shape = remember { RoundedCornerShape(SUB_RADIUS_DP.dp) }
    BoxWithConstraints(Modifier.matchParentSize()) {
        val base = maxHeight * VideoMetrics.SUB_BOTTOM_FRACTION
        val raised = max(base.value, liftDp.value).dp
        Text(
            text = text,
            style = style,
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = SUB_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset { IntOffset(0, -(base + (raised - base) * lift().coerceIn(0f, 1f)).toPx().roundToInt()) }
                .widthIn(max = maxWidth * SUB_MAX_WIDTH)
                .then(if (background) Modifier.background(Color.Black.copy(alpha = SUB_BACKGROUND_ALPHA), shape) else Modifier)
                .padding(horizontal = SUB_PAD_H_DP.dp, vertical = SUB_PAD_V_DP.dp),
        )
    }
}
