package com.cosmotune.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cosmotune.settings.Extras
import com.cosmotune.settings.ExtrasSettings
import com.cosmotune.ui.components.CosmoIcons

/** Where "Add to playlist" puts a song: at the end (the default) or at the start, as a segmented choice under its row. */
@Composable
fun PlaylistAddPlaceRow(modifier: Modifier = Modifier, settings: ExtrasSettings = Extras.settings) {
    val atStart by settings.playlistAddToStart.collectAsState()
    Column(modifier.fillMaxWidth()) {
        CosmoSettingsRow(icon = CosmoIcons.List, title = TITLE, subtitle = LINE)
        CosmoSettingsSegmented(
            options = listOf("At the end", "At the start"),
            selectedIndex = if (atStart) 1 else 0,
            onSelect = { settings.setPlaylistAddToStart(it == 1) },
            modifier = Modifier.padding(start = CosmoSettingsInsets.textStart, end = CosmoSettingsInsets.side, bottom = 8.dp),
            compact = true,
        )
    }
}

private const val TITLE = "Add songs to playlists"
private const val LINE = "Where a song you add to a playlist goes."
