package com.cosmotune.ui.providers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.ui.components.SongRow
import com.cosmotune.providers.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Regional feeds and provider catalogue navigation share the app's existing song rows and playback queue. */
@Composable
internal fun ProviderBrowserDialog(manager: ProviderManager, installed: ProviderInstall, onDismiss: () -> Unit,
    onPlay: ((List<Song>, Int) -> Unit)?) {
    val state by manager.store.state.collectAsState()
    val current = state.installed.find { it.manifest.name == installed.manifest.name } ?: installed.copy(enabled = false)
    val enabled = state.installed.any { it.manifest.name == current.manifest.name && it.enabled }
    val access = remember(manager, current, state.settings[current.manifest.name], enabled) { object : CatalogueBrowserAccess {
        override val identity = current.manifest.name
        override val title = current.manifest.title
        override val revision = current.sha256 + "|" + current.allowArtwork + "|" + current.allowLocalNetwork + "|" + state.settings[identity].toString()
        override val enabled = state.installed.any { it.manifest.name == identity && it.enabled }
        override val settings = current.manifest.effectiveSettings(state.settings[identity].orEmpty())
        override val regionKey = (current.manifest.capabilities["homeFeedRegionSetting"] as? JsonPrimitive)?.contentOrNull
        override val languageKey = current.manifest.settings.firstOrNull { it.key in setOf("lang", "language", "locale") }?.key
        override val hasHome = current.manifest.homeFeed
        override suspend fun search(query: String, force: Boolean) = CatalogueSearchPage(manager.search(identity, query, 100))
        override suspend fun catalogue(key: ProviderBrowseKey, force: Boolean) = manager.catalogue(key)
        override suspend fun home(region: String, language: String, force: Boolean) = manager.home(identity, region, language, force)
        override fun song(track: ProviderTrack) = track.song()
    } }
    CatalogueBrowserDialog(access, onDismiss, onPlay)
}

