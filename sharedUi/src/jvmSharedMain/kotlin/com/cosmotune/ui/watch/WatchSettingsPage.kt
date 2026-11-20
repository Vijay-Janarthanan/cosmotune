package com.cosmotune.ui.watch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLabel
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitPageHeading
import com.cosmotune.ui.orbit.OrbitSelectChip
import com.cosmotune.ui.orbit.OrbitSwitch
import com.cosmotune.ui.orbit.WatchCardChip
import com.cosmotune.ui.settings.CosmoDestructiveRow
import com.cosmotune.ui.settings.CosmoSettingsGroup
import com.cosmotune.ui.settings.CosmoSettingsRow
import com.cosmotune.watch.model.WatchProfile
import kotlinx.coroutines.launch

/** Which of the settings' small dialogs is open. */
private enum class WatchSettingsDialog { FOLDER, DENSITY, ADD_PROFILE, PIN, REMOVE_PROFILE, TRACKING, KEYS, DEBRID }

/**
 * Watch settings on the same rows Video's settings use ([CosmoSettingsGroup], [CosmoSettingsRow]): Sources and add-ons first
 * (Plugins, where new downloads are saved), then Profiles (one row per viewer, a tick on this one, Add, PIN, Delete), Display
 * (poster size, the anime skips) and Connected services (tracking, service keys, debrid). Each row says its current value; the
 * ones that need words typed open a small dialog. Reached from the profile menu on the phone and from the sidebar on the desktop.
 */
