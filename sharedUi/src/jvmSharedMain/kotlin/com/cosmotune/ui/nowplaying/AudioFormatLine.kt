package com.cosmotune.ui.nowplaying

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cosmotune.music.data.NerdStats
import com.cosmotune.music.data.audioFormatLabel
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.audio_pipeline
import com.cosmotune.music.sharedui.resources.hi_res_lossless
import com.cosmotune.music.sharedui.resources.lossless
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import org.jetbrains.compose.resources.stringResource

/**
 * Basic stream information, always visible: the measured quality pill ("FLAC · 24/96", "AAC 256", "Dolby Atmos": what the decoder is
 * fed, never what a source promised), then the numbers behind it. A tap opens the audio details, whose first section is the signal
 * path (source, decoder, processing, output); the optional nerd panel remains more detailed.
 */
@Composable
fun AudioFormatLine(modifier: Modifier = Modifier, onClick: (() -> Unit)? = LocalOpenAudioDetails.current) {
    val stats by NerdStats.current.collectAsStateWithLifecycle()
    val label = stats?.audioFormatLabel() ?: "Audio details"
    val badge = stats?.measuredQualityBadge()
    val type = LocalCosmoType.current
    Row(
        modifier.heightIn(min = 32.dp).clickable(enabled = onClick != null, role = Role.Button, onClickLabel = "Audio details", onClick = { onClick?.invoke() }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (badge != null) {
            QualityPill(badge)
            Spacer(Modifier.width(8.dp))
        }
        Text(label, style = type.caption,
            color = Color(LocalCosmoColors.current.palette.textSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The portrait record's compact entry point into the actual playing signal path. */
@Composable
internal fun RecordAudioDetails(mediaId: String, fonts: OrbitFonts, look: OrbitLook, onClick: () -> Unit) {
    val stats by NerdStats.current.collectAsStateWithLifecycle()
    val playing = stats?.takeIf { it.mediaId == mediaId } ?: return
    val format = playing.audioFormatLabel() ?: return
    val tier = when {
        playing.isDolbyAtmos -> "Dolby Atmos"
        playing.isHiRes -> stringResource(Res.string.hi_res_lossless)
        playing.isLossless -> stringResource(Res.string.lossless)
        else -> null
    }
    Row(
        Modifier.heightIn(min = 44.dp).clip(CircleShape).background(look.chip)
            .clickable(role = Role.Button, onClickLabel = stringResource(Res.string.audio_pipeline), onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(CosmoIcons.Equaliser, contentDescription = null, tint = look.accent, modifier = Modifier.size(16.dp))
        if (tier != null) Text(tier, style = fonts.title, color = look.ink, maxLines = 1)
        Text(format, style = fonts.body, color = look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false))
        Icon(CosmoIcons.ChevronRight, contentDescription = null, tint = look.dim, modifier = Modifier.size(14.dp))
    }
}

/**
 * The measured pill alone, for the mini player and the player bar: nothing while the decoder has measured nothing, so a tier is
 * never claimed for a stream that has not started. With [notableOnly] (the default) an ordinary lossy stream shows nothing either:
 * the small surfaces name Atmos, Hi-Res, lossless and high-bitrate streams, Now Playing names every one.
 */
@Composable
fun PlayingQualityPill(modifier: Modifier = Modifier, compact: Boolean = true, notableOnly: Boolean = true) {
    val stats by NerdStats.current.collectAsStateWithLifecycle()
    val badge = stats?.measuredQualityBadge() ?: return
    if (notableOnly && (badge.tone == QualityTone.STANDARD || badge.tone == QualityTone.LOW)) return
    QualityPill(badge, modifier, compact = compact)
}
