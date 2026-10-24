package com.cosmotune.ui.nowplaying

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
internal fun AudioFormatLine(modifier: Modifier = Modifier) {
    val stats by NerdStats.current.collectAsStateWithLifecycle()
    val label = stats?.audioFormatLabel() ?: return
    val type = LocalCosmoType.current
    Text(label, modifier, style = TextStyle(fontFamily = type.textFamily, fontSize = 11.sp, lineHeight = 14.sp),
        color = Color(LocalCosmoColors.current.palette.textSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
}
