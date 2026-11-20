@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.cosmotune.podio.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.podio.PodioLibrary
import com.cosmotune.podio.PodioOpml
import com.cosmotune.ui.collectAsUiState
import com.cosmotune.ui.orbit.*
import com.cosmotune.ui.rememberUiCoroutineScope
import com.cosmotune.ui.theme.LocalCosmoColors
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings › Podio (board frame 15), the settings that used to sit at the foot of Following: new episodes (the background refresh,
 * downloading new episodes, how many per show Following lists), playback (skip silence and speed of each followed show) and the
 * subscriptions (import, paste, export and share OPML). [storage] is Podio's library file; the file hooks are the host's native
 * dialogs, null where it has none (the paste dialog and the clipboard stand in). [onRefreshChange] lets the host schedule or stop
 * its background work when the refresh is turned on or off.
 */
@Composable
fun PodioSettingsSection(storage: File, modifier: Modifier = Modifier,
    onImportOpmlFile: (suspend () -> String?)? = null, onExportOpmlFile: (suspend (String) -> Boolean)? = null,
    onShareOpmlFile: (suspend (String) -> Unit)? = null, onRefreshChange: (Boolean) -> Unit = {}) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val library = remember(storage) { PodioLibrary(storage) }
    val saved by library.state.collectAsUiState()
    val scope = rememberUiCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var message by remember { mutableStateOf<String?>(null) }
    var pasting by remember { mutableStateOf(false) }
    var editingShows by remember { mutableStateOf(false) }
    fun save(change: (com.cosmotune.podio.PodioSaved) -> com.cosmotune.podio.PodioSaved) = scope.launch {
        try { withContext(Dispatchers.IO) { library.update(change) } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { message = "Couldn't save the Podio settings. Check available storage and retry." }
    }
    fun import(text: String) = scope.launch {
        try { val added = withContext(Dispatchers.IO) { library.importOpml(text) }; message = "Imported $added new podcast subscriptions."; pasting = false }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { message = e.message?.take(180) ?: "Couldn't import this OPML document." }
    }
    val hasShows = saved.shows.isNotEmpty()
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).testTag("settings:podio"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        message?.let { Text(it, style = fonts.body, color = look.dim) }
        OrbitLabel("New episodes", fonts, look.dim)
        OrbitSwitchRow("Check followed shows in the background", saved.refreshInBackground, { on -> save { it.copy(refreshInBackground = on) }; onRefreshChange(on) },
            detail = saved.lastRefreshMs.takeIf { it > 0 }?.let { "Says when there are new episodes · checked " + PodioFormat.ago(it) } ?: "Says when there are new episodes",
            plain = true, switchModifier = Modifier.testTag("settings:podio:refresh"))
        OrbitSwitchRow("Download new episodes", saved.autoDownloadNew, { on -> save { it.copy(autoDownloadNew = on) } },
            detail = "As the background check finds them, up to five at a time", enabled = saved.refreshInBackground, plain = true,
            switchModifier = Modifier.testTag("settings:podio:auto-download"))
        Text("Latest episodes per show in Following", style = fonts.body, color = look.ink)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (limit in listOf(6, 12, 24)) OrbitSelectChip("$limit episodes", saved.inboxEpisodesPerShow == limit, fonts, look,
                { save { it.copy(inboxEpisodesPerShow = limit) } }, haptic = false)
        }
        OrbitLabel("Playback", fonts, look.dim, Modifier.padding(top = 8.dp))
        val skipping = saved.skipSilenceByShow.count { it.value }
        val speeds = saved.speedByShow.size
        Text(PodioFormat.episodeMeta(if (skipping == 0) "" else "Skip silence for $skipping ${if (skipping == 1) "show" else "shows"}",
            if (speeds == 0) "" else "$speeds ${if (speeds == 1) "show has" else "shows have"} its own speed").ifBlank { "Every show plays at the app's own settings." },
            style = fonts.body.copy(fontSize = 13.sp), color = look.dim)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OrbitSelectChip("Choose for each show", false, fonts, look, { editingShows = true }, Modifier.alpha(if (hasShows) 1f else 0.45f), haptic = false)
            if (speeds > 0) OrbitSelectChip("Play every show at 1×", false, fonts, look, { save { it.copy(speedByShow = emptyMap()) } }, haptic = false)
        }
        OrbitLabel("Subscriptions", fonts, look.dim, Modifier.padding(top = 8.dp))
        Text(if (hasShows) "OPML keeps your follows; bring them to another podcast app or back." else "Bring your shows from another podcast app.",
            style = fonts.body.copy(fontSize = 13.sp), color = look.dim)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OrbitSelectChip("Import OPML", false, fonts, look, {
                val pick = onImportOpmlFile
                if (pick == null) pasting = true else scope.launch {
                    try { pick()?.let { import(it) } }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { message = "Couldn't read that OPML file. Please retry." }
                }
            }, icon = Icons.Outlined.FileDownload, haptic = false, modifier = Modifier.testTag("settings:podio:import"))
            if (onImportOpmlFile != null) OrbitSelectChip("Paste OPML", false, fonts, look, { pasting = true }, icon = Icons.Outlined.ContentPaste, haptic = false)
            OrbitSelectChip("Export OPML", false, fonts, look, {
                if (!hasShows) return@OrbitSelectChip
                val text = PodioOpml.encode(saved.shows)
                val export = onExportOpmlFile
                if (export == null) { clipboard.setText(AnnotatedString(text)); message = "Copied ${saved.shows.size} podcast subscriptions as OPML." }
                else scope.launch {
                    try { if (export(text)) message = "Saved podcast subscriptions as OPML." }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { message = "Couldn't save the OPML file. Please retry." }
                }
            }, Modifier.alpha(if (hasShows) 1f else 0.45f), icon = Icons.Outlined.FileUpload, haptic = false)
            if (onShareOpmlFile != null) OrbitSelectChip("Share OPML", false, fonts, look, {
                if (hasShows) scope.launch {
                    try { onShareOpmlFile(PodioOpml.encode(saved.shows)) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { message = "Couldn't share the OPML file. Please retry." }
                }
            }, Modifier.alpha(if (hasShows) 1f else 0.45f), icon = Icons.Outlined.RssFeed, haptic = false)
        }
    }
    if (pasting) {
        var text by remember { mutableStateOf("") }
        com.cosmotune.ui.sheets.CosmoAlertDialog(
            onDismissRequest = { pasting = false },
            title = "Import podcast subscriptions",
            content = { Column {
                Text("Paste an OPML export from your podcast player. Existing follows and listening positions are kept.")
                OrbitInputField(text, { text = it }, "OPML document", singleLine = false, minLines = 5,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).padding(top = 12.dp))
            } },
            confirmLabel = "Import subscriptions", onConfirm = { import(text) }, confirmEnabled = text.isNotBlank(),
            dismissLabel = "Cancel", onDismiss = { pasting = false },
        )
    }
    if (editingShows && hasShows) ShowPlaybackDialog(saved, onDismiss = { editingShows = false }) { shows, choice ->
        save { old -> old.copy(skipSilenceByShow = if (choice == null) old.skipSilenceByShow - shows else old.skipSilenceByShow + shows.associateWith { choice }) }
        editingShows = false
    }
}