@Composable
internal fun WatchSettingsPage(fonts: OrbitFonts, look: OrbitLook, frame: WatchFrame) {
    val repository = watchRepository()
    val scope = rememberCoroutineScope()
    val downloads = LocalWatchDownloads.current
    val plugins by repository.plugins.collectAsState()
    val preferences by repository.preferences.collectAsState()
    val profiles by repository.profiles.collectAsState()
    val active by repository.activeProfile.collectAsState()
    val configured by repository.configuredServices.collectAsState()
    val destination = downloads?.destination?.collectAsState()?.value
    val trackers = repository.tracking?.statuses?.collectAsState()?.value
    var dialog by remember { mutableStateOf<WatchSettingsDialog?>(null) }
    var unlocking by remember { mutableStateOf<WatchProfile?>(null) }
    val side = frame.side
    LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxSize(), contentPadding = frame.padding) {
        frame.header?.let { header -> item(key = "settings:header") { header() } }
        item(key = "settings:heading") {
            val viewer = profiles.firstOrNull { it.id == active }?.name
            OrbitPageHeading("Watch settings", listOfNotNull("Watch", viewer).joinToString(" · "), fonts, look,
                Modifier.padding(horizontal = side + 6.dp).padding(top = if (frame.wide) 24.dp else 18.dp))
        }
        item(key = "settings:sources") {
            CosmoSettingsGroup(header = "Sources and add-ons") {
                val on = plugins.count { it.enabled }
                val yours = plugins.count { !it.builtIn }
                CosmoSettingsRow(Icons.Rounded.Extension, "Plugins", subtitle = listOfNotNull("$on on", yours.takeIf { it > 0 }?.let { "$it of yours" }, "find more").joinToString(" · "),
                    onClick = { WatchNav.go(WatchTab.PLUGINS) })
                if (downloads != null && destination != null) {
                    CosmoSettingsRow(Icons.Rounded.Folder, "Save downloads to", subtitle = destination.name.ifBlank { destination.path },
                        onClick = { dialog = WatchSettingsDialog.FOLDER })
                }
            }
        }
        item(key = "settings:profiles") {
            CosmoSettingsGroup(header = "Profiles", footer = "Each viewer has their own My list and Continue watching.") {
                profiles.forEach { profile ->
                    val here = profile.id == active
                    CosmoSettingsRow(
                        if (profile.locked) Icons.Rounded.Lock else Icons.Rounded.Person, profile.name,
                        subtitle = when { here -> "This profile"; profile.locked -> "Locked with a PIN"; else -> null },
                        onClick = if (here) null else ({
                            if (profile.locked) unlocking = profile else scope.launch { repository.selectProfile(profile.id) }
                        }),
                        trailing = if (here) ({ androidx.compose.material3.Icon(CosmoIcons.Check, null, tint = look.accent) }) else null,
                    )
                }
                if (profiles.size < 8) CosmoSettingsRow(CosmoIcons.Plus, "Add a profile", onClick = { dialog = WatchSettingsDialog.ADD_PROFILE })
                CosmoSettingsRow(Icons.Rounded.Key, "Profile PIN", subtitle = if (profiles.firstOrNull { it.id == active }?.locked == true) "On" else "Off",
                    onClick = { dialog = WatchSettingsDialog.PIN })
                if (active != "default") CosmoDestructiveRow("Delete this profile", onClick = { dialog = WatchSettingsDialog.REMOVE_PROFILE })
            }
        }
        item(key = "settings:display") {
            CosmoSettingsGroup(header = "Display") {
                CosmoSettingsRow(Icons.Rounded.GridView, "Poster size", subtitle = preferences.density.label, onClick = { dialog = WatchSettingsDialog.DENSITY })
                CosmoSettingsRow(Icons.Rounded.SkipNext, "Skip anime openings and endings", subtitle = "When the times are known",
                    trailing = {
                        OrbitSwitch(preferences.animeSkips, { on -> scope.launch { repository.setPreferences(preferences.copy(animeSkips = on)) } }, look,
                            "Skip anime openings and endings")
                    })
            }
        }
        item(key = "settings:services") {
            CosmoSettingsGroup(header = "Connected services") {
                if (repository.tracking != null) {
                    val connected = trackers.orEmpty().count { it.connected }
                    CosmoSettingsRow(Icons.Rounded.Link, "Tracking", subtitle = when (connected) {
                        0 -> "Trakt or Simkl, optional"; 1 -> "Connected to one service"; else -> "Connected to $connected services"
                    }, onClick = { dialog = WatchSettingsDialog.TRACKING })
                }
                CosmoSettingsRow(Icons.Rounded.Key, "Service keys", subtitle = if (configured.isEmpty()) "Ratings and subtitles, optional" else "${configured.size} saved",
                    onClick = { dialog = WatchSettingsDialog.KEYS })
                CosmoSettingsRow(CosmoIcons.Download, "Debrid", subtitle = if (preferences.debrid != null) "Connected" else "Optional",
                    onClick = { dialog = WatchSettingsDialog.DEBRID })
            }
        }
        item(key = "settings:end") { Spacer(Modifier.height(40.dp)) }
    }

    unlocking?.let { target ->
        var pin by remember(target) { mutableStateOf("") }
        var message by remember(target) { mutableStateOf<String?>(null) }
        com.cosmotune.ui.sheets.CosmoAlertDialog(onDismissRequest = { unlocking = null }, title = "Unlock ${target.name}",
            confirmLabel = "Unlock", dismissLabel = "Cancel", onConfirm = {
                scope.launch { if (repository.selectProfile(target.id, pin)) unlocking = null else message = "The PIN did not match" }
            }) {
            WatchField("PIN", pin, { pin = it.filter(Char::isDigit).take(8) }, fonts, look, password = true)
            message?.let { Text(it, style = fonts.body, color = look.dim) }
        }
    }
    when (dialog) {
        WatchSettingsDialog.FOLDER -> if (downloads != null) WatchDialog("Downloads", "Save new downloads to", onDismiss = { dialog = null }) {
            var error by remember { mutableStateOf<String?>(null) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                downloads.destinations.forEach { folder ->
                    OrbitSelectChip(folder.path, folder.path == destination?.path, fonts, look, onClick = {
                        error = runCatching { downloads.selectDestination(folder) }.exceptionOrNull()?.message
                        if (error == null) dialog = null
                    }, haptic = false)
                }
                if (downloads.canChooseFolder) WatchCardChip("Choose another folder…", look.ink, fonts, onClick = {
                    scope.launch { error = runCatching { downloads.browseDestination() }.exceptionOrNull()?.message; if (error == null) dialog = null }
                }, icon = Icons.Rounded.Folder)
                Text(error ?: "Existing downloads stay in their original folder.", style = fonts.body, color = look.dim)
            }
        }
        WatchSettingsDialog.DENSITY -> WatchDialog("Display", "Poster size", onDismiss = { dialog = null }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                com.cosmotune.watch.WatchDensity.entries.forEach { density ->
                    OrbitSelectChip(density.label, preferences.density == density, fonts, look, onClick = {
                        scope.launch { repository.setPreferences(preferences.copy(density = density)) }; dialog = null
                    }, haptic = false)
                }
            }
        }
        WatchSettingsDialog.ADD_PROFILE -> {
            var name by remember { mutableStateOf("") }
            com.cosmotune.ui.sheets.CosmoAlertDialog(onDismissRequest = { dialog = null }, title = "Add a profile",
                message = "A viewer with their own My list and Continue watching.", confirmLabel = "Add", dismissLabel = "Cancel",
                confirmEnabled = name.isNotBlank(), onConfirm = {
                    scope.launch { repository.addProfile(name)?.let { repository.selectProfile(it) }; dialog = null }
                }) {
                WatchField("Name", name, { name = it.take(40) }, fonts, look)
            }
        }
        WatchSettingsDialog.PIN -> {
            var pin by remember { mutableStateOf("") }
            com.cosmotune.ui.sheets.CosmoAlertDialog(onDismissRequest = { dialog = null }, title = "Profile PIN",
                message = "Use four to eight digits. Leave blank to remove the PIN. This lock stays on this device.",
                confirmLabel = "Save", dismissLabel = "Cancel", confirmEnabled = pin.isEmpty() || pin.length in 4..8,
                onConfirm = { scope.launch { repository.setProfilePin(pin.takeIf { it.isNotBlank() }); dialog = null } }) {
                WatchField("New PIN", pin, { pin = it.filter(Char::isDigit).take(8) }, fonts, look, password = true)
            }
        }
        WatchSettingsDialog.REMOVE_PROFILE -> {
            var message by remember { mutableStateOf<String?>(null) }
            com.cosmotune.ui.sheets.CosmoAlertDialog(onDismissRequest = { dialog = null }, title = "Delete this Watch profile?",
                message = message ?: "Its saved titles and viewing progress will be removed. Everyone is kept.", confirmLabel = "Delete", dismissLabel = "Cancel",
                onConfirm = { scope.launch { runCatching { repository.removeProfile(active) }.onSuccess { dialog = null }.onFailure { message = it.message } } })
        }
        WatchSettingsDialog.TRACKING -> repository.tracking?.let { tracking ->
            WatchDialog("Connected services", "Tracking", onDismiss = { dialog = null }) { WatchTrackingCard(tracking, fonts, look) }
        }
        WatchSettingsDialog.KEYS -> WatchDialog("Connected services", "Service keys", onDismiss = { dialog = null }) { ServiceKeys(fonts, look) }
        WatchSettingsDialog.DEBRID -> WatchDialog("Connected services", "Debrid", onDismiss = { dialog = null }) {
            Box(Modifier.padding(bottom = 8.dp)) { WatchDebridCard(repository, fonts, look) }
        }
        null -> Unit
    }
}

