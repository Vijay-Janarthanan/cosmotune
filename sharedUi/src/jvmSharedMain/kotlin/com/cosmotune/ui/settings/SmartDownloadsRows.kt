package com.cosmotune.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cosmotune.settings.CosmoLookSettings
import com.cosmotune.ui.orbit.OrbitSelectChip
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.pages.SmartDownloads

/** The setting's name, for both hosts' settings search. */
const val SMART_DOWNLOADS_TITLE = "Smart downloads"
const val SMART_DOWNLOADS_LINE = "Keep your most played songs downloaded"

/** Smart downloads in a host's Downloads settings: Off, 25, 50 or 100 of the most played songs kept on the device. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SmartDownloadsRows(look: CosmoLookSettings) {
    val fonts = orbitFonts()
    val ink = orbitLook()
    val count by look.smartDownloads.collectAsState()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(SMART_DOWNLOADS_TITLE, style = fonts.title, color = ink.ink)
        Text(
            if (count <= 0) "$SMART_DOWNLOADS_LINE. They download on Wi-Fi once a day."
            else "Your $count most played songs stay downloaded. Songs that drop out are removed again.",
            style = fonts.body, color = ink.dim,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SmartDownloads.COUNTS.forEach { option ->
                OrbitSelectChip(if (option == 0) "Off" else "$option songs", count == option, fonts, ink, onClick = { look.setSmartDownloads(option) }, haptic = false)
            }
        }
    }
}
