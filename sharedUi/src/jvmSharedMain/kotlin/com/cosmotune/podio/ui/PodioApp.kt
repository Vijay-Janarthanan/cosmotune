package com.cosmotune.podio.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.cosmotune.podio.*
import java.io.File
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cosmotune.ui.blocks.ModeNav
import com.cosmotune.ui.blocks.ModeTabs
import kotlinx.coroutines.*

data class PodioPlayback(val id: String? = null, val title: String = "", val author: String = "", val playing: Boolean = false,
    val loading: Boolean = false, val positionMs: Long = 0, val durationMs: Long = 0, val error: String? = null, val rate: Float = 1f)

/** Genres of Apple's podcast chart (id, name). */
private val PODCAST_GENRES = listOf("1489" to "News", "1303" to "Comedy", "1318" to "Technology", "1321" to "Business", "1324" to "Society & culture",
    "1304" to "Education", "1512" to "Health & fitness", "1545" to "Sports", "1488" to "True crime", "1487" to "History", "1533" to "Science",
    "1310" to "Music", "1301" to "Arts", "1305" to "Kids & family", "1309" to "TV & film", "1483" to "Fiction")
private val REGIONS = listOf("" to "World", "IN" to "India", "LK" to "Sri Lanka", "US" to "US", "GB" to "UK", "CA" to "Canada", "AU" to "Australia", "DE" to "Germany",
    "FR" to "France", "ES" to "Spain", "IT" to "Italy", "BR" to "Brazil", "MX" to "Mexico", "JP" to "Japan", "KR" to "South Korea", "AE" to "UAE", "SG" to "Singapore",
    "MY" to "Malaysia", "PK" to "Pakistan", "BD" to "Bangladesh", "NP" to "Nepal", "NG" to "Nigeria", "ZA" to "South Africa", "ID" to "Indonesia", "TR" to "Türkiye", "RU" to "Russia", "NL" to "Netherlands")
private val LANGUAGES = listOf("" to "All languages", "Tamil" to "Tamil", "Telugu" to "Telugu", "Hindi" to "Hindi", "English" to "English", "Malayalam" to "Malayalam",
    "Kannada" to "Kannada", "Marathi" to "Marathi", "Bengali" to "Bengali", "Punjabi" to "Punjabi", "Gujarati" to "Gujarati", "Urdu" to "Urdu", "Sinhala" to "Sinhala",
    "Arabic" to "Arabic", "Spanish" to "Spanish", "French" to "French", "German" to "German", "Portuguese" to "Portuguese", "Italian" to "Italian", "Russian" to "Russian",
    "Japanese" to "Japanese", "Korean" to "Korean", "Chinese" to "Chinese", "Turkish" to "Turkish", "Indonesian" to "Indonesian")

