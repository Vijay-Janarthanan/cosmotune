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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cosmotune.listening.LocalLibraryRules
import com.cosmotune.settings.CosmoLookSettings
import com.cosmotune.ui.orbit.OrbitActionPill
import com.cosmotune.ui.orbit.OrbitSelectChip
import com.cosmotune.ui.orbit.OrbitSwitchRow
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook

const val LOCAL_SHORT_SONGS_TITLE = "Hide short songs"
const val LOCAL_SHORT_SONGS_LINE = "Voice notes, ringtones and intros stay out of your songs"
const val LOCAL_FOLDERS_TITLE = "Folders"
const val LOCAL_FOLDERS_LINE = "Choose which folders' songs show in your library"

/** How many folders show before "Show all". */
private const val FOLDERS_SHOWN = 8

/** Local music's "Hide short songs": Off, 30 seconds, 1 minute or 2 minutes. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LocalShortSongsRows(look: CosmoLookSettings) {
    val fonts = orbitFonts()
    val ink = orbitLook()
    val rules by look.localRules.collectAsState()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(LOCAL_SHORT_SONGS_TITLE, style = fonts.title, color = ink.ink)
        Text(LOCAL_SHORT_SONGS_LINE, style = fonts.body, color = ink.dim)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LocalLibraryRules.MIN_CHOICES.forEach { seconds ->
                val label = when {
                    seconds == 0 -> "Off"
                    seconds < 60 -> "Under $seconds s"
                    else -> "Under ${seconds / 60} min"
                }
                OrbitSelectChip(label, rules.minSeconds == seconds, fonts, ink, onClick = { look.setLocalMinSeconds(seconds) }, haptic = false)
            }
        }
    }
}

/**
 * Local music's folders: each folder the device's songs come from (and each one already hidden), with a switch to show or hide its
 * songs. [folders] is every folder the last scan found, hidden ones included.
 */
@Composable
fun LocalFoldersRows(look: CosmoLookSettings, folders: List<String>) {
    val fonts = orbitFonts()
    val ink = orbitLook()
    val rules by look.localRules.collectAsState()
    val all = (folders + rules.hiddenFolders).distinct().sorted()
    var showAll by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(LOCAL_FOLDERS_TITLE, style = fonts.title, color = ink.ink)
        Text(if (all.isEmpty()) "Folders show here once your device's songs are found." else LOCAL_FOLDERS_LINE, style = fonts.body, color = ink.dim)
        (if (showAll) all else all.take(FOLDERS_SHOWN)).forEach { folder ->
            OrbitSwitchRow(
                title = LocalLibraryRules.label(folder),
                detail = folder,
                checked = folder !in rules.hiddenFolders,
                onChange = { shown -> look.setLocalFolderHidden(folder, !shown) },
                description = "Show songs from ${LocalLibraryRules.label(folder)}",
            )
        }
        if (all.size > FOLDERS_SHOWN) {
            OrbitActionPill(if (showAll) "Show fewer" else "Show all ${all.size}", { showAll = !showAll }, fonts, ink, Modifier.padding(top = 4.dp))
        }
    }
}
