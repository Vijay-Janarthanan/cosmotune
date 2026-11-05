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
import androidx.compose.ui.unit.em
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
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

/**
 * A small pill naming a tier, for callers without the Cosmo theme (it reads no theme local): neutral, white ink on a faint wash,
 * the highest tiers outlined.
 */
@Composable
fun QualityPill(quality: SourceQuality, modifier: Modifier = Modifier) {
    val top = quality == SourceQuality.HI_RES_LOSSLESS || quality == SourceQuality.DOLBY_ATMOS
    val shape = RoundedCornerShape(percent = 50)
    Text(
        text = when (quality) {
            SourceQuality.HI_RES_LOSSLESS -> stringResource(Res.string.hi_res_lossless)
            SourceQuality.LOSSLESS -> stringResource(Res.string.lossless)
            SourceQuality.HIGH_QUALITY -> stringResource(Res.string.high_quality)
            SourceQuality.DOLBY_ATMOS -> "Dolby Atmos"
        },
        modifier = modifier.clip(shape).background(Color.White.copy(alpha = 0.08f))
            .then(if (top) Modifier.border(1.dp, Color.White.copy(alpha = 0.16f), shape) else Modifier)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        color = if (top) Color(0xFFF2F2F3) else Color(0xFFB4B4BB),
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        softWrap = false,
    )
}

/**
 * The pill of a [badge] (Lokal-style: the tier's colour, the full resolution as text). [compact] is the song row's size (10 sp,
 * 18 dp tall); the default is Now Playing's and the player bar's (11.5 sp, 22 dp). A measured pill is filled, a catalogue one only
 * outlined, so the two cannot be taken for each other; both say where they come from to a screen reader. With [onClick] the pill is
 * a button (Now Playing's opens the signal path).
 */
@Composable
fun QualityPill(badge: QualityBadge, modifier: Modifier = Modifier, compact: Boolean = false, onClick: (() -> Unit)? = null) {
    val dark = LocalCosmoColors.current.dark
    val colours = remember(badge.tone, badge.evidence, dark) { badge.tone.colours(dark, badge.evidence) }
    val edge = Color(colours.edge)
    val shape = RoundedCornerShape(percent = 50)
    val type = LocalCosmoType.current
    val size = if (compact) 11.sp else 12.sp
    val tap = if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = "Signal path", onClick = onClick) else Modifier
    Text(
        text = badge.text,
        modifier = modifier
            .heightIn(min = if (compact) 18.dp else 22.dp)
            .clip(shape)
            .then(tap)
            .background(Color(colours.fill))
            .then(if (colours.edge ushr 24 != 0) Modifier.border(1.dp, edge, shape) else Modifier)
            .semantics { contentDescription = badge.spoken }
            .padding(horizontal = if (compact) 6.dp else 9.dp, vertical = if (compact) 2.dp else 3.dp),
        color = Color(colours.ink),
        style = TextStyle(fontFamily = type.textFamily, fontSize = size, lineHeight = size * 1.2f, fontWeight = FontWeight(650), letterSpacing = 0.02.em),
        maxLines = 1,
        softWrap = false,
    )
}