/** The same account-free radio/podcast page is used by Android and every desktop target. */
@Composable
fun PodioApp(storage: File, playback: PodioPlayback, onPlay: (PodioItem, Long) -> Unit,
    onToggle: () -> Unit, onSeek: (Long) -> Unit, onSpeed: (Float) -> Unit, onMusic: () -> Unit = {},
    padding: PaddingValues = PaddingValues(), directory: PodioCatalogue? = null,
    /** False on the desktop, where the sidebar chooses Radio, Podcasts, Favourites and Following (see [ModeNav]). */
    showNavigation: Boolean = true,
    /** The desktop's volume (0..1) and a way to set it; null on the phone, where the volume keys do it. */
    volume: Float = 1f, onVolume: ((Float) -> Unit)? = null,
    /** Opens the application's settings; null hides the Settings tab. */
    onSettings: (() -> Unit)? = null) {
    val catalogue = remember(directory) { directory ?: PodioCatalogue() }
    val library = remember(storage) { PodioLibrary(storage) }
    val saved by library.state.collectAsState()
    val scope = rememberCoroutineScope()
    var localTab by rememberSaveable { mutableStateOf("Radio") }
    var localSaved by rememberSaveable { mutableStateOf(false) }
    val sidebarTab by ModeNav.podioTab.collectAsState()
    val sidebarSaved by ModeNav.podioSaved.collectAsState()
    val tab = if (showNavigation) localTab else sidebarTab
    val savedOnly = if (showNavigation) localSaved else sidebarSaved
    fun chooseTab(next: String) { if (showNavigation) { localTab = next; localSaved = false } else { ModeNav.podioTab.value = next; ModeNav.podioSaved.value = false } }
    fun chooseSaved(next: Boolean) { if (showNavigation) localSaved = next else ModeNav.podioSaved.value = next }
    var query by rememberSaveable { mutableStateOf("") }
    var language by rememberSaveable { mutableStateOf("") }
    var country by rememberSaveable { mutableStateOf("") }
    var genre by rememberSaveable { mutableStateOf("") }
    var radio by remember { mutableStateOf(emptyList<RadioStation>()) }
    var shows by remember { mutableStateOf(emptyList<PodcastShow>()) }
    var showsTitle by remember { mutableStateOf("") }
    var opened by remember { mutableStateOf<PodcastFeed?>(null) }
    var selected by remember { mutableStateOf<RadioStation?>(null) }
    var nearby by remember { mutableStateOf(emptyList<RadioStation>()) }
    var directoryLoading by remember { mutableStateOf(false) }
    var feedLoading by remember { mutableStateOf(false) }
    var requestedFeed by remember { mutableStateOf<PodcastShow?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var request by remember { mutableIntStateOf(0) }
    var addFeed by remember { mutableStateOf(false) }
    var feedAddress by remember { mutableStateOf("") }
    var openJob by remember { mutableStateOf<Job?>(null) }
    var panelOpen by rememberSaveable { mutableStateOf(false) }
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    var panelTab by rememberSaveable { mutableStateOf("Explore") }
    var localDetail by rememberSaveable { mutableStateOf(false) }
    val sidebarDetail by ModeNav.podioDetail.collectAsState()
    val detailOpen = if (showNavigation) localDetail else sidebarDetail
    fun setDetail(open: Boolean) { if (showNavigation) localDetail = open else ModeNav.podioDetail.value = open }
    val searchFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(panelTab) { if (panelTab == "Search") runCatching { searchFocus.requestFocus() } }
    val loading = feedLoading || requestedFeed == null && directoryLoading
    val latestPlayback by rememberUpdatedState(playback)
    fun save(change: (PodioSaved) -> PodioSaved) { scope.launch {
        try { withContext(Dispatchers.IO) { library.update(change) } }
        catch(e: CancellationException) { throw e }
        catch (_: Exception) { error = "Couldn't save your Podio library. Check available storage and retry." }
    } }
    fun toggleFavourite(station: RadioStation) = save { old -> old.copy(stations = if (old.stations.any { it.id == station.id }) old.stations.filterNot { it.id == station.id } else old.stations + station) }
    fun play(item: PodioItem) {
        val old = saved.progress[item.id] ?: 0
        val resume = old.takeIf { !item.live && (item.durationSeconds <= 0 || it < item.durationSeconds * 1000 - 10_000) } ?: 0
        onPlay(item, resume)
    }
    fun playStation(station: RadioStation) { selected = station; play(station.item()); scope.launch { catalogue.stationPlayed(station.id) } }
    fun open(show: PodcastShow) {
        val previous = openJob
        previous?.cancel()
        requestedFeed = show
        openJob = scope.launch {
            previous?.join()
            feedLoading = true; error = null
            try { opened = catalogue.feed(show.feed) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = if (PodioCatalogue.isSpotifyLink(show.feed))
                "No matching public feed was available for this Spotify link. Try the publisher's RSS feed; Spotify-only audio isn't supported." else "Couldn't load this podcast feed. Please retry." }
            finally { feedLoading = false }
        }
    }
    fun browse() {
        // Keep the cancelled job so a subsequent open can join its final state update.
        openJob?.cancel()
        requestedFeed = null; opened = null; feedLoading = false; error = null
    }
    // A change of section (from the tabs, or the desktop sidebar) leaves an open feed and starts the new section clean.
    LaunchedEffect(tab, savedOnly) { browse(); query = ""; genre = "" }
    LaunchedEffect(tab, language, country, genre, request, savedOnly) {
        // Network continuations can resume on a worker with an immediate effect dispatcher.
        // Publish page state on the UI dispatcher.
        withContext(Dispatchers.Main.immediate) {
            if (savedOnly) return@withContext
            directoryLoading = true
            if (requestedFeed == null) error = null
            try {
                if (tab == "Radio") radio = catalogue.stations(query, country, language)
                else {
                    val storefront = country.ifBlank { java.util.Locale.getDefault().country.ifBlank { "US" } }
                    val genreName = PODCAST_GENRES.firstOrNull { it.first == genre }?.second
                    val typed = query.trim()
                    if (typed.isNotBlank()) { shows = catalogue.podcasts(listOf(typed, language).filter(String::isNotBlank).joinToString(" "), storefront); showsTitle = "Results for \"$typed\"" }
                    else if (language.isBlank()) {
                        val chart = runCatching { catalogue.topPodcasts(storefront, genre.ifBlank { null }) }.getOrDefault(emptyList())
                        shows = chart.ifEmpty { catalogue.podcasts(genreName ?: "popular podcasts", storefront) }
                        showsTitle = (genreName ?: "Popular") + " · " + REGIONS.firstOrNull { it.first == country }?.second.orEmpty().ifBlank { "top charts" }
                    } else {
                        shows = catalogue.podcasts("$language ${genreName ?: "podcasts"}", storefront)
                        showsTitle = "$language ${genreName ?: "podcasts"}"
                    }
                }
            } catch(e: CancellationException) { throw e }
            catch (_: Exception) { if (requestedFeed == null) error = "Couldn't reach the directory. Check your connection and try again." }
            finally { directoryLoading = false }
        }
    }
    LaunchedEffect(playback.id) {
        if (playback.id?.startsWith("podio:episode:") != true) return@LaunchedEffect
        try {
            while (isActive) {
                delay(5_000)
                val current = latestPlayback
                if (current.id != playback.id) break
                withContext(Dispatchers.IO) { runCatching { library.update { it.copy(progress = it.progress + (requireNotNull(current.id) to current.positionMs)) } } }
            }
        } finally {
            val current = latestPlayback
            if (current.id == playback.id) withContext(NonCancellable + Dispatchers.IO) {
                runCatching { library.update { it.copy(progress = it.progress + (requireNotNull(current.id) to current.positionMs)) } }
            }
        }
    }
    val live = playback.id?.startsWith("podio:radio:") == true
    val stations = if (savedOnly) saved.stations else radio
    val playingStation = if (live) (stations + saved.stations + listOfNotNull(selected)).firstOrNull { it.item().id == playback.id } else null
    // What the station is playing now, from its own stream; asked again every half minute while it plays.
    var song by remember(playback.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(playback.id, playingStation?.url) {
        val url = playingStation?.url ?: return@LaunchedEffect
        while (isActive) { song = catalogue.nowPlaying(url)?.takeIf { it != playingStation.name }; delay(30_000) }
    }
    Surface(Modifier.fillMaxSize().testTag("podio:root"), color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val wide = maxWidth > 760.dp
    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = if (wide) 24.dp else 12.dp)) {
        // On a phone the tabs are the title: the room goes to the map and the list.
        if (wide || !showNavigation) Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                val title = if (showNavigation) "Podio" else when { tab == "Radio" && savedOnly -> "Favourite stations"; tab == "Radio" -> "Radio"; savedOnly -> "Following"; else -> "Podcasts" }
                Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (showNavigation) Text("Explore live radio and your next favourite podcast.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (tab == "Podcasts") TextButton(onClick = { addFeed = true }) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Text("Add link") }
        }
        if (showNavigation && (wide || tab == "Podcasts")) Row(Modifier.fillMaxWidth().padding(top = if (wide) 0.dp else 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeTabs(listOf("Radio", "Podcasts"), tab, { browse(); chooseTab(it); query = "" }, Modifier.weight(1f, fill = false).widthIn(max = 440.dp))
            FilterChip(selected = savedOnly, onClick = { browse(); chooseSaved(!savedOnly) }, label = { Text(if (tab == "Radio") "Favourites" else "Following") })
            if (!wide && tab == "Podcasts") TextButton(onClick = { addFeed = true }) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Text("Add link") }
        }
        val filtersBar: @Composable (Modifier) -> Unit = { box ->
            Row(box.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PodioFilter("Region", country, REGIONS) { browse(); country = it }
                PodioFilter("Language", language, LANGUAGES) { browse(); language = it }
                if (country.isNotBlank() || language.isNotBlank() || query.isNotBlank() || genre.isNotBlank()) TextButton(onClick = { browse(); country = ""; language = ""; query = ""; genre = ""; request++ }) { Text("Clear") }
            }
        }
        val searchField: @Composable (Modifier) -> Unit = { modifier ->
            OutlinedTextField(query, { query = it }, singleLine = true, label = { Text(if (tab == "Radio") "Find a station" else "Find a podcast") }, modifier = modifier.focusRequester(searchFocus),
                shape = RoundedCornerShape(16.dp),
                trailingIcon = { IconButton(onClick = { browse(); chooseSaved(false); request++ }) { Icon(Icons.Outlined.Search, "Search") } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { browse(); chooseSaved(false); request++ }))
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 2.dp).testTag("podio:loading"))
        error?.let { message -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { requestedFeed?.let { open(it) } ?: run { request++ } }) { Text("Retry") }
        } }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (tab == "Radio") {
                val ordered = remember(nearby, stations, panelTab) { if (panelTab == "Search") stations.take(400) else nearby.take(400) }
                val grouped = remember(nearby) { PodioPlaces.group(nearby) }
                // A new place under the ring starts the list from its first station, not from wherever the old list was scrolled to.
                val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                LaunchedEffect(nearby.firstOrNull()?.id, panelTab) { listState.scrollToItem(0) }
                val countText = "${stations.size} ${if (stations.size == 1) "station" else "stations"}" + if (country.isBlank()) " · Worldwide" else " · " + (REGIONS.firstOrNull { it.first == country }?.second ?: country)
                val mapBox: @Composable (Modifier, Alignment) -> Unit = { box, controls ->
                    Box(box.clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                        PodioGlobe(stations, playingStation?.id ?: selected?.id, { nearby = it }, Modifier.fillMaxSize(), focus = selected, tiles = catalogue.tiles, controlsAlignment = controls)
                    }
                }
                val panel: @Composable (Modifier) -> Unit = { box ->
                    Column(box, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Surface(Modifier.fillMaxWidth().weight(1f), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
                            Column(Modifier.fillMaxSize()) {
                                Row(Modifier.fillMaxWidth().then(if (wide) Modifier else Modifier.clickable { panelOpen = !panelOpen }).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                                        Text((if (nearby.isNotEmpty() && panelTab == "Explore") nearby.size else stations.size).toString(), color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(when { panelTab == "Favorites" -> "Favourite stations"; panelTab == "Browse" -> "Browse"; panelTab == "Search" -> "Search results"
                                            grouped.size == 1 -> grouped[0].name; grouped.size > 1 -> "Stations nearby"; else -> "Stations" },
                                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(when { panelTab != "Explore" -> countText
                                            grouped.isNotEmpty() && grouped.all { it.approximate } -> "Approximate place"
                                            grouped.size > 1 -> "${grouped.size} places under the ring"; else -> "Drag the map to pick a place" },
                                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    if (panelTab == "Explore" && grouped.size == 1) Text(PodioPlaces.localTime(grouped[0].stations.first().countryCode, grouped[0].longitude),
                                        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (!wide) Icon(if (panelOpen) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess, if (panelOpen) "Hide stations" else "Show stations")
                                }
                                HorizontalDivider()
                                if (wide || panelOpen) LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                      if (panelTab == "Favorites") {
                                        if (saved.stations.isEmpty()) item { Text("Save a station with the heart button.", Modifier.padding(16.dp)) }
                                        items(saved.stations, key = { "fav-" + it.id }) { station -> RadioRow(station, true, playback.id == station.item().id, onPlay = { playStation(station) }, onSave = { toggleFavourite(station) }) }
                                      } else if (panelTab == "Browse") {
                                        item { Text("Regions", Modifier.padding(10.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                        items(REGIONS, key = { "r-" + it.first }) { (id, name) -> BrowseRow(name, country == id) { browse(); country = id; panelTab = "Explore" } }
                                        item { Text("Languages", Modifier.padding(10.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                        items(LANGUAGES, key = { "l-" + it.first }) { (id, name) -> BrowseRow(name, language == id) { browse(); language = id; panelTab = "Explore" } }
                                      } else {
                                        if (ordered.isEmpty() && !loading) item { Text(if (savedOnly) "Save a station with the heart button." else if (stations.isNotEmpty() && panelTab == "Explore") "No station under the ring. Drag the map to a dot, or open Search for the full list." else "No stations found. Try another name, country or language.", Modifier.padding(16.dp)) }
                                        itemsIndexed(ordered, key = { _, it -> it.id }) { index, station ->
                                            if (nearby.isNotEmpty() && index == nearby.size) Text("More stations", Modifier.padding(start = 10.dp, top = 10.dp, bottom = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            RadioRow(station, saved.stations.any { it.id == station.id }, playback.id == station.item().id, onPlay = { playStation(station) }, onSave = { toggleFavourite(station) })
                                        }
                                        item { Text("Stations: Radio Browser · Imagery: NASA GIBS", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(10.dp)) }
                                      }
                                }
                                HorizontalDivider()
                                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                                    PanelTab("Explore", Icons.Outlined.Public, panelTab == "Explore") { panelTab = "Explore" }
                                    PanelTab("Favorites", Icons.Outlined.FavoriteBorder, panelTab == "Favorites") { panelTab = "Favorites"; panelOpen = true }
                                    PanelTab("Browse", Icons.Outlined.Map, panelTab == "Browse") { panelTab = "Browse"; panelOpen = true }
                                    PanelTab("Search", Icons.Outlined.Search, panelTab == "Search") { panelTab = "Search"; panelOpen = true }
                                    if (!wide && showNavigation) PanelTab("Podcasts", Icons.Outlined.Podcasts, false) { browse(); chooseTab("Podcasts"); query = "" }
                                    onSettings?.let { open -> PanelTab("Settings", Icons.Outlined.Settings, false, open) }
                                }
                            }
                        }
                        playingStation?.let { station ->
                            val sequence = if (ordered.any { it.id == station.id }) ordered else stations
                            val index = sequence.indexOfFirst { it.id == station.id }
                            LiveCard(station, playback, song, saved.stations.any { it.id == station.id }, onToggle = onToggle, onSave = { toggleFavourite(station) },
                                volume = volume, onVolume = onVolume, onOpen = { setDetail(true) }, compact = !wide,
                                onPrevious = sequence.getOrNull(index - 1)?.takeIf { index > 0 }?.let { { playStation(it) } },
                                onNext = sequence.getOrNull(index + 1)?.takeIf { index >= 0 }?.let { { playStation(it) } })
                        }
                    }
                }
                if (!wide) Box(Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp))) {
                    // The globe fills the page; a search pill floats on top, and one small stack (place, player, tabs) sits at the bottom.
                    mapBox(Modifier.fillMaxSize().padding(bottom = 205.dp), Alignment.CenterEnd)
                    Column(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            searchField(Modifier.weight(1f).background(MaterialTheme.colorScheme.surface.copy(alpha = .9f), RoundedCornerShape(16.dp)))
                            FilledTonalIconButton(onClick = { filtersOpen = !filtersOpen }, modifier = Modifier.size(48.dp).semantics { contentDescription = "Filters" }) { Icon(Icons.Outlined.Tune, null) }
                        }
                        if (filtersOpen) Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .94f), tonalElevation = 3.dp) {
                            Column(Modifier.padding(8.dp)) { filtersBar(Modifier.fillMaxWidth()); Text(countText, Modifier.padding(start = 4.dp, top = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                    panel(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(10.dp).height(if (panelOpen) 420.dp else 212.dp))
                } else Column(Modifier.fillMaxSize()) {
                    // One row of tools above the map and the panel: nothing floats over anything else.
                    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        searchField(Modifier.width(380.dp))
                        filtersBar(Modifier.weight(1f, fill = false))
                        Spacer(Modifier.weight(1f))
                        if (!loading) Text(countText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        panel(Modifier.width(380.dp).fillMaxHeight()); mapBox(Modifier.weight(1f).fillMaxHeight(), Alignment.BottomEnd)
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    if (opened == null) {
                        searchField(Modifier.fillMaxWidth().padding(bottom = 8.dp))
                        filtersBar(Modifier.fillMaxWidth())
                        if (language.isBlank() && query.isBlank()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = genre.isBlank(), onClick = { browse(); genre = "" }, label = { Text("Popular") })
                            PODCAST_GENRES.forEach { (id, name) -> FilterChip(selected = genre == id, onClick = { browse(); genre = id }, label = { Text(name) }) }
                        }
                    }
                    val feed = opened
                    if (feed != null) LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
                        item {
                            TextButton(onClick = ::browse) { Text("Back to podcasts") }
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                                if (feed.show.artwork.isNotBlank()) AsyncImage(feed.show.artwork, null, Modifier.size(if (wide) 140.dp else 96.dp).clip(RoundedCornerShape(18.dp)))
                                Column(Modifier.weight(1f)) {
                                    Text(feed.show.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                    Text(feed.show.author, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(feed.show.source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Row {
                                        val following = saved.shows.any { it.id == feed.show.id }
                                        FilledTonalButton(onClick = { save { old -> old.copy(shows = if (following) old.shows.filterNot { it.id == feed.show.id } else old.shows + feed.show) } }) { Text(if (following) "Following" else "Follow") }
                                        TextButton(onClick = { open(feed.show) }) { Text("Refresh") }
                                    }
                                }
                            }
                            if (feed.episodes.isEmpty()) Text("This feed contains no playable audio episodes.", Modifier.padding(vertical = 20.dp))
                        }
                        items(feed.episodes, key = { it.id }) { episode ->
                            ListItem(headlineContent = { Text(episode.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                supportingContent = { Text(listOf(episode.published.take(25), episode.durationSeconds.takeIf { it > 0 }?.let { "${it / 60} min" }.orEmpty()).filter(String::isNotBlank).joinToString(" · ")) },
                                leadingContent = { Icon(Icons.Outlined.PlayArrow, null) }, modifier = Modifier.clickable { play(episode.item()) })
                        }
                    } else {
                        val list = if (savedOnly) saved.shows else shows
                        if (list.isEmpty() && !loading) Text(if (savedOnly) "Follow a podcast to keep it here." else "Nothing found. Try another search, language or topic—or add a show's RSS feed.", Modifier.padding(16.dp))
                        LazyVerticalGrid(GridCells.Adaptive(if (wide) 168.dp else 150.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            if (!savedOnly && showsTitle.isNotBlank() && list.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                                Text(showsTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                            }
                            items(list, key = { it.id }) { show -> ShowCard(show) { open(show) } }
                            item(span = { GridItemSpan(maxLineSpan) }) { Text("Discovery: Apple Podcasts and fyyd · Audio: publisher feeds", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(12.dp)) }
                        }
                    }
                }
            }
        }
        if (playback.id?.startsWith("podio:") == true && !(live && tab == "Radio")) PodioPlayerBar(playback, onToggle, onSeek, onSpeed)
    }
    val station = playingStation
    if (detailOpen && station != null) {
        val here = remember(stations, station.id) { PodioPlaces.group(stations).firstOrNull { place -> place.stations.any { it.id == station.id } }?.stations.orEmpty().filter { it.id != station.id } }
        val around = remember(stations, here) { (here + stations.filter { s -> here.none { it.id == s.id } && s.id != station.id }).take(40) }
        val index = stations.indexOfFirst { it.id == station.id }
        RadioDetail(station, playback, song, saved.stations.any { it.id == station.id }, catalogue, here, volume, onVolume, onToggle, { toggleFavourite(station) },
            onPrevious = stations.getOrNull(index - 1)?.takeIf { index > 0 }?.let { { playStation(it) } },
            onNext = stations.getOrNull(index + 1)?.takeIf { index >= 0 }?.let { { playStation(it) } },
            onPlayStation = { playStation(it) }, onBack = { setDetail(false) }, wide = wide, modifier = Modifier.padding(padding))
    }
    }
    }
    if (addFeed) AlertDialog(onDismissRequest = { addFeed = false }, title = { Text("Open a podcast") },
        text = { OutlinedTextField(feedAddress, { feedAddress = it }, label = { Text("RSS feed or Spotify podcast link") }, singleLine = true) },
        confirmButton = { TextButton(onClick = {
            val url = PodioCatalogue.httpUrl(feedAddress)
            if (url == null) { error = "Enter an HTTP or HTTPS feed URL."; addFeed = false }
            else { addFeed = false; chooseTab("Podcasts"); open(PodcastShow(PodioCatalogue.identity(url), "", "", url)) }
        }) { Text("Open podcast") } }, dismissButton = { TextButton(onClick = { addFeed = false }) { Text("Cancel") } })
}

/** The station's logo; a station with none (or one that does not load) gets a tile in its own colour with its initials. */
@Composable internal fun StationLogo(station: RadioStation, size: androidx.compose.ui.unit.Dp) {
    var loaded by remember(station.artwork) { mutableStateOf(false) }
    val hue = (station.name.fold(7) { total, char -> total * 31 + char.code } and 0x7fffffff) % 360
    val initials = station.name.split(' ', '-', '.', '_').filter(String::isNotBlank).take(2).joinToString("") { it.first().uppercase() }.ifBlank { "FM" }
    Box(Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(if (loaded) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.hsv(hue.toFloat(), .5f, .5f)), contentAlignment = Alignment.Center) {
        if (!loaded) Text(initials, color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * .36f).sp, maxLines = 1)
        if (station.artwork.isNotBlank()) AsyncImage(station.artwork, null, Modifier.fillMaxSize(), onSuccess = { loaded = true })
    }
}

internal fun RadioStation.placeLine(): String = listOf(state, country).filter(String::isNotBlank).distinct().joinToString(", ")

@Composable private fun RadioRow(station: RadioStation, saved: Boolean, current: Boolean, onPlay: () -> Unit, onSave: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
        .background(if (current) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
        .clickable(onClick = onPlay).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StationLogo(station, 42.dp)
        Column(Modifier.weight(1f)) {
            Text(station.name, fontWeight = if (current) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(station.placeLine(), station.language, listOf(station.codec, station.bitrate.takeIf { it > 0 }?.let { "$it kbps" }.orEmpty()).filter(String::isNotBlank).joinToString(" ")).filter(String::isNotBlank).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onSave) { Icon(if (saved) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder, if (saved) "Remove favourite" else "Save favourite") }
    }
}

/** The station that is on: its name, its place, the song it is playing, and the few controls a live stream has. */
@Composable private fun LiveCard(station: RadioStation, playback: PodioPlayback, song: String?, saved: Boolean, onToggle: () -> Unit, onSave: () -> Unit,
    volume: Float, onVolume: ((Float) -> Unit)?, onOpen: () -> Unit, onPrevious: (() -> Unit)?, onNext: (() -> Unit)?, compact: Boolean = false) {
    if (compact) {
        // On a phone the card is one row: the station, what is on air, heart and play. A tap opens the station page.
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth().testTag("podio:live")) {
            Row(Modifier.clickable(onClickLabel = "Open the station page", onClick = onOpen).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StationLogo(station, 44.dp)
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Color(0xFFE5484D)))
                        Text(station.name, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(song ?: station.placeLine().ifBlank { station.language }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onSave) { Icon(if (saved) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder, if (saved) "Remove favourite" else "Save favourite") }
                if (playback.loading) CircularProgressIndicator(Modifier.padding(8.dp).size(28.dp))
                else FilledIconButton(onClick = onToggle, modifier = Modifier.size(44.dp)) { Icon(if (playback.playing) Icons.Outlined.Stop else Icons.Outlined.PlayArrow, if (playback.playing) "Stop" else "Play") }
            }
        }
        return
    }
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .95f), tonalElevation = 4.dp, shadowElevation = 6.dp, modifier = Modifier.fillMaxWidth().testTag("podio:live")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClickLabel = "Open the station page", onClick = onOpen), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                StationLogo(station, 56.dp)
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Color(0xFFE5484D)))
                        Text("LIVE", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(station.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(station.placeLine().ifBlank { station.language }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onSave) { Icon(if (saved) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder, if (saved) "Remove favourite" else "Save favourite") }
            }
            if (song != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.GraphicEq, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text(song, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            } else if (station.tags.isNotBlank()) Text(station.tags.split(',').map(String::trim).filter(String::isNotBlank).take(4).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onPrevious?.invoke() }, enabled = onPrevious != null) { Icon(Icons.Outlined.SkipPrevious, "Previous station") }
                if (playback.loading) CircularProgressIndicator(Modifier.padding(12.dp).size(32.dp))
                else FilledIconButton(onClick = onToggle, modifier = Modifier.size(52.dp)) { Icon(if (playback.playing) Icons.Outlined.Stop else Icons.Outlined.PlayArrow, if (playback.playing) "Stop" else "Play", Modifier.size(28.dp)) }
                IconButton(onClick = { onNext?.invoke() }, enabled = onNext != null) { Icon(Icons.Outlined.SkipNext, "Next station") }
            }
            onVolume?.let { change -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.VolumeUp, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(volume, change, valueRange = 0f..1f, modifier = Modifier.weight(1f).semantics { contentDescription = "Volume" })
            } }
            if (playback.error != null) Text("Playback failed. Try another station.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable private fun PanelTab(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(22.dp), tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun BrowseRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        if (selected) Icon(Icons.Outlined.Check, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable private fun ShowCard(show: PodcastShow, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Podcasts, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            if (show.artwork.isNotBlank()) AsyncImage(show.artwork, null, Modifier.fillMaxSize())
        }
        Text(show.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        Text(show.author, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun PodioPlayerBar(playback: PodioPlayback, onToggle: () -> Unit, onSeek: (Long) -> Unit, onSpeed: (Float) -> Unit) {
    val live = playback.id?.startsWith("podio:radio:") == true
    var speed by remember(playback.id) { mutableFloatStateOf(playback.rate) }
    LaunchedEffect(playback.rate) { speed = playback.rate }
    var scrubbing by remember(playback.id) { mutableFloatStateOf(-1f) }
    Surface(tonalElevation = 6.dp, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(playback.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Text(if (live) "LIVE · ${playback.author}" else playback.author, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                }
                if (!live) IconButton(onClick = { onSeek((playback.positionMs - 15_000).coerceAtLeast(0)) }) { Icon(Icons.Outlined.Replay, "Back 15 seconds") }
                if (playback.loading) CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp))
                else IconButton(onClick = onToggle) { Icon(if (playback.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (playback.playing) "Pause" else "Play") }
                if (!live) TextButton(onClick = { speed = when(speed) { 1f -> 1.25f; 1.25f -> 1.5f; 1.5f -> 2f; else -> 1f }; onSpeed(speed) }) { Text("${speed}×") }
            }
            if (!live && playback.durationMs > 0) {
                Slider(value = if (scrubbing >= 0) scrubbing else (playback.positionMs.toFloat() / playback.durationMs).coerceIn(0f, 1f),
                    onValueChange = { scrubbing = it }, onValueChangeFinished = {
                        if (scrubbing >= 0) onSeek((scrubbing * playback.durationMs).toLong())
                        scrubbing = -1f
                    })
                Text("${clock(playback.positionMs)} / ${clock(playback.durationMs)}", style = MaterialTheme.typography.labelSmall)
            }
            if (playback.error != null) Text("Playback failed. Try another station or refresh this feed.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
        }
    }
}
private fun clock(ms: Long): String = "${ms.coerceAtLeast(0) / 60_000}:${(ms.coerceAtLeast(0) / 1000 % 60).toString().padStart(2, '0')}"

@Composable private fun PodioFilter(title: String, value: String, choices: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilledTonalButton(onClick = { expanded = true }, shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
            Column(horizontalAlignment = Alignment.Start) {
                Text(title, style = MaterialTheme.typography.labelSmall)
                Text(choices.find { it.first == value }?.second ?: value, style = MaterialTheme.typography.labelLarge)
            }
            Icon(Icons.Outlined.ExpandMore, null, Modifier.padding(start = 6.dp).size(16.dp))
        }
        DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            choices.forEach { (id, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { expanded = false; onSelect(id) }) }
        }
    }
}
