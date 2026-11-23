package com.cosmotune.reading.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cosmotune.manga.ui.*
import com.cosmotune.reading.data.*
import com.cosmotune.ui.collectAsUiState
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.kit.KitSheet
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The languages Translate offers besides the device's own, as codes. */
internal val translateTargets = listOf("en", "es", "fr", "de", "it", "pt", "ru", "hi", "ta", "bn", "ar", "zh", "ja", "ko", "tr", "nl", "pl", "id")

internal fun languageName(code: String): String = Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).ifBlank { code }

/** Where Translate turns words into: the reader's choice, else the device's language (English when the book is in it already). */
internal fun translateTarget(preferences: ReadingPreferences, bookLanguage: String, device: String = Locale.getDefault().language): String {
    val chosen = preferences.translateTo.takeIf { it.isNotBlank() } ?: device.ifBlank { "en" }
    return if (chosen == bookLanguage.substringBefore('-').lowercase() && preferences.translateTo.isBlank()) (if (chosen == "en") "es" else "en") else chosen
}

/**
 * Look up (the selection's "Look up", or the reader's Dictionary): the words in a field that can be changed, then Dictionary (the
 * dictionaries on this device first, then Wiktionary), Wikipedia and Translate as chips. Recent look-ups stand under an empty field.
 */
@Composable
internal fun LookupSheet(repository: ReadingRepository, words: String, bookLanguage: String, onDismiss: () -> Unit) {
    val state by repository.state.collectAsUiState()
    val look = orbitLook()
    val scope = com.cosmotune.ui.rememberUiCoroutineScope()
    var query by remember { mutableStateOf(words.trim().take(200)) }
    var tab by remember { mutableStateOf(if (words.trim().contains(' ') && words.trim().length > 40) "Translate" else "Dictionary") }
    var definitions by remember { mutableStateOf(emptyList<ReadingDefinition>()) }
    var article by remember { mutableStateOf<ReadingArticle?>(null) }
    var translation by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val online = state.preferences.onlineLookup
    val target = translateTarget(state.preferences, bookLanguage)
    LaunchedEffect(query, tab, online, target) {
        definitions = emptyList(); article = null; translation = null; message = null
        val clean = query.trim()
        if (clean.isEmpty()) return@LaunchedEffect
        delay(350)
        loading = true
        try {
            when (tab) {
                "Dictionary" -> {
                    val offline = repository.defineOffline(clean)
                    definitions = offline.ifEmpty { if (online) repository.lookup.define(clean, bookLanguage.ifBlank { "en" }) else emptyList() }
                    if (definitions.isEmpty()) message = if (online || state.dictionaries.isNotEmpty()) "No definition found for “$clean”."
                        else "Add a dictionary in Settings › Read, or turn on looking words up online."
                }
                "Wikipedia" -> if (!online) message = "Turn on looking words up online in Settings › Read to read Wikipedia here."
                    else { article = repository.lookup.article(clean, bookLanguage.ifBlank { "en" }); if (article == null) message = "Wikipedia has no article on “$clean”." }
                else -> if (!online) message = "Turn on looking words up online in Settings › Read to translate."
                    else translation = repository.lookup.translate(clean, bookLanguage.ifBlank { "en" }, target)
            }
            repository.rememberLookup(clean)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { message = failure.message?.takeIf { it.length < 160 } ?: "This could not be looked up now. Check the connection." }
        finally { loading = false }
    }
    KitSheet(onDismiss = onDismiss) {
        ReadSheetHeader("Look up", onDone = onDismiss, doneLabel = "Close")
        ReadSearchField(query, { query = it.take(200) }, "A word or a name", Modifier.testTag("books:lookup:field"))
        ReadChipRow(Modifier.padding(top = 8.dp)) {
            listOf("Dictionary", "Wikipedia", "Translate").forEach { name -> ReadChip(name, tab == name, { tab = name }, Modifier.testTag("books:lookup:$name")) }
        }
        if (tab == "Translate") ReadChipRow(Modifier.padding(top = 8.dp)) {
            (listOf(target) + translateTargets.filter { it != target }).take(8).forEach { code ->
                ReadChip("To " + languageName(code), code == target, { scope.launch {
                    try { repository.updatePreferences(state.preferences.copy(translateTo = code)) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
                } }, Modifier.testTag("books:lookup:to:$code"))
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp).height(2.dp), color = look.accent)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp).padding(top = 12.dp)) {
            if (query.isBlank() && state.lookups.isNotEmpty()) {
                item { ReadOverline("Recent", inset = 20.dp) }
                items(state.lookups.take(20)) { word -> ReadSheetRow(word, CosmoIcons.Clock, { query = word }) }
            }
            message?.let { text -> item { Text(text, style = ReadType.body(), color = look.dim, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) } }
            items(definitions) { definition ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text((definition.heading.ifBlank { definition.source } + if (definition.heading.isNotBlank()) " · " + definition.source else "").uppercase(),
                        style = orbitFonts().label, color = look.dim)
                    Text(definition.text, style = ReadType.body(), color = look.ink, modifier = Modifier.padding(top = 4.dp))
                    if (definition.example.isNotBlank()) Text("“${definition.example}”", style = ReadType.body(), color = look.dim, modifier = Modifier.padding(top = 4.dp))
                }
            }
            article?.let { found -> item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text("WIKIPEDIA · " + found.title.uppercase(), style = orbitFonts().label, color = look.dim)
                    Text(found.extract, style = ReadType.body(), color = look.ink, modifier = Modifier.padding(top = 4.dp))
                }
            } }
            translation?.let { text -> item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text(("${languageName(bookLanguage.ifBlank { "en" })} to ${languageName(target)} · MyMemory").uppercase(), style = orbitFonts().label, color = look.dim)
                    Text(text, style = ReadType.body(), color = look.ink, modifier = Modifier.padding(top = 4.dp))
                }
            } }
        }
        Spacer(Modifier.height(16.dp))
    }
}
