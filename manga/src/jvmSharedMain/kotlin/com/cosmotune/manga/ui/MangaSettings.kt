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
                MangaReaderMode.entries.forEach { item -> ReadChip(mangaText(readerModeLabel(item)), state.readerMode == item, { action { repository.store.update { it.copy(readerMode = item) } } }) }
            }
            Text(mangaText("Scale"), style = ReadType.rowTitle(), color = look.ink)
            ReadChipRow(inset = 0.dp) {
                ReadChip(mangaText("Fit page"), state.readerFit == MangaReaderFit.PAGE, { action { repository.store.update { it.copy(readerFit = MangaReaderFit.PAGE) } } })
                ReadChip(mangaText("Fit width"), state.readerFit == MangaReaderFit.WIDTH, { action { repository.store.update { it.copy(readerFit = MangaReaderFit.WIDTH) } } })
            }
            OrbitSwitchRow(mangaText("Keep screen on while reading"), state.readerKeepScreenOn, { on -> action { repository.store.update { it.copy(readerKeepScreenOn = on) } } }, plain = true)
            OrbitSwitchRow(mangaText("Page number at the foot"), state.readerPageNumber, { on -> action { repository.store.update { it.copy(readerPageNumber = on) } } }, plain = true,
                detail = mangaText("While the reader's card is hidden"))
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = ReadMargin), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            error?.let { Text(it, style = ReadType.body(), color = Color(palette.danger)) }
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
