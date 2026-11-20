package com.cosmotune.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitSongRow
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook

/**
 * Look and colours, Row size (the Music board's 8b): Comfortable (60 dp rows, the default) or Compact (52 dp), as a segmented choice
 * stacked under its row, and two rows under that drawn at the chosen size, so the choice shows itself. Every list of the app follows.
 */
@Composable
fun CosmoRowSizeSetting(modifier: Modifier = Modifier) {
    val settings = rememberCosmoLookSettings()
    val compact by settings.compactRows.collectAsState()
    Column(modifier.fillMaxWidth()) {
        CosmoSettingsRow(icon = CosmoIcons.List, title = "Row size", subtitle = "Comfortable 60 dp · Compact 52 dp")
        CosmoSettingsSegmented(
            options = listOf("Comfortable", "Compact"),
            selectedIndex = if (compact) 1 else 0,
            onSelect = { settings.setCompactRows(it == 1) },
            modifier = Modifier.padding(start = CosmoSettingsInsets.textStart, end = CosmoSettingsInsets.side, bottom = 8.dp),
            compact = true,
        )
        val fonts = orbitFonts()
        val look = orbitLook()
        Column(Modifier.padding(start = CosmoSettingsInsets.side, end = CosmoSettingsInsets.side, bottom = 8.dp)) {
            OrbitSongRow("Song title", "Artist name · 3:42", null, fonts, look, onClick = null, modifier = Modifier.fillMaxWidth(), icon = CosmoIcons.Waveform)
            OrbitSongRow("Song title", "Artist name · 4:05", null, fonts, look, onClick = null, modifier = Modifier.fillMaxWidth(), icon = CosmoIcons.Waveform, now = true)
        }
    }
}
