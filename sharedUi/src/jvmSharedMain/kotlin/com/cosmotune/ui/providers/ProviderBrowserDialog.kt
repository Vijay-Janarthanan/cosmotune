package com.cosmotune.ui.providers

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cosmotune.music.data.model.Song
import com.cosmotune.providers.*
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitBusyRing
import com.cosmotune.ui.orbit.OrbitErrorText
import com.cosmotune.ui.orbit.OrbitFormField
import com.cosmotune.ui.orbit.OrbitPageSection
import com.cosmotune.ui.orbit.OrbitRoundButton
import com.cosmotune.ui.orbit.OrbitRowTime
import com.cosmotune.ui.orbit.OrbitSongRow
import com.cosmotune.ui.orbit.OrbitSongRowFor
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.sheets.CosmoActionRow
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

    val fonts = orbitFonts()
    val look = orbitLook()
    // A song of the page: Home's row, a tap plays it, its album and artists behind More (or a hold) when there are any.
    @Composable fun TrackRow(track: ProviderTrack, rows: List<ProviderTrack>) {
        val song = access.song(track)
        val more = links(track).isNotEmpty()
        OrbitSongRowFor(song, fonts, look, onClick = { play(rows, rows.indexOf(track)) }, modifier = Modifier.testTag("provider:track:${track.id}"),
            onLongClick = if (more) ({ selectedTrack = track }) else null,
            trailing = {
                OrbitRowTime(song.durationText, fonts, look)
                if (more) OrbitRoundButton(CosmoIcons.MoreVertical, "More", look.ink, onClick = { selectedTrack = track }, modifier = Modifier.padding(start = 6.dp), size = 36.dp)
            })
    }
    // An album, an artist or a playlist to open: the same row with its kind's mark for a disc (no remote picture is fetched for it).
    @Composable fun CollectionRow(page: ProviderCollection, modifier: Modifier = Modifier) {
        OrbitSongRow(page.title, page.subtitle, cover = null, fonts = fonts, look = look, onClick = { open(page) }, modifier = modifier.fillMaxWidth(),
            icon = when (page.key.kind) { "artist" -> CosmoIcons.User; "album" -> CosmoIcons.Library; else -> CosmoIcons.List },
            trailing = { androidx.compose.material3.Icon(CosmoIcons.ChevronRight, contentDescription = null, tint = look.dim, modifier = Modifier.size(18.dp)) })
    }
    ProviderPageWindow(::back, kind = if (path.isEmpty() && submitted.isBlank()) "Catalogue" else access.title, tag = "provider:browse",
        trailing = { OrbitRoundButton(CosmoIcons.Replay, "Refresh", look.ink, onClick = { revision++ }, enabled = !loading) }) {
                ProviderPageTitle(heading)
                if (path.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        OrbitFormField(query, { query = it }, "Search ${access.title}", Modifier.weight(1f), fieldModifier = Modifier.testTag("provider:query"))
                        ProviderButton("Search", { submitted = query.trim(); revision++ }, enabled = query.isNotBlank() && !loading, primary = true)
                    }
                    if (access.hasHome) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                            itemVerticalAlignment = Alignment.Bottom, modifier = Modifier.padding(vertical = 10.dp)) {
                            if (regional != null) OrbitFormField(region, { region = it.take(2) }, "Region", Modifier.width(110.dp), label = "Region")
                            if (languageKey != null) OrbitFormField(language, { language = it.take(100) }, "Language", Modifier.widthIn(min = 140.dp, max = 220.dp), label = "Language")
                            ProviderButton("Home", { path = emptyList(); submitted = ""; revision++ }, enabled = !loading, icon = CosmoIcons.Home,
                                modifier = Modifier.padding(bottom = 4.dp))
                        }
                    }
                }
                if (loading) OrbitBusyRing(Modifier.padding(vertical = 8.dp))
                if (failure) OrbitErrorText("This provider could not load the page. Check its connection settings and retry.", Modifier.padding(vertical = 12.dp))
                LazyColumn(Modifier.weight(1f).padding(top = 8.dp), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (tracks.isEmpty() && sections.isEmpty() && collection?.collections.isNullOrEmpty() && !loading && !failure) item {
                        Text(if (submitted.isNotBlank()) "No songs found." else "Search this provider to find songs.", color = look.dim, modifier = Modifier.padding(vertical = 16.dp))
                    }
                    items(tracks, key = { "track:${it.id}" }) { track -> TrackRow(track, tracks) }
                    items(collection?.collections.orEmpty(), key = { "collection:${it.key.encode()}" }) { page ->
                        CollectionRow(page, Modifier.testTag("provider:collection:${page.key.id}"))
                    }
                    sections.forEachIndexed { sectionIndex, section ->
                        item("heading:$sectionIndex") { OrbitPageSection(section.title, Modifier.padding(top = 20.dp, bottom = 4.dp)) }
                        items(section.tracks, key = { "s$sectionIndex:track:${it.id}" }) { track -> TrackRow(track, section.tracks) }
                        items(section.collections, key = { "s$sectionIndex:collection:${it.key.encode()}" }) { page -> CollectionRow(page) }
                    }
                }
    }
    selectedTrack?.let { track -> com.cosmotune.ui.sheets.CosmoAlertDialog(
        onDismissRequest = { selectedTrack = null },
        title = track.title,
        content = {
        Column { links(track).forEach { (title, key) ->
            CosmoActionRow(if (key.kind == "artist") CosmoIcons.User else CosmoIcons.Library, "${if (key.kind == "artist") "Artist" else "Album"} · $title",
                menu = true, onClick = { selectedTrack = null; path = path + key })
        } }
    },
        confirmLabel = "Close",
        onConfirm = { selectedTrack = null },
    ) }
}
