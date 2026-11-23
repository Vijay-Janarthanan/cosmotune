package com.cosmotune.manga.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cosmotune.manga.data.*
import com.cosmotune.ui.orbit.OrbitInputField
import com.cosmotune.ui.orbit.OrbitSwitch
import com.cosmotune.ui.orbit.orbitLook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Settings › Manga › Suwayomi: connect the listener's own Suwayomi server (its address, and a user name and password when it asks
 * for one), then add its library and any of the extensions installed on it as sources. Each added one has its own switch.
 */
@Composable
internal fun MangaSuwayomiControls(repository: MangaRepository, onError: (String?) -> Unit) {
    val state by repository.store.state.collectAsState()
    val look = orbitLook()
    val coroutines = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var server by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<SuwayomiCatalogue>?>(null) }
    Column(Modifier.fillMaxWidth().testTag("manga:suwayomi"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ReadButton(mangaText("Connect Suwayomi server"), { open = !open }, kind = ReadKind.TONAL)
        if (open) {
            Text(mangaText("Suwayomi runs Mihon extensions on your own computer or server. Its extensions then work here too."), style = ReadType.meta(), color = look.dim)
            OrbitInputField(server, { server = it.take(2_048) }, mangaText("Server URL"), modifier = Modifier.fillMaxWidth().testTag("manga:suwayomi:server"))
            OrbitInputField(user, { user = it.take(256) }, mangaText("User name (if the server asks)"), modifier = Modifier.fillMaxWidth())
            OrbitInputField(password, { password = it.take(512) }, mangaText("Password"), secret = true, modifier = Modifier.fillMaxWidth())
            ReadButton(mangaText(if (busy) "Connecting…" else "Connect"), enabled = !busy && server.isNotBlank(), modifier = Modifier.padding(top = 4.dp), onClick = { coroutines.launch {
                busy = true; onError(null)
                try {
                    val library = SuwayomiSource.configuration(server, user, password)
                    val client = SuwayomiSource(library, repository.context.httpClient)
                    found = client.catalogues()
                    repository.store.source(library); repository.invalidateSource(library.id)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { onError(failure.message ?: "Suwayomi could not be reached") }
                finally { busy = false }
            } })
            found?.let { list ->
                Text(if (list.isEmpty()) mangaText("The server's library was added. It has no extensions installed yet.")
                    else mangaText("The server's library was added. Add any of its extensions:"), style = ReadType.meta(), color = look.dim)
                list.take(200).forEach { catalogue ->
                    val config = SuwayomiSource.configuration(server, user, password, catalogue)
                    val added = state.sources.any { it.id == config.id }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(catalogue.name + if (catalogue.language.isNotBlank()) " · " + catalogue.language else "", style = ReadType.body(), color = look.ink, modifier = Modifier.weight(1f))
                        if (!added) ReadTextButton(mangaText("Add"), { coroutines.launch {
                            try { repository.store.source(config) } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { onError(failure.message) }
                        } }) else Text(mangaText("Added"), style = ReadType.meta(), color = look.dim)
                    }
                }
            }
        }
        state.sources.filter { it.kind == "suwayomi" }.forEach { config ->
            Row(Modifier.fillMaxWidth().testTag("manga:suwayomi:${config.id}"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f).padding(end = 4.dp)) {
                    Text(config.name, style = ReadType.rowTitle(), color = look.ink)
                    Text(config.baseUrl, style = ReadType.meta(), color = look.dim)
                }
                OrbitSwitch(config.enabled, onChange = { enabled -> coroutines.launch {
                    runCatching { repository.store.source(config.copy(enabled = enabled)); repository.invalidateSource(config.id) }
                } }, look = look, label = "Use ${config.name}")
                ReadTextButton(mangaText("Remove"), { coroutines.launch {
                    runCatching { repository.store.update { it.copy(sources = it.sources.filterNot { source -> source.id == config.id }) }; repository.invalidateSource(config.id) }
                } })
            }
        }
    }
}
