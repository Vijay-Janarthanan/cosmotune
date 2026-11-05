package com.cosmotune.ui.nowplaying

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
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
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType

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
