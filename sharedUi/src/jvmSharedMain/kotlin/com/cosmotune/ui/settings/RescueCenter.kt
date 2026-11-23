package com.cosmotune.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cosmotune.diagnostics.CrashNotes
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook

/**
 * Rescue (RiMusic and Kreate's Rescue Center): for when Cosmotune will not open or keeps stopping. Reached without the main screens,
 * from the phone's launcher shortcut or, on the desktop, by itself after two starts that did not get going. Saves a backup, shows
 * the last crash note to copy, and opens Cosmotune.
 */
@Composable
fun RescueCenter(onSaveBackup: () -> Unit, status: String?, onOpenApp: () -> Unit, modifier: Modifier = Modifier) {
    val look = orbitLook()
    val fonts = orbitFonts()
    val note by CrashNotes.note.collectAsState()
    var noteOpen by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize().background(look.ground).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp).testTag("rescue")) {
        Text("Rescue", style = fonts.greeting, color = look.ink)
        Spacer(Modifier.height(8.dp))
        Text("For when Cosmotune will not open or keeps stopping. Save your settings and Replay first, then send the crash note with a report if you like.",
            style = fonts.body, color = look.dim)
        Spacer(Modifier.height(16.dp))
        CosmoSettingsRow(icon = CosmoIcons.Download, title = "Save a backup", subtitle = "Your settings and Replay, in a place you choose", onClick = onSaveBackup)
        CosmoSettingsRow(icon = CosmoIcons.Comments, title = "Last crash note",
            subtitle = if (note == null) "No crash has been recorded" else "See what went wrong and copy it", onClick = { noteOpen = true })
        CosmoSettingsRow(icon = CosmoIcons.Play, title = "Open Cosmotune", subtitle = "Start as usual", onClick = onOpenApp)
        if (status != null) {
            Spacer(Modifier.height(12.dp))
            Text(status, style = fonts.body, color = look.accent, modifier = Modifier.padding(horizontal = 16.dp).testTag("rescue:status"))
        }
    }
    if (noteOpen) CrashNoteDialog { noteOpen = false }
}
