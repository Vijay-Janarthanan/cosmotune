package com.cosmotune.ui.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.music.data.NerdStats
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.high_quality
import com.cosmotune.music.sharedui.resources.hi_res_lossless
import com.cosmotune.music.sharedui.resources.lossless
import com.cosmotune.music.ui.components.SourceQuality
import org.jetbrains.compose.resources.stringResource

/**
 * The tier of what the decoder is actually being fed, or null when it is not worth a label: Dolby
 * Atmos, then lossless past CD quality (past 16-bit or 48 kHz) as Hi-Res, then lossless, then lossy
 * at 256 kbps or more. Read from the measured stream, never from what a source said it offered.
 */
fun NerdStats.Snapshot.qualityTier(): SourceQuality? = when {
    isDolbyAtmos -> SourceQuality.DOLBY_ATMOS
    isHiRes -> SourceQuality.HI_RES_LOSSLESS
    isLossless -> SourceQuality.LOSSLESS
    isHiQuality -> SourceQuality.HIGH_QUALITY
    else -> null
}

/** A small tinted pill naming a tier: violet for Hi-Res, green for lossless, blue for high quality lossy. */
@Composable
fun QualityPill(quality: SourceQuality, modifier: Modifier = Modifier) {
    val (fill, ink) = when (quality) {
        SourceQuality.HI_RES_LOSSLESS -> Color(0xFF8E51FF) to Color(0xFFC4B4FF)
        SourceQuality.LOSSLESS -> Color(0xFF00C950) to Color(0xFF7BF1A8)
        SourceQuality.HIGH_QUALITY -> Color(0xFF00A6F4) to Color(0xFF74D4FF)
        SourceQuality.DOLBY_ATMOS -> Color(0xFFFFFFFF) to Color(0xFFE6E6E6)
    }
    val shape = RoundedCornerShape(percent = 50)
    Text(
        text = when (quality) {
            SourceQuality.HI_RES_LOSSLESS -> stringResource(Res.string.hi_res_lossless)
            SourceQuality.LOSSLESS -> stringResource(Res.string.lossless)
            SourceQuality.HIGH_QUALITY -> stringResource(Res.string.high_quality)
            SourceQuality.DOLBY_ATMOS -> "Dolby Atmos"
        },
        modifier = modifier.clip(shape).background(fill.copy(alpha = 0.15f)).border(1.dp, fill.copy(alpha = 0.32f), shape)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        color = ink,
        fontSize = 10.sp,
        lineHeight = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.4.sp,
        maxLines = 1,
        softWrap = false,
    )
}