/** Skip silence for the chosen shows: skip, keep, or the app's default; each show says what it has now and its own speed. */
@Composable
private fun ShowPlaybackDialog(saved: com.cosmotune.podio.PodioSaved, onDismiss: () -> Unit, onApply: (Set<String>, Boolean?) -> Unit) {
    val fonts = orbitFonts()
    val look = orbitLook()
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var choice by remember { mutableStateOf<Boolean?>(true) }
    com.cosmotune.ui.sheets.CosmoAlertDialog(
        onDismissRequest = onDismiss, title = "Podcast playback", scroll = false,
        content = {
            Column {
                OrbitChipRow(0.dp) {
                    OrbitSelectChip("Skip silence", choice == true, fonts, look, { choice = true }, haptic = false)
                    OrbitSelectChip("Keep silence", choice == false, fonts, look, { choice = false }, haptic = false)
                    OrbitSelectChip("App default", choice == null, fonts, look, { choice = null }, haptic = false)
                }
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(saved.shows, key = { it.id }) { show ->
                        OrbitSwitchRow(show.title, show.id in selected, { checked -> selected = if (checked) selected + show.id else selected - show.id },
                            detail = PodioFormat.episodeMeta(when (saved.skipSilenceByShow[show.id]) { true -> "Skip silence"; false -> "Keep silence"; null -> "App default" },
                                saved.speedByShow[show.id]?.let { PodioFormat.speed(it) }.orEmpty()))
                    }
                }
            }
        },
        confirmLabel = "Apply", confirmEnabled = selected.isNotEmpty(), onConfirm = { onApply(selected, choice) },
        dismissLabel = "Cancel", onDismiss = onDismiss,
    )
}
