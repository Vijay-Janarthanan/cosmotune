@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.cosmotune.manga.ui

import com.cosmotune.ui.orbit.OrbitInputField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cosmotune.manga.data.*
import com.cosmotune.ui.orbit.OrbitSwitch
import com.cosmotune.ui.orbit.OrbitSwitchRow
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.theme.LocalCosmoColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Settings › Manga (board frame 15): what used to stand above the source list in the Sources tab, and the reader's defaults. For the
 * settings screens of the phone and the desktop; it opens Manga's own library (the same one the mode uses) unless handed [repository].
 */
@Composable
fun MangaSettingsSection(modifier: Modifier = Modifier, repository: MangaRepository? = null) {
    val opened = if (repository == null) rememberMangaRepository().getOrNull() else null
    val repository = repository ?: opened
    if (repository == null) {
        Text(mangaText("Manga's storage could not be opened. Check available disk space and file access."), style = ReadType.body(), color = orbitLook().dim,
            modifier = modifier.padding(16.dp))
        return
    }
    MangaBackgroundUpdates(repository)
    MangaSettingsContent(repository, modifier)
}

/** The reader's defaults, page storage, Komga, extensions, custom websites, verification, parser updates and the library tools. */
@Composable
internal fun MangaSettingsContent(repository: MangaRepository, modifier: Modifier = Modifier) {
    val state by repository.store.state.collectAsState()
    val palette = LocalCosmoColors.current.palette
    val scope = rememberCoroutineScope()
    val parserUpdate by repository.context.parserUpdates.status.collectAsState()
    val look = orbitLook()
    var komga by remember { mutableStateOf(false) }
    var server by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun action(block: suspend () -> Unit) = scope.launch {
        error = null
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "Manga settings could not be saved" }
    }
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("settings:manga")) {
        ReadOverline(mangaText("Reader"), inset = ReadMargin - 4.dp)
        Column(Modifier.fillMaxWidth().padding(horizontal = ReadMargin), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(mangaText("Direction for new series"), style = ReadType.rowTitle(), color = look.ink)
            ReadChipRow(inset = 0.dp) {
                readerModeOrder.forEach { item -> ReadChip(mangaText(readerModeLabel(item)), state.readerMode == item, { action { repository.store.update { it.copy(readerMode = item) } } }) }
            }
            Text(mangaText("Scale"), style = ReadType.rowTitle(), color = look.ink)
            ReadChipRow(inset = 0.dp) {
                ReadChip(mangaText("Fit page"), state.readerFit == MangaReaderFit.PAGE, { action { repository.store.update { it.copy(readerFit = MangaReaderFit.PAGE) } } })
                ReadChip(mangaText("Fit width"), state.readerFit == MangaReaderFit.WIDTH, { action { repository.store.update { it.copy(readerFit = MangaReaderFit.WIDTH) } } })
            }
            MangaReaderImageOptions(state) { block -> action { repository.store.update(block) } }
            if (mangaLockAvailable()) MangaLockSettings(state) { block -> action { repository.store.update(block) } }
            mangaHideDetail()?.let { hideDetail ->
                OrbitSwitchRow(mangaText("Hide from screenshots"), state.secureScreen, { on -> action { repository.store.update { it.copy(secureScreen = on) } } },
                    plain = true, detail = mangaText(hideDetail))
            }
            OrbitSwitchRow(mangaText("Keep screen on while reading"), state.readerKeepScreenOn, { on -> action { repository.store.update { it.copy(readerKeepScreenOn = on) } } }, plain = true)
            OrbitSwitchRow(mangaText("Page number at the foot"), state.readerPageNumber, { on -> action { repository.store.update { it.copy(readerPageNumber = on) } } }, plain = true,
                detail = mangaText("While the reader's card is hidden"))
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = ReadMargin).padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ReadOverline(mangaText("Downloads"), inset = 0.dp)
            OrbitSwitchRow(mangaText("Download new chapters"), state.autoDownloadNew, { on -> action { repository.store.update { it.copy(autoDownloadNew = on) } } }, plain = true,
                detail = mangaText("When an update finds them"))
            Text(mangaText("Download ahead while reading"), style = ReadType.rowTitle(), color = look.ink)
            ReadChipRow(inset = 0.dp) {
                listOf(0 to "Off", 2 to "Next 2", 5 to "Next 5", 10 to "Next 10").forEach { (count, label) ->
                    ReadChip(mangaText(label), state.downloadAhead == count, { action { repository.store.update { it.copy(downloadAhead = count) } } }, Modifier.testTag("manga:ahead:$count"))
                }
            }
            Text(mangaText("Pages loaded ahead in the reader"), style = ReadType.rowTitle(), color = look.ink)
            ReadChipRow(inset = 0.dp) {
                listOf(0 to "None", 2 to "2", 4 to "4", 8 to "8").forEach { (count, label) ->
                    ReadChip(mangaText(label), state.readerPreloadPages == count, { action { repository.store.update { it.copy(readerPreloadPages = count) } } }, Modifier.testTag("manga:preload:$count"))
                }
            }
            OrbitSwitchRow(mangaText("Load ahead only on Wi-Fi"), state.aheadOnWifiOnly, { on -> action { repository.store.update { it.copy(aheadOnWifiOnly = on) } } }, plain = true,
                detail = mangaText("Pages and chapters ahead wait while you are on mobile data"))
            MangaCbzSettings(state) { block -> action { repository.store.update(block) } }
            OrbitSwitchRow(mangaText("Remove chapters once read"), state.deleteAfterRead, { on -> action { repository.store.update { it.copy(deleteAfterRead = on) } } }, plain = true,
                detail = mangaText("Frees space after you finish a downloaded chapter"))
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = ReadMargin), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            error?.let { Text(it, style = ReadType.body(), color = Color(palette.danger)) }
            MangaHiddenImagesSettings(state) { block -> action { repository.store.update(block) } }
            ReadOverline(mangaText("Storage"), inset = 0.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${state.cacheLimitBytes / (1024 * 1024)} MB page storage", style = ReadType.rowTitle(), color = Color(palette.textPrimary), modifier = Modifier.weight(1f).padding(end = 12.dp))
                ReadTextButton(mangaText("Clear cache"), { action { repository.clearCache() } })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(128L, 256L, 512L, 1024L).forEach { mb ->
                    ReadChip("$mb", state.cacheLimitBytes == mb * 1024 * 1024, { action { repository.store.update { it.copy(cacheLimitBytes = mb * 1024 * 1024) } } })
                }
            }
            ReadOverline(mangaText("Libraries and extensions"), inset = 0.dp)
            ReadButton(mangaText("Connect Komga library"), { komga = !komga }, kind = ReadKind.TONAL)
            if (komga) {
                OrbitInputField(server, { server = it.take(2_048) }, mangaText("Server URL"), modifier = Modifier.fillMaxWidth().testTag("manga:komga:server"))
                OrbitInputField(key, { key = it.take(512) }, mangaText("API key"), secret = true,
                    modifier = Modifier.fillMaxWidth().testTag("manga:komga:key"))
                Text("Or sign in with a Komga user name and password instead of an API key.", style = ReadType.meta(), color = Color(palette.textSecondary))
                OrbitInputField(user, { user = it.take(256) }, mangaText("User name"), modifier = Modifier.fillMaxWidth())
                OrbitInputField(password, { password = it.take(512) }, mangaText("Password"), secret = true,
                    modifier = Modifier.fillMaxWidth())
                ReadButton(mangaText("Test and save"), enabled = !testing, onClick = { scope.launch {
                    testing = true
                    try {
                        val config = KomgaSource.configuration(server, key, user, password).let { next ->
                            state.sources.find { it.id == next.id }?.let { previous -> next.copy(enabled = previous.enabled) } ?: next
                        }
                        KomgaSource(config, repository.context.httpClient).browse()
                        repository.store.source(config); repository.invalidateSource(config.id)
                        key = ""; password = ""; komga = false; error = null
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = failure.message ?: "Komga could not be reached" }
                    finally { testing = false }
                } })
            }
            state.sources.filter { it.kind == "komga" }.forEach { config ->
                Row(Modifier.fillMaxWidth().testTag("manga:komga:${config.id}"), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(config.name, style = ReadType.rowTitle(), color = Color(palette.textPrimary))
                        Text(config.baseUrl, style = ReadType.meta(), color = Color(palette.textSecondary))
                    }
                    OrbitSwitch(config.enabled, onChange = { enabled -> action { repository.store.source(config.copy(enabled = enabled)); repository.invalidateSource(config.id) } }, look = look, label = "Use ${config.name}")
                    ReadTextButton(mangaText("Remove"), { action {
                        repository.store.update { it.copy(sources = it.sources.filterNot { source -> source.id == config.id }) }
                        repository.invalidateSource(config.id)
                    } })
                }
            }
            MangaSuwayomiControls(repository, onError = { error = it })
            MangaWebDavSettings(repository)
            MangaTrackingSettings(repository)
            MangaExtensionControls(repository, onError = { error = it })
            MangaTemplateControls(repository)
            MangaVerificationSourcePicker(repository)
            ReadOverline(mangaText("Backup and updates"), inset = 0.dp)
            Text("Built-in parsers · ${parserUpdate.revision}", style = ReadType.rowTitle(), color = Color(palette.textPrimary))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Update sources automatically", style = ReadType.body(), color = Color(palette.textPrimary), modifier = Modifier.weight(1f))
                OrbitSwitch(state.parserAutoUpdates, onChange = { enabled -> action {
                    repository.store.update { it.copy(parserAutoUpdates = enabled) }
                    if (enabled) repository.refreshParsers()
                } }, look = look, label = "Update sources automatically")
            }
            Text(parserUpdate.message ?: "Parser fixes are verified with Cosmotune's signing key before loading.", style = ReadType.meta(), color = Color(palette.textSecondary))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ReadButton(if (parserUpdate.busy) "Checking…" else "Check parser updates", enabled = !parserUpdate.busy,
                    onClick = { action { repository.context.parserUpdates.check(force = true) } }, kind = ReadKind.TONAL)
                if (parserUpdate.downloaded) ReadTextButton("Roll back", { action { repository.context.parserUpdates.rollback() } })
            }
            MangaLibraryTools(repository)
        }
    }
}

