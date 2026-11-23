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
import com.cosmotune.podio.PodioShowRules
import com.cosmotune.ui.components.CosmoIcons
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
    onShareOpmlFile: (suspend (String) -> Unit)? = null, onRefreshChange: (Boolean) -> Unit = {},
    /** Whether the phone lets the app set exact alarms (the radio alarm), and the way to its page when not. */
    exactAlarms: Boolean = true, onAllowExactAlarms: (() -> Unit)? = null,
    /** The phone takes requests from automation apps ([com.cosmotune.podio.PodioAutomation]); the desktop does not. */
    automation: Boolean = false) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val library = remember(storage) { PodioLibrary(storage) }
    val saved by library.state.collectAsUiState()
    val scope = rememberUiCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var message by remember { mutableStateOf<String?>(null) }
    var pasting by remember { mutableStateOf(false) }
    var choosingShow by remember { mutableStateOf(false) }
    var settingsFor by remember { mutableStateOf<com.cosmotune.podio.PodcastShow?>(null) }
    fun save(change: (com.cosmotune.podio.PodioSaved) -> com.cosmotune.podio.PodioSaved) = scope.launch {
        try { withContext(Dispatchers.IO) { library.update(change) } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { message = "Couldn't save the Podio settings. Check available storage and retry." }
    }
    fun import(text: String) = scope.launch {
        if (saved.followLock != null) { message = "Following new shows is locked. Turn the lock off below to import."; pasting = false; return@launch }
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
        OrbitLabel("Downloads", fonts, look.dim, Modifier.padding(top = 8.dp))
        OrbitSwitchRow("Remove played downloads", saved.deletePlayedDownloads, { on -> save { it.copy(deletePlayedDownloads = on) } },
            detail = "Once an episode is played to its end", plain = true, switchModifier = Modifier.testTag("settings:podio:delete-played"))
        Text("Downloads to keep per show", style = fonts.body, color = look.ink)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (count in PodioShowRules.KEEP_CHOICES) OrbitSelectChip(keepLength(count), saved.keepDownloadsPerShow == count, fonts, look,
                { save { it.copy(keepDownloadsPerShow = count) } }, haptic = false)
        }
        Text("Older ones go by themselves; episodes you have started always stay.", style = fonts.body.copy(fontSize = 13.sp), color = look.dim)
        OrbitSwitchRow("Keep episodes as they play", saved.keepStreamed, { on -> save { it.copy(keepStreamed = on) } },
            detail = "An episode you stream is saved whole, so it plays on without a connection", plain = true,
            switchModifier = Modifier.testTag("settings:podio:keep-streamed"))
        OrbitLabel("Playback", fonts, look.dim, Modifier.padding(top = 8.dp))
        OrbitSwitchRow("Voice boost", saved.voiceBoost, { on -> save { it.copy(voiceBoost = on) } },
            detail = "Louder, clearer speech for quiet podcasts", plain = true, switchModifier = Modifier.testTag("settings:podio:voice-boost"))
        OrbitSwitchRow("Headphones' Next button adds a bookmark", saved.headsetBookmarks, { on -> save { it.copy(headsetBookmarks = on) } },
            detail = "While an episode plays on the phone, instead of skipping it", plain = true, switchModifier = Modifier.testTag("settings:podio:headset-bookmarks"))
        OrbitLabel("Radio recordings", fonts, look.dim, Modifier.padding(top = 8.dp))
        OrbitSwitchRow("Save each song as its own file", saved.recordBySong, { on -> save { it.copy(recordBySong = on) } },
            detail = "When the station names its songs; up to three hours a recording", plain = true, switchModifier = Modifier.testTag("settings:podio:record-by-song"))
        OrbitSwitchRow("Scrobble the songs stations play", saved.scrobbleRadio, { on -> save { it.copy(scrobbleRadio = on) } },
            detail = "To Last.fm or ListenBrainz, once you have signed in to one; podcasts never are", plain = true,
            switchModifier = Modifier.testTag("settings:podio:scrobble-radio"))
        PodioAlarmSettings(saved, { change -> save(change) }, exactAlarms, onAllowExactAlarms)
        PodioFollowLockSettings(saved) { change -> save(change) }
        if (automation) {
            OrbitLabel("Automation", fonts, look.dim, Modifier.padding(top = 8.dp))
            Text("Apps such as Tasker can play a saved station by its name, go on with what you were listening to, or pause. " +
                "Send a broadcast to Cosmotune: ${com.cosmotune.podio.PodioAutomation.PLAY_STATION} with \"name\", " +
                "${com.cosmotune.podio.PodioAutomation.PLAY_FOR_YOU} or ${com.cosmotune.podio.PodioAutomation.PAUSE}.",
                style = fonts.body.copy(fontSize = 13.sp), color = look.dim, modifier = Modifier.testTag("settings:podio:automation"))
        }
        OrbitLabel("Each show", fonts, look.dim, Modifier.padding(top = 8.dp))
        val custom = saved.shows.count { it.id in saved.showSettings || it.id in saved.skipSilenceByShow || it.id in saved.speedByShow }
        Text(if (custom == 0) "Every show follows these settings." else "$custom ${if (custom == 1) "show has" else "shows have"} its own settings.",
            style = fonts.body.copy(fontSize = 13.sp), color = look.dim)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OrbitSelectChip("Choose a show", false, fonts, look, { if (hasShows) choosingShow = true }, Modifier.alpha(if (hasShows) 1f else 0.45f), haptic = false,
                icon = CosmoIcons.Settings)
            if (saved.speedByShow.isNotEmpty()) OrbitSelectChip("Play every show at 1×", false, fonts, look, { save { it.copy(speedByShow = emptyMap()) } }, haptic = false)
        }
        OrbitLabel("Subscriptions", fonts, look.dim, Modifier.padding(top = 8.dp))
        Text(if (hasShows) "OPML keeps your follows; bring them to another podcast app or back." else "Bring your shows from another podcast app.",
            style = fonts.body.copy(fontSize = 13.sp), color = look.dim)
        Text("With Sync on, your shows, saved stations and where you left each episode follow you to your other devices.",
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
                val text = com.cosmotune.podio.PodioTags.opml(saved)
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
                    try { onShareOpmlFile(com.cosmotune.podio.PodioTags.opml(saved)) }
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
    if (choosingShow && hasShows) ShowPickerDialog(saved, onDismiss = { choosingShow = false }) { show -> choosingShow = false; settingsFor = show }
    settingsFor?.let { show -> ShowSettingsSheet(show, saved, { change -> save(change) }) { settingsFor = null } }
}

/** The followed shows, each saying whether it has its own settings; a tap opens its settings sheet. */
@Composable
private fun ShowPickerDialog(saved: com.cosmotune.podio.PodioSaved, onDismiss: () -> Unit, onPick: (com.cosmotune.podio.PodcastShow) -> Unit) {
    val fonts = orbitFonts()
    val look = orbitLook()
    com.cosmotune.ui.sheets.CosmoAlertDialog(
        onDismissRequest = onDismiss, title = "Show settings", scroll = false,
        content = {
            LazyColumn(Modifier.heightIn(max = 420.dp).testTag("settings:podio:shows")) {
                items(saved.shows, key = { it.id }) { show ->
                    val own = show.id in saved.showSettings || show.id in saved.skipSilenceByShow || show.id in saved.speedByShow
                    OrbitSongRow(show.title.ifBlank { "Podcast" }, PodioFormat.episodeMeta(if (own) "Own settings" else "App settings",
                        saved.speedByShow[show.id]?.let { PodioFormat.speed(it) }.orEmpty()), show.artwork.takeIf(String::isNotBlank), fonts, look, { onPick(show) },
                        Modifier.fillMaxWidth())
                }
            }
        },
        confirmLabel = "Done", onConfirm = onDismiss,
    )
}
