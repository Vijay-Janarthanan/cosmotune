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

/** Basic stream information is always visible; the optional nerd panel remains more detailed. */
@Composable
fun AudioFormatLine(modifier: Modifier = Modifier, onClick: (() -> Unit)? = LocalOpenAudioDetails.current) {
    val stats by NerdStats.current.collectAsStateWithLifecycle()
    val label = stats?.audioFormatLabel() ?: "Audio details"
    val tier = stats?.qualityTier()
    val type = LocalCosmoType.current
    // The tier first, as a pill ("Hi-Res Lossless" while 24-bit or 96 kHz plays), then the numbers behind it.
    Row(
        modifier.heightIn(min = 32.dp).clickable(enabled = onClick != null, role = Role.Button, onClickLabel = "Audio details", onClick = { onClick?.invoke() }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tier != null) {
            QualityPill(tier)
            Spacer(Modifier.width(8.dp))
        }
        Text(label, style = TextStyle(fontFamily = type.textFamily, fontSize = 11.sp, lineHeight = 14.sp),
            color = Color(LocalCosmoColors.current.palette.textSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