/** The keys for OMDb ratings and SubDL subtitles: which is saved, a field for a new one, Save and Remove. */
@Composable
private fun ServiceKeys(fonts: OrbitFonts, look: OrbitLook) {
    val repository = watchRepository()
    val scope = rememberCoroutineScope()
    val configured by repository.configuredServices.collectAsState()
    var service by remember { mutableStateOf("omdb") }
    var apiKey by remember(service) { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OrbitLabel("Additional ratings and subtitles", fonts, look.dim)
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("omdb" to "OMDb ratings", "subdl" to "SubDL subtitles").forEach { (id, label) ->
                OrbitSelectChip(label + if (id in configured) " · connected" else "", service == id, fonts, look, onClick = { service = id; message = null }, haptic = false)
            }
        }
        WatchField("API key", apiKey, { apiKey = it.take(200) }, fonts, look, password = true)
        Text("Keys stay in protected device storage. OMDb supplies attributed IMDb, Rotten Tomatoes and Metacritic ratings when available. SubDL adds individual subtitle files to the player’s subtitle search.", style = fonts.body, color = look.dim)
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WatchCardChip("Save key", look.ink, fonts, enabled = apiKey.isNotBlank(), onClick = {
                scope.launch { runCatching { repository.setServiceKey(service, apiKey) }.onSuccess { apiKey = ""; message = "Saved" }.onFailure { message = "The key could not be saved" } }
            })
            if (service in configured) WatchCardChip("Remove key", look.ink, fonts, onClick = { scope.launch { repository.setServiceKey(service, "") } })
        }
        message?.let { Text(it, style = fonts.body, color = look.dim) }
    }
}
