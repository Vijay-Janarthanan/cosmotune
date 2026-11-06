package com.cosmotune.manga.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.cosmotune.manga.data.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.cosmotune.ui.orbit.OrbitSwitch
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.theme.LocalCosmoColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Sources (a tab of Manga, board D): the catalogue of sources with the search pill, the language chips and a switch each, then page
 * storage, Komga, extensions, custom websites, website verification, library backup and the update schedule.
 */
@Composable
internal fun MangaSources(repository: MangaRepository, bottom: androidx.compose.ui.unit.Dp) {
    val state by repository.store.state.collectAsState()
    val palette = LocalCosmoColors.current.palette
    val scope = rememberCoroutineScope()
    val catalogue = remember { KotatsuSource.catalogue() }
    val list = rememberLazyListState()
    val look = orbitLook()
    var query by remember { mutableStateOf("") }
    var language by remember { mutableStateOf("") }
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
        catch (failure: Exception) { error = failure.message ?: "Source settings could not be saved" }
    }
    val languages = remember(catalogue) { catalogue.map { it.language }.filter { it.isNotBlank() }.distinct().sorted() }
    val shown = catalogue.filter { it.title.contains(query, true) && it.language.contains(language, true) }
    val on = state.sources.count { it.enabled }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("manga:sources:list"), state = list, contentPadding = PaddingValues(bottom = bottom)) {
            item(key = "heading") { ReadPageHead(mangaText("Sources"), if (on == 1) mangaText("1 source on") else "$on " + mangaText("sources on")) }
            item(key = "management") {
                Column(Modifier.fillMaxWidth().padding(horizontal = ReadMargin), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    error?.let { Text(it, style = ReadType.body(), color = Color(palette.danger)) }
                    ReadOverline(mangaText("Storage"), inset = 0.dp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${state.cacheLimitBytes / (1024 * 1024)} MB page storage", style = ReadType.rowTitle(), color = Color(palette.textPrimary), modifier = Modifier.weight(1f))
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
                        OutlinedTextField(server, { server = it.take(2_048) }, label = { Text(mangaText("Server URL")) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("manga:komga:server"))
                        OutlinedTextField(key, { key = it.take(512) }, label = { Text(mangaText("API key")) }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth().testTag("manga:komga:key"))
                        Text("Or sign in with a Komga user name and password instead of an API key.", style = ReadType.meta(), color = Color(palette.textSecondary))
                        OutlinedTextField(user, { user = it.take(256) }, label = { Text(mangaText("User name")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(password, { password = it.take(512) }, label = { Text(mangaText("Password")) }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
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
                    MangaExtensionControls(repository, onError = { error = it })
                    MangaTemplateControls(repository)
                    MangaVerificationSourcePicker(repository)
                    ReadOverline(mangaText("Backup and updates"), inset = 0.dp)
                    MangaLibraryTools(repository)
                }
            }
            item(key = "find") {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReadSection("Catalogue", subtitle = if (shown.size == 1) mangaText("1 source") else "${shown.size} " + mangaText("sources"))
                    ReadSearchField(query, { query = it.take(500) }, mangaText("Find source"), Modifier.testTag("manga:sources:query"))
                    ReadChipRow {
                        ReadChip(mangaText("All languages"), language.isBlank(), { language = "" })
                        languages.forEach { tag -> ReadChip(languageName(tag), language == tag, { language = if (language == tag) "" else tag }) }
                    }
                    OutlinedTextField(language, { language = it.take(32) }, label = { Text(mangaText("Language code, e.g. en or ja")) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = ReadMargin).testTag("manga:sources:language"))
                }
            }
            items(shown, key = { it.id }) { source ->
                val enabled = state.sources.any { it.id == source.id && it.enabled }
                Row(Modifier.fillMaxWidth().padding(horizontal = ReadMargin, vertical = 4.dp).heightIn(min = 62.dp).clip(ReadShape.md).background(look.row)
                    .testTag("manga:source:${source.id}")
                    .toggleable(value = enabled, role = Role.Switch, onValueChange = { value -> action {
                        val previous = state.sources.find { it.id == source.id }
                        repository.store.source(previous?.copy(enabled = value) ?: MangaSourceConfig(source.id, name = source.title, language = source.language, enabled = value))
                    } }).semantics(mergeDescendants = true) { contentDescription = "${source.title} (${source.language})" }
                    .padding(start = 18.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(source.title, style = ReadType.rowTitle(), color = look.ink)
                        Text(languageName(source.language), style = ReadType.meta(), color = look.dim)
                    }
                    Box(Modifier.clearAndSetSemantics { }) { OrbitSwitch(enabled, onChange = {}, look = look, label = source.title, enabled = false) }
                }
            }
        }
    }
}