/** Lock Manga, with what it asks for; where only Manga's own PIN can lock it, the PIN is set here first. */
@Composable
private fun MangaLockSettings(state: MangaLibraryState, update: ((MangaLibraryState) -> MangaLibraryState) -> Unit) {
    val prompt = rememberMangaLockPrompt()
    val pinOnly = prompt == null
    var pin by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    val problem = pin.takeIf { it.isNotEmpty() }?.let(MangaLockPin::problem)
    val detail = when {
        prompt == "" -> "Asks for your sign-in when Manga opens"
        !pinOnly -> "Asks for $prompt when Manga opens"
        state.lockPin == null -> "Set a Manga PIN first"
        else -> "Asks for your Manga PIN when Manga opens"
    }
    OrbitSwitchRow(mangaText("Lock Manga"), state.lockManga, { on -> update { it.copy(lockManga = on) } }, plain = true, detail = mangaText(detail),
        enabled = !pinOnly || state.lockPin != null || state.lockManga)
    if (!pinOnly) return
    if (state.lockPin != null && !editing) {
        ReadTextButton(mangaText("Change Manga PIN"), { editing = true }, modifier = Modifier.testTag("manga:lock:change"))
        return
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OrbitInputField(pin, { typed -> pin = typed.filter(Char::isDigit).take(12) }, mangaText(if (state.lockPin == null) "New Manga PIN" else "New PIN"), secret = true,
            error = problem != null, modifier = Modifier.fillMaxWidth().testTag("manga:lock:newpin"))
        Text(mangaText(problem ?: "4 to 12 digits. This computer has no sign-in prompt Manga can ask for."), style = ReadType.meta(), color = orbitLook().dim)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReadButton(mangaText("Save PIN"), { val hashed = MangaLockPin.hash(pin); update { it.copy(lockPin = hashed) }; pin = ""; editing = false },
                enabled = pin.isNotEmpty() && problem == null, modifier = Modifier.testTag("manga:lock:save"))
            if (editing) ReadTextButton(mangaText("Cancel"), { pin = ""; editing = false })
        }
    }
}

