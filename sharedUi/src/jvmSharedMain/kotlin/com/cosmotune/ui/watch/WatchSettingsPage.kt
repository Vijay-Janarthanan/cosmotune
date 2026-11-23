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
private enum class WatchSettingsDialog { FOLDER, DENSITY, LANGUAGES, ANIME, RESTORE, SUBTITLES, CACHE, SEED_TIME, CONNECTION, ADD_PROFILE, PIN, REMOVE_PROFILE, TRACKING, KEYS, DEBRID }

/**
 * Watch settings on the same rows Video's settings use ([CosmoSettingsGroup], [CosmoSettingsRow]): Sources and add-ons first
 * (Plugins, where new downloads are saved), then Profiles (one row per viewer, a tick on this one, Add, PIN, Delete), Display
 * (poster size, the subtitle look, the intro and credits skips) and Connected services (tracking, service keys, debrid). Each row says its current value; the
 * ones that need words typed open a small dialog. Reached from the profile menu on the phone and from the sidebar on the desktop.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
    val torrent by repository.torrentSettings.collectAsState()
    fun keepTorrent(change: (com.cosmotune.watch.torrent.TorrentSettings) -> com.cosmotune.watch.torrent.TorrentSettings) =
        scope.launch { repository.setTorrentSettings(change(repository.torrentSettings.value)) }
    var dialog by remember { mutableStateOf<WatchSettingsDialog?>(null) }
    var backupNote by remember { mutableStateOf<String?>(null) }
    var restoring by remember { mutableStateOf<String?>(null) }
    val saveFile = rememberTextFileSaver()
    val openBackup = rememberTextFileOpener { text -> restoring = text; dialog = WatchSettingsDialog.RESTORE }
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
                CosmoSettingsRow(CosmoIcons.Translate, "Languages on Home", subtitle = homeLanguagesText(preferences.homeLanguages),
                    onClick = { dialog = WatchSettingsDialog.LANGUAGES })
                val sub = preferences.subtitles
                CosmoSettingsRow(CosmoIcons.Lyrics, "Subtitle look", subtitle = "${sub.sizePercent}% · ${sub.colour.label} · ${sub.background.label.lowercase()} behind",
                    onClick = { dialog = WatchSettingsDialog.SUBTITLES })
                CosmoSettingsRow(CosmoIcons.Devices, "Software decoding", subtitle = "Try this if the picture looks broken or green",
                    trailing = {
                        OrbitSwitch(preferences.softwareDecoding, { on -> scope.launch { repository.setPreferences(preferences.copy(softwareDecoding = on)) } }, look, "Software decoding")
                    })
                // Android sends surround sound on to a receiver by itself when the output takes it; a computer needs telling.
                if (com.cosmotune.ui.components.platformSdkInt == null) CosmoSettingsRow(CosmoIcons.Volume, "Surround to your amplifier",
                    subtitle = "Sends Dolby and DTS sound as it is over HDMI or optical",
                    trailing = {
                        OrbitSwitch(preferences.passthrough, { on -> scope.launch { repository.setPreferences(preferences.copy(passthrough = on)) } }, look, "Surround to your amplifier")
                    })
                CosmoSettingsRow(Icons.Rounded.SkipNext, "Skip intros, recaps and credits", subtitle = "A button shows when the times are known",
                    trailing = {
                        OrbitSwitch(preferences.animeSkips, { on -> scope.launch { repository.setPreferences(preferences.copy(animeSkips = on)) } }, look,
                            "Skip intros, recaps and credits")
                    })
            }
        }
        if (repository.canOpenTorrents) item(key = "settings:torrents") {
            CosmoSettingsGroup(header = "Torrents", footer = "Torrents come only from links and files you open and add-ons you install. Sharing is off on mobile data unless you allow it.") {
                CosmoSettingsRow(CosmoIcons.Share, "Keep sharing after a download", subtitle = if (torrent.seedAfterDownload) seedTimeText(torrent.seedMinutes) else "Off",
                    trailing = { OrbitSwitch(torrent.seedAfterDownload, { on -> keepTorrent { it.copy(seedAfterDownload = on) } }, look, "Keep sharing after a download") })
                if (torrent.seedAfterDownload) {
                    CosmoSettingsRow(CosmoIcons.Clock, "Share for", subtitle = seedTimeText(torrent.seedMinutes), onClick = { dialog = WatchSettingsDialog.SEED_TIME })
                    CosmoSettingsRow(CosmoIcons.Devices, "Share on mobile data", subtitle = if (torrent.seedOnMobileData) "On" else "Off",
                        trailing = { OrbitSwitch(torrent.seedOnMobileData, { on -> keepTorrent { it.copy(seedOnMobileData = on) } }, look, "Share on mobile data") })
                }
                CosmoSettingsRow(Icons.Rounded.Lock, "Torrent connection", subtitle = torrent.bindInterface?.let { "Only $it · stops if it drops" } ?: "Any connection",
                    onClick = { dialog = WatchSettingsDialog.CONNECTION })
            }
        }
        item(key = "settings:storage") {
            val limit by repository.cacheLimitMb.collectAsState()
            val used by androidx.compose.runtime.produceState(0L, dialog) { value = runCatching { repository.cacheBytes() }.getOrDefault(0L) }
            CosmoSettingsGroup(header = "Storage") {
                CosmoSettingsRow(Icons.Rounded.Folder, "Cache", subtitle = "${sizeText(used) ?: "Empty"} of ${cacheLimitText(limit)}", onClick = { dialog = WatchSettingsDialog.CACHE })
                CosmoSettingsRow(CosmoIcons.Download, "Back up to a file", subtitle = backupNote ?: "My list, where you are, playlists, channels and add-ons",
                    onClick = { scope.launch {
                        val text = runCatching { repository.exportBackup() }.getOrNull()
                        if (text == null) backupNote = "The backup could not be made" else
                            saveFile("cosmotune-watch-${java.time.LocalDate.now()}.json", text) { saved -> backupNote = if (saved) "Backup saved" else "Not saved" }
                    } })
                CosmoSettingsRow(CosmoIcons.Replay, "Restore from a file", subtitle = "Adds a backup to this profile; nothing is removed", onClick = openBackup)
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
                repository.animeTracking?.let { anime ->
                    val lists by anime.statuses.collectAsState()
                    val on = lists.filter { it.connected }
                    CosmoSettingsRow(Icons.Rounded.Link, "Anime lists", subtitle = if (on.isEmpty()) "AniList or MyAnimeList, optional" else on.joinToString(" and ") { it.service.label },
                        onClick = { dialog = WatchSettingsDialog.ANIME })
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
        WatchSettingsDialog.LANGUAGES -> WatchDialog("Display", "Languages on Home", onDismiss = { dialog = null }) {
            val chosen = preferences.homeLanguages
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.cosmotune.watch.WatchLanguage.entries.forEach { language ->
                        val on = language.code in chosen
                        OrbitSelectChip(language.label, on, fonts, look, onClick = {
                            val next = if (on) chosen - language.code else (chosen + language.code).takeLast(MAX_HOME_LANGUAGES)
                            scope.launch { repository.setPreferences(preferences.copy(homeLanguages = next)) }
                        }, haptic = false)
                    }
                }
                Text("Each language gets Top 10 films and series, then its films, web series and daily soaps on Home. Pick up to $MAX_HOME_LANGUAGES; another one takes the place of the first.",
                    style = fonts.body, color = look.dim)
            }
        }
        WatchSettingsDialog.SUBTITLES -> WatchSubtitleLookDialog { dialog = null }
        WatchSettingsDialog.CACHE -> WatchDialog("Storage", "Cache", onDismiss = { dialog = null }) {
            val limit by repository.cacheLimitMb.collectAsState()
            var cleared by remember { mutableStateOf(false) }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OrbitLabel("Keep at most", fonts, look.dim, Modifier.padding(start = 6.dp))
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(100, 250, 500, 1000, 2000).forEach { mb ->
                        OrbitSelectChip(cacheLimitText(mb), limit == mb, fonts, look, onClick = { scope.launch { repository.setCacheLimitMb(mb) } }, haptic = false)
                    }
                }
                WatchCardChip(if (cleared) "Cache emptied" else "Empty the cache", look.ink, fonts, enabled = !cleared,
                    onClick = { scope.launch { repository.clearCache(); cleared = true } })
                Text("The cache keeps catalogues, guides and add-on answers so pages open quickly; the oldest go first. Streaming a torrent keeps at most 96 MB in memory and nothing on disk; downloads stay until you remove them.",
                    style = fonts.body, color = look.dim)
            }
        }
        WatchSettingsDialog.SEED_TIME -> WatchDialog("Torrents", "Share for", onDismiss = { dialog = null }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30, 60, 180, 1440, 0).forEach { minutes ->
                    OrbitSelectChip(seedTimeText(minutes), torrent.seedMinutes == minutes, fonts, look, onClick = { keepTorrent { it.copy(seedMinutes = minutes) }; dialog = null }, haptic = false)
                }
            }
        }
        WatchSettingsDialog.CONNECTION -> WatchDialog("Torrents", "Torrent connection", onDismiss = { dialog = null }) {
            val connections = remember { repository.torrentConnections() }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OrbitSelectChip("Any connection", torrent.bindInterface == null, fonts, look, onClick = { keepTorrent { it.copy(bindInterface = null) }; dialog = null }, haptic = false)
                (connections + listOfNotNull(torrent.bindInterface)).distinct().forEach { name ->
                    OrbitSelectChip(name, torrent.bindInterface == name, fonts, look, onClick = { keepTorrent { it.copy(bindInterface = name) }; dialog = null }, haptic = false)
                }
                Text("Pick your VPN's connection: torrents then use only it, and stop at once if it drops. Turn the VPN on first so it shows here.",
                    style = fonts.body, color = look.dim)
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
        WatchSettingsDialog.ANIME -> repository.animeTracking?.let { anime ->
            WatchDialog("Connected services", "Anime lists", onDismiss = { dialog = null }) { WatchAnimeTrackingCard(anime, fonts, look) }
        }
        WatchSettingsDialog.RESTORE -> restoring?.let { text ->
            var done by remember(text) { mutableStateOf<String?>(null) }
            val preview by androidx.compose.runtime.produceState<Result<String>?>(null, text) { value = runCatching { repository.readBackup(text) } }
            val readable = preview?.isSuccess == true
            com.cosmotune.ui.sheets.CosmoAlertDialog(onDismissRequest = { dialog = null; restoring = null }, title = "Restore from a file",
                message = done ?: preview?.fold({ "This backup has $it. They are added to this profile; nothing here is removed." }, { it.message ?: "This file could not be read." })
                    ?: "Reading the backup…",
                confirmLabel = if (done == null && readable) "Restore" else null, dismissLabel = if (done == null) "Cancel" else "Done",
                onConfirm = { scope.launch { done = runCatching { repository.restoreBackup(text) }.getOrElse { it.message ?: "The backup could not be restored." } } })
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

/** "1 hour", "Until you stop it". */
private fun seedTimeText(minutes: Int): String = when {
    minutes <= 0 -> "Until you stop it"
    minutes < 60 -> "$minutes minutes"
    minutes == 60 -> "1 hour"
    minutes < 1440 -> "${minutes / 60} hours"
    minutes == 1440 -> "1 day"
    else -> "${minutes / 1440} days"
}

/** How many languages may have their own rows on Home; each adds five rows. */
private const val MAX_HOME_LANGUAGES = 4

/** "Tamil and Hindi", "None". */
internal fun homeLanguagesText(codes: List<String>): String {
    val names = codes.map { code -> com.cosmotune.watch.WatchLanguage.entries.firstOrNull { it.code == code }?.label ?: code }
    return when (names.size) { 0 -> "None"; 1 -> names[0]; else -> names.dropLast(1).joinToString(", ") + " and " + names.last() }
}

/** "250 MB", "1 GB". */
private fun cacheLimitText(mb: Int): String = if (mb >= 1000) "${mb / 1000} GB" else "$mb MB"