@Composable
fun CatalogueBrowserDialog(access: CatalogueBrowserAccess, onDismiss: () -> Unit, onPlay: ((List<Song>, Int) -> Unit)?) {
    val name = access.identity
    val effective = access.settings
    val languageKey = access.languageKey
    val regional = access.regionKey
    var region by remember(name) { mutableStateOf(regional?.let { effective[it]?.jsonPrimitive?.contentOrNull }
        ?: java.util.Locale.getDefault().country.ifBlank { "IN" }) }
    var language by remember(name) { mutableStateOf(languageKey?.let { effective[it]?.jsonPrimitive?.contentOrNull } ?: java.util.Locale.getDefault().language) }
    var query by remember(name) { mutableStateOf("") }
    var submitted by remember(name) { mutableStateOf("") }
    var path by remember(name) { mutableStateOf(emptyList<ProviderBrowseKey>()) }
    var revision by remember(name) { mutableIntStateOf(0) }
    var lastRevision by remember(name) { mutableIntStateOf(0) }
    var generation by remember(name) { mutableLongStateOf(0) }
    var loading by remember(name) { mutableStateOf(false) }
    var failure by remember(name) { mutableStateOf(false) }
    var tracks by remember(name) { mutableStateOf(emptyList<ProviderTrack>()) }
    var sections by remember(name) { mutableStateOf(emptyList<ProviderHomeSection>()) }
    var collection by remember(name) { mutableStateOf<ProviderCollection?>(null) }
    var heading by remember(name) { mutableStateOf(access.title) }
    var selectedTrack by remember(name) { mutableStateOf<ProviderTrack?>(null) }
    val key = path.lastOrNull()
    LaunchedEffect(access.enabled) { if (!access.enabled) onDismiss() }
    LaunchedEffect(name, key, submitted, revision, access.revision) {
        val request = ++generation
        val force = revision != lastRevision
        lastRevision = revision
        loading = true; failure = false
        try {
            when {
                key != null -> {
                    val page = access.catalogue(key, force)
                    currentCoroutineContext().ensureActive()
                    collection = page; heading = page.title; tracks = page.tracks; sections = emptyList()
                }
                submitted.isNotBlank() -> {
                    val result = access.search(submitted, force)
                    currentCoroutineContext().ensureActive()
                    tracks = result.tracks; sections = emptyList(); collection = ProviderCollection(ProviderBrowseKey(name, "playlist", "search"), "Search results", collections = result.collections); heading = "Search results"
                }
                access.hasHome -> {
                    val feed = access.home(region, language, force)
                    currentCoroutineContext().ensureActive()
                    sections = feed; tracks = emptyList(); collection = null; heading = access.title
                }
                else -> { tracks = emptyList(); sections = emptyList(); collection = null; heading = access.title }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (request == generation) failure = true }
        finally { if (request == generation) loading = false }
    }
    fun back() { if (path.isNotEmpty()) path = path.dropLast(1) else onDismiss() }
    fun open(page: ProviderCollection) { path = path + page.key }
    fun play(rows: List<ProviderTrack>, index: Int) { onPlay?.invoke(rows.map(access::song), index) }
    fun links(track: ProviderTrack): List<Pair<String, ProviderBrowseKey>> = buildList {
        track.albumId?.let { add((track.album ?: "Album") to ProviderBrowseKey(track.provider, "album", it, track.namespace)) }
        track.artists.forEach { artist -> artist.browseId?.let(ProviderBrowseKey::parse)?.let { add(artist.name to it) } }
    }.filter { it.second.kind in access.supportedKinds }.distinctBy { it.second }

    Dialog(onDismissRequest = ::back, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxHeight().widthIn(max = 920.dp).fillMaxWidth()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(horizontal = 16.dp).testTag("provider:browse")) {
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = ::back) { Text("Back") }
                    Text(heading, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { revision++ }, enabled = !loading) { Text("Refresh") }
                }
                if (path.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(query, { query = it }, label = { Text("Search ${access.title}") },
                            singleLine = true, modifier = Modifier.weight(1f).testTag("provider:query"))
                        TextButton(onClick = { submitted = query.trim(); revision++ }, enabled = query.isNotBlank() && !loading) { Text("Search") }
                    }
                    if (access.hasHome) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                            if (regional != null) OutlinedTextField(region, { region = it.take(2) }, label = { Text("Region") },
                                singleLine = true, modifier = Modifier.width(100.dp))
                            if (languageKey != null) OutlinedTextField(language, { language = it.take(100) }, label = { Text("Language") },
                                singleLine = true, modifier = Modifier.widthIn(max = 220.dp))
                            TextButton(onClick = { path = emptyList(); submitted = ""; revision++ }, enabled = !loading) { Text("Home") }
                        }
                    }
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (failure) Text("This provider could not load the page. Check its connection settings and retry.", color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 12.dp))
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                    if (tracks.isEmpty() && sections.isEmpty() && collection?.collections.isNullOrEmpty() && !loading && !failure) item {
                        Text(if (submitted.isNotBlank()) "No songs found." else "Search this provider to find songs.", modifier = Modifier.padding(16.dp))
                    }
                    items(tracks, key = { "track:${it.id}" }) { track ->
                        SongRow(access.song(track), onClick = { play(tracks, tracks.indexOf(track)) },
                            onMore = if (links(track).isNotEmpty()) ({ selectedTrack = track }) else null,
                            modifier = Modifier.testTag("provider:track:${track.id}"))
                    }
                    items(collection?.collections.orEmpty(), key = { "collection:${it.key.encode()}" }) { page ->
                        ListItem(headlineContent = { Text(page.title) }, supportingContent = { Text(page.subtitle) },
                            modifier = Modifier.clickable { open(page) }.testTag("provider:collection:${page.key.id}"))
                    }
                    sections.forEachIndexed { sectionIndex, section ->
                        item("heading:$sectionIndex") { Text(section.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)) }
                        items(section.tracks, key = { "s$sectionIndex:track:${it.id}" }) { track ->
                            SongRow(access.song(track), onClick = { play(section.tracks, section.tracks.indexOf(track)) },
                                onMore = if (links(track).isNotEmpty()) ({ selectedTrack = track }) else null,
                                modifier = Modifier.testTag("provider:track:${track.id}"))
                        }
                        items(section.collections, key = { "s$sectionIndex:collection:${it.key.encode()}" }) { page ->
                            ListItem(headlineContent = { Text(page.title) }, supportingContent = { Text(page.subtitle) }, modifier = Modifier.clickable { open(page) })
                        }
                    }
                }
            }
        }
    }
    selectedTrack?.let { track -> AlertDialog(onDismissRequest = { selectedTrack = null }, title = { Text(track.title) }, text = {
        Column { links(track).forEach { (title, key) ->
            TextButton(onClick = { selectedTrack = null; path = path + key }) { Text("${if (key.kind == "artist") "Artist" else "Album"} · $title") }
        } }
    }, confirmButton = { TextButton(onClick = { selectedTrack = null }) { Text("Close") } }) }
}