/** Komikku's WebDAV sync: the listener's own server folder, Sync now and Disconnect, and when it last synced. */
@Composable
private fun MangaWebDavSettings(repository: MangaRepository) {
    val state by repository.store.state.collectAsState()
    val scope = rememberCoroutineScope()
    val look = orbitLook()
    var open by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    fun run(account: MangaWebDav?) = scope.launch {
        busy = true; failure = null
        try {
            if (account != null && state.webDav == null) { repository.syncWebDav(account); repository.store.update { it.copy(webDav = it.webDav ?: account) } }
            else repository.syncWebDav()
            password = ""; open = false
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (problem: Exception) { failure = problem.message ?: "Your server could not be reached" }
        finally { busy = false }
    }
    val account = state.webDav
    if (account == null) {
        ReadButton(mangaText("Sync with WebDAV"), { open = !open }, kind = ReadKind.TONAL, modifier = Modifier.testTag("manga:webdav:open"))
        if (!open) return
        Text(mangaText("Your library, read chapters and places in one file on your own server (Nextcloud, a NAS)."), style = ReadType.meta(), color = look.dim)
        OrbitInputField(url, { url = it.take(2_048) }, mangaText("Folder address"), modifier = Modifier.fillMaxWidth().testTag("manga:webdav:url"))
        OrbitInputField(user, { user = it.take(256) }, mangaText("User name"), modifier = Modifier.fillMaxWidth())
        OrbitInputField(password, { password = it.take(512) }, mangaText("Password"), secret = true, modifier = Modifier.fillMaxWidth())
        failure?.let { Text(it, style = ReadType.meta(), color = Color(LocalCosmoColors.current.palette.danger)) }
        ReadButton(mangaText(if (busy) "Syncing…" else "Connect and sync"), { run(MangaWebDav(url.trim(), user.trim(), password)) },
            enabled = !busy && url.isNotBlank(), modifier = Modifier.testTag("manga:webdav:connect"))
        return
    }
    Row(Modifier.fillMaxWidth().testTag("manga:webdav"), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(mangaText("WebDAV sync"), style = ReadType.rowTitle(), color = look.ink)
            val line = failure ?: account.lastError ?: if (account.lastSyncMs > 0) "Synced " + mangaAgo(account.lastSyncMs) else "Not synced yet"
            Text(line, style = ReadType.meta(), color = look.dim, maxLines = 2)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReadTextButton(mangaText(if (busy) "Syncing…" else "Sync now"), { run(null) }, enabled = !busy, modifier = Modifier.testTag("manga:webdav:sync"))
            ReadTextButton(mangaText("Disconnect"), { scope.launch { repository.store.update { it.copy(webDav = null) } } })
        }
    }
}

