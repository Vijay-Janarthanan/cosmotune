package com.cosmotune.manga.ui

import com.cosmotune.ui.orbit.OrbitInputField
import com.cosmotune.ui.orbit.OrbitRing
import com.cosmotune.ui.orbit.OrbitSwitch
import com.cosmotune.ui.orbit.orbitLook
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cosmotune.manga.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun MangaTemplateControls(repository: MangaRepository) {
    val state by repository.store.state.collectAsState()
    var open by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<MangaSourceConfig?>(null) }
    var editor by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var language by remember { mutableStateOf("") }
    var raw by remember { mutableStateOf(MangaHtmlTemplate.presets.first().encode()) }
    var local by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun action(block: suspend () -> Unit) = scope.launch {
        busy = true; error = null; result = null
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "Custom source could not be saved" }
        finally { busy = false }
    }
    fun configuration(): MangaSourceConfig {
        val next = MangaHtmlTemplate.parse(raw).configuration(address, name, language, local)
        val previous = editing ?: return next
        require(previous.baseUrl == next.baseUrl) { "Add a new source to change its website address" }
        return next.copy(id = previous.id, enabled = previous.enabled)
    }
    fun edit(config: MangaSourceConfig?) {
        editing = config; name = config?.name.orEmpty(); address = config?.baseUrl.orEmpty(); language = config?.language.orEmpty()
        raw = config?.settings?.get("template") ?: MangaHtmlTemplate.presets.first().encode()
        local = config?.settings?.get("allowLocalNetwork") == "true"; editor = true; error = null; result = null
    }
    ReadButton(mangaText("Custom websites and templates"), { open = true }, kind = ReadKind.TONAL)
    if (!open) return
    com.cosmotune.ui.sheets.CosmoAlertDialog(onDismissRequest = { if (!busy) { open = false; editor = false } }, title = if (editor) "Custom manga website" else "Custom manga sources",
        scroll = false,
        content = { Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (editor) {
                Text("Choose a known HTML family, or paste a declarative template: CSS selectors over a site's pages (\"type\": \"html\") or field paths over its JSON API (\"type\": \"json\"). A template defines requests and selectors; it does not run site scripts. Some sites require browser verification.")
                OrbitInputField(name, { if (it.length <= 120) name = it }, mangaText("Source name"), modifier = Modifier.fillMaxWidth().testTag("manga-template:name"))
                OrbitInputField(address, { if (it.length <= 4_096) address = it }, mangaText("Website URL"), modifier = Modifier.fillMaxWidth().testTag("manga-template:url"))
                OrbitInputField(language, { if (it.length <= 32) language = it }, mangaText("Language code"), modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { MangaHtmlTemplate.presets.forEach { preset -> ReadTextButton(preset.name, { raw = preset.encode(); result = null }, enabled = !busy) } }
                OrbitInputField(raw, { if (it.toByteArray(Charsets.UTF_8).size <= 128 * 1024) { raw = it; result = null } },
                    mangaText("Template (JSON text)"), modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 220.dp).testTag("manga-template:json"), singleLine = false, minLines = 6)
                Row(verticalAlignment = Alignment.CenterVertically) { OrbitSwitch(local, { local = it }, orbitLook(), "Allow this website on my local network"); Text("Allow this website on my local network", Modifier.padding(start = 12.dp), style = ReadType.body(), color = orbitLook().ink) }
                Text("Android still requires HTTPS for external servers. Existing music account cookies are never used by this source.", style = ReadType.caption(), color = orbitLook().dim)
                ReadTextButton(mangaText("Test catalogue"), enabled = !busy, onClick = { action {
                    val config = configuration()
                    val page = TemplateMangaSource(config, repository.context.httpClient).browse()
                    result = "Found ${page.books.size} series. Open a series after saving to check chapters and pages."
                } })
            } else {
                ReadTextButton(mangaText("Add website"), { edit(null) }, enabled = !busy)
                val sources = state.sources.filter { it.kind == "template" }
                if (sources.isEmpty()) Text(mangaText("No custom websites yet."))
                sources.forEach { config -> Column {
                    Row(verticalAlignment = Alignment.CenterVertically) { Text(config.name, Modifier.weight(1f), style = ReadType.rowTitle(), color = orbitLook().ink); OrbitSwitch(config.enabled, { enabled -> action { repository.store.source(config.copy(enabled = enabled)); repository.invalidateSource(config.id) } }, orbitLook(), "Use ${config.name}", enabled = !busy) }
                    Text(config.baseUrl, style = ReadType.caption(), color = orbitLook().dim)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReadTextButton("Edit ${config.name}", { edit(config) }, enabled = !busy)
                        ReadTextButton("Remove ${config.name}", { action { repository.store.update { it.copy(sources = it.sources.filterNot { source -> source.id == config.id }) }; repository.invalidateSource(config.id) } }, enabled = !busy)
                    }
                } }
            }
            if (busy) OrbitRing(size = 22.dp, color = orbitLook().accent, progress = null, spinning = true)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            result?.let { Text(it) }
        } },
        confirmLabel = if (editor) "Save website" else "Done",
        onConfirm = {
            if (editor) action { val config = configuration(); repository.store.source(config); repository.invalidateSource(config.id); editor = false }
            else open = false
        },
        confirmEnabled = !busy,
        dismissLabel = if (editor) mangaText("Back") else null,
        onDismiss = { editor = false; error = null },
        dismissEnabled = !busy,
    )
}
