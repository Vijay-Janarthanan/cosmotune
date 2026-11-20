@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.cosmotune.manga.ui

import com.cosmotune.ui.orbit.OrbitInputField
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
 * Sources (a tab of Manga): the catalogue of sources and nothing else, the ones in use first, with the search pill, the language
 * chips and a switch each. Page storage, Komga, extensions, custom websites, website verification, parser updates and the library
 * backup are in Settings › Manga ([MangaSettingsContent]).
 */
@Composable
internal fun MangaSources(repository: MangaRepository, bottom: androidx.compose.ui.unit.Dp) {
    val state by repository.store.state.collectAsState()
    val palette = LocalCosmoColors.current.palette
    val scope = rememberCoroutineScope()
    val activeParsers by repository.context.parserUpdates.bundle.collectAsState()
    val catalogue = remember(activeParsers) { KotatsuSource.catalogue(activeParsers) }
    val list = rememberLazyListState()
    val look = orbitLook()
    var query by remember { mutableStateOf("") }
    var language by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    fun action(block: suspend () -> Unit) = scope.launch {
        error = null
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "Source settings could not be saved" }
    }
    val languages = remember(catalogue) { catalogue.map { it.language }.filter { it.isNotBlank() }.distinct().sorted() }
    val shown = catalogue.filter { it.title.contains(query, true) && it.language.contains(language, true) }
        .sortedByDescending { source -> state.sources.any { it.id == source.id && it.enabled } }
    val on = state.sources.count { it.enabled }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().readScrollbar(list.scrollIndicatorState, bottomInset = bottom).testTag("manga:sources:list"), state = list, contentPadding = PaddingValues(bottom = bottom)) {
            item(key = "heading") { ReadPageHead(mangaText("Sources"), if (on == 1) mangaText("1 source on") else "$on " + mangaText("sources on")) }
            error?.let { message -> item(key = "error") { ReadInlineBanner(message, onDismiss = { error = null }) } }
            item(key = "find") {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReadSection("Catalogue", subtitle = if (shown.size == 1) mangaText("1 source") else "${shown.size} " + mangaText("sources"))
                    ReadSearchField(query, { query = it.take(500) }, mangaText("Find source"), Modifier.testTag("manga:sources:query"))
                    ReadChipRow {
                        ReadChip(mangaText("All languages"), language.isBlank(), { language = "" })
                        languages.forEach { tag -> ReadChip(languageName(tag), language == tag, { language = if (language == tag) "" else tag }) }
                    }
                }
            }
            items(shown, key = { it.id }) { source ->
                val enabled = state.sources.any { it.id == source.id && it.enabled }
                Row(Modifier.fillMaxWidth().padding(horizontal = ReadMargin).heightIn(min = com.cosmotune.ui.orbit.orbitRowHeight()).clip(ReadShape.sm)
                    .testTag("manga:source:${source.id}")
                    .toggleable(value = enabled, role = Role.Switch, onValueChange = { value -> action {
                        val previous = state.sources.find { it.id == source.id }
                        repository.store.source(previous?.copy(enabled = value) ?: MangaSourceConfig(source.id, name = source.title, language = source.language, enabled = value))
                    } }).semantics(mergeDescendants = true) { contentDescription = "${source.title} (${source.language})" }
                    .padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
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