/** "just now", "5 min ago", "3 h ago", "2 days ago". */
internal fun mangaAgo(atMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val minutes = ((nowMs - atMs) / 60_000L).coerceAtLeast(0)
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 48 * 60 -> "${minutes / 60} h ago"
        else -> "${minutes / (24 * 60)} days ago"
    }
}

/** The images blanked out in the reader, newest first, each with Show; Show all clears them. */
@Composable
private fun MangaHiddenImagesSettings(state: MangaLibraryState, update: ((MangaLibraryState) -> MangaLibraryState) -> Unit) {
    val look = orbitLook()
    var all by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().testTag("manga:hidden-images"), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(mangaText("Hidden images"), style = ReadType.rowTitle(), color = look.ink)
            Text(if (state.hiddenImages.isEmpty()) mangaText("None. Hold a page in the reader and choose Hide this image.")
                else "${state.hiddenImages.size} " + mangaText(if (state.hiddenImages.size == 1) "image stays blank in the reader" else "images stay blank in the reader"),
                style = ReadType.meta(), color = look.dim)
        }
        if (state.hiddenImages.isNotEmpty()) ReadTextButton(mangaText("Show all"), { update { it.copy(hiddenImages = emptyMap()) } })
    }
    val listed = state.hiddenImages.entries.sortedByDescending { it.value.hiddenAtMs }
    (if (all) listed else listed.take(5)).forEach { (mark, image) ->
        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(mangaText("From") + " ${image.series} · " + mangaAgo(image.hiddenAtMs), style = ReadType.meta(), color = look.dim, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 12.dp))
            ReadTextButton(mangaText("Show"), { update { MangaHiddenImages.show(it, mark) } })
        }
    }
    if (!all && listed.size > 5) ReadTextButton(mangaText("See all") + " ${listed.size}", { all = true })
}

