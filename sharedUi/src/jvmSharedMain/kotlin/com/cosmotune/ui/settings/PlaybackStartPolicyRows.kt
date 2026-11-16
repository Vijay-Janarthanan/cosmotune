package com.cosmotune.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cosmotune.audio.PlaybackStartPolicy
import com.cosmotune.ui.orbit.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaybackStartPolicyRows(value: PlaybackStartPolicy, onChange: (PlaybackStartPolicy) -> Unit) {
    val fonts = orbitFonts(); val look = orbitLook()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(SoundExtrasText.PLAYBACK_START, style = fonts.title, color = look.ink)
        Text(value.description, style = fonts.body, color = look.dim)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PlaybackStartPolicy.entries.forEach { policy ->
                OrbitSelectChip(policy.label, value == policy, fonts, look, onClick = { onChange(policy) }, haptic = false)
            }
        }
    }
}
