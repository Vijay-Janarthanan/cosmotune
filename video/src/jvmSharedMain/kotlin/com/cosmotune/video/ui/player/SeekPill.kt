package com.cosmotune.video.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.ui.components.cosmoGlass
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.tabular
import com.cosmotune.video.design.VideoMetrics

/** The seek pill and the chips (spec 6.5): padding 0 14, 13 sp 650. */
private const val PILL_PAD_DP = 14f
private const val PILL_SP = 13f
private const val PILL_WEIGHT = 650

/** A full pill. */
private val PILL_SHAPE = RoundedCornerShape(percent = 50)

/**
 * The double tap's pill (spec 6.5): a C glass pill 36 dp tall, padding 0 14, 13 sp 650, "-10 s" or "+30 s", in the half that was
 * tapped; the seconds add up over a run of taps.
 */
@Composable
internal fun SeekPill(text: String, modifier: Modifier = Modifier) = ChromePill(text, modifier, tabular = true)

/**
 * A C glass label over the video (the "Chips over video" of the B+C spec, with the seek pill's numbers): "2x speed", "Muted:
 * sponsor", "Zoomed to fill". Ink on the glass with no backdrop blur (nothing is blurred over the video), read out politely when it
 * appears.
 */
@Composable
internal fun ChromePill(text: String, modifier: Modifier = Modifier, tabular: Boolean = false) {
    val type = LocalCosmoType.current
    val palette = LocalCosmoColors.current.palette
    val style = remember(type, tabular, text) {
        val base = TextStyle(fontFamily = type.textFamily, fontWeight = FontWeight(PILL_WEIGHT), fontSize = PILL_SP.sp)
        type.forText(if (tabular) base.tabular() else base, text)
    }
    Box(
        modifier
            .semantics { liveRegion = LiveRegionMode.Polite }
            .height(VideoMetrics.SEEK_PILL_H_DP.dp)
            .cosmoGlass(hazeState = null, shape = PILL_SHAPE, overVideo = true)
            .padding(horizontal = PILL_PAD_DP.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = style, color = Color(palette.textPrimary), maxLines = 1)
    }
}
