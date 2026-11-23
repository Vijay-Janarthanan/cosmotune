package com.cosmotune.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.orbit.OrbitSwitchRow
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook

const val HIDE_EXPLICIT_TITLE = "Hide explicit songs"
const val HIDE_EXPLICIT_LINE = "AutoPlay, radio and Home leave out songs marked explicit"
const val BLOCKED_ARTISTS_TITLE = "Blocked artists"
const val BLOCKED_ARTISTS_LINE = "Artists you chose not to hear: AutoPlay, radio and Home leave them out"

/** The explicit switch and the artists blocked from a song's menu ("Don't play this artist"), each with a switch to let them back. */
@Composable
fun MusicBlocksRows() {
    val look = rememberCosmoLookSettings()
    val fonts = orbitFonts()
    val ink = orbitLook()
    val hideExplicit by look.hideExplicit.collectAsState()
    val blocked by look.blockedArtists.collectAsState()
    OrbitSwitchRow(
        title = HIDE_EXPLICIT_TITLE,
        detail = HIDE_EXPLICIT_LINE,
        checked = hideExplicit,
        onChange = look::setHideExplicit,
        description = HIDE_EXPLICIT_TITLE,
    )
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(BLOCKED_ARTISTS_TITLE, style = fonts.title, color = ink.ink)
        Text(if (blocked.isEmpty()) "None yet. Choose Don't play this artist in a song's menu." else BLOCKED_ARTISTS_LINE, style = fonts.body, color = ink.dim)
        blocked.sorted().forEach { name ->
            OrbitSwitchRow(
                title = name,
                detail = "Blocked",
                checked = true,
                onChange = { keep -> if (!keep) look.setArtistBlocked(name, false) },
                description = "Keep $name blocked",
            )
        }
    }
}
