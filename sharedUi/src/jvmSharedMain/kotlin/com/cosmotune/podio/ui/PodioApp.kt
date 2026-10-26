package com.cosmotune.podio.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.cosmotune.podio.*
import java.io.File
import kotlinx.coroutines.*

data class PodioPlayback(val id: String? = null, val title: String = "", val author: String = "", val playing: Boolean = false,
    val loading: Boolean = false, val positionMs: Long = 0, val durationMs: Long = 0, val error: String? = null, val rate: Float = 1f)

/** The same account-free radio/podcast page is used by Android and every desktop target. */
@Composable
fun PodioApp(storage: File, playback: PodioPlayback, onPlay: (PodioItem, Long) -> Unit,
    onToggle: () -> Unit, onSeek: (Long) -> Unit, onSpeed: (Float) -> Unit, onMusic: () -> Unit,
    padding: PaddingValues = PaddingValues(), directory: PodioCatalogue? = null) {
    val catalogue = remember(directory) { directory ?: PodioCatalogue() }
    val library = remember(storage) { PodioLibrary(storage) }
    val saved by library.state.collectAsState()
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf("Radio") }
    var query by rememberSaveable { mutableStateOf("") }
    var language by rememberSaveable { mutableStateOf("") }
    var country by rememberSaveable { mutableStateOf("") }
    var savedOnly by rememberSaveable { mutableStateOf(false) }
    var radio by remember { mutableStateOf(emptyList<RadioStation>()) }
    var globe by remember { mutableStateOf(emptyList<RadioStation>()) }
    var shows by remember { mutableStateOf(emptyList<PodcastShow>()) }
    var opened by remember { mutableStateOf<PodcastFeed?>(null) }
    var selected by remember { mutableStateOf<RadioStation?>(null) }
    var directoryLoading by remember { mutableStateOf(false) }
    var feedLoading by remember { mutableStateOf(false) }
    var requestedFeed by remember { mutableStateOf<PodcastShow?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var request by remember { mutableIntStateOf(0) }
    var addFeed by remember { mutableStateOf(false) }
    var feedAddress by remember { mutableStateOf("") }
    var openJob by remember { mutableStateOf<Job?>(null) }
    val loading = feedLoading || requestedFeed == null && directoryLoading
    val latestPlayback by rememberUpdatedState(playback)
    fun save(change: (PodioSaved) -> PodioSaved) { scope.launch {
        try { withContext(Dispatchers.IO) { library.update(change) } }
        catch(e: CancellationException) { throw e }
        catch (_: Exception) { error = "Couldn't save your Podio library. Check available storage and retry." }
    } }
    fun play(item: PodioItem) {
        val old = saved.progress[item.id] ?: 0
        val resume = old.takeIf { !item.live && (item.durationSeconds <= 0 || it < item.durationSeconds * 1000 - 10_000) } ?: 0
        onPlay(item, resume)
    }
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
    LaunchedEffect(Unit) {
        try { globe = catalogue.stations(globe = true) } catch(e: CancellationException) { throw e } catch (_: Exception) { }
    }
    LaunchedEffect(tab, language, country, request, savedOnly) {
        if (savedOnly) return@LaunchedEffect
        directoryLoading = true
        if (requestedFeed == null) error = null
        try {
            if (tab == "Radio") radio = catalogue.stations(query, country, language)
            else shows = catalogue.podcasts(listOf(query.trim(), language).filter(String::isNotBlank).joinToString(" ").ifBlank { "popular podcasts" }, country.ifBlank { "IN" })
        } catch(e: CancellationException) { throw e }
        catch (_: Exception) { if (requestedFeed == null) error = "Couldn't reach the directory. Check your connection and try again." }
        finally { directoryLoading = false }
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
    Surface(Modifier.fillMaxSize().testTag("podio:root"), color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground) {
    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Podio", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text("Live radio. Stories worth staying for.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onMusic) { Text("Music") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (name in listOf("Radio", "Podcasts")) FilterChip(selected = tab == name, onClick = { browse(); tab = name; query = ""; savedOnly = false }, label = { Text(name) })
            FilterChip(selected = savedOnly, onClick = { browse(); savedOnly = !savedOnly }, label = { Text(if (tab == "Radio") "Favourites" else "Following") })
            if (tab == "Podcasts") TextButton(onClick = { addFeed = true }) { Text("Add link") }
        }
        if (opened == null) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(query, { query = it }, singleLine = true, label = { Text(if (tab == "Radio") "Find a station" else "Find a podcast") }, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { browse(); savedOnly = false; request++ }))
                IconButton(onClick = { browse(); savedOnly = false; request++ }) { Icon(Icons.Outlined.Search, "Search") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((label, code) in listOf("World" to "", "India" to "IN", "US" to "US", "UK" to "GB", "Australia" to "AU", "Germany" to "DE"))
                    FilterChip(country == code, { browse(); country = code }, label = { Text(label) })
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (name in listOf("", "Tamil", "Telugu", "Hindi", "English", "Malayalam", "Kannada"))
                    FilterChip(language == name, { browse(); language = name }, label = { Text(name.ifBlank { "All languages" }) })
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("podio:loading"))
        error?.let { message -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { requestedFeed?.let { open(it) } ?: run { request++ } }) { Text("Retry") }
        } }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            if (tab == "Radio") {
                val stations = if (savedOnly) saved.stations else radio
                val displayedSelection = selected?.takeIf { !savedOnly || saved.stations.any { savedStation -> savedStation.id == it.id } }
                val mapStations = if (savedOnly || query.isNotBlank() || country.isNotBlank() || language.isNotBlank()) stations else globe
                @Composable fun stationList(modifier: Modifier) {
                    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                        displayedSelection?.let { station -> item(key = "selected") {
                            RadioRow(station, station.id.let { id -> saved.stations.any { it.id == id } }, playback.id == station.item().id,
                                onPlay = { play(station.item()); scope.launch { catalogue.stationPlayed(station.id) } },
                                onSave = { save { old -> old.copy(stations = if (old.stations.any { it.id == station.id }) old.stations.filterNot { it.id == station.id } else old.stations + station) } })
                        } }
                        if (stations.isEmpty() && !loading) item { Text(if (savedOnly) "Save a station with the heart button." else "No stations found. Try another name, country or language.", Modifier.padding(16.dp)) }
                        items(stations.filterNot { it.id == displayedSelection?.id }, key = { it.id }) { station ->
                            RadioRow(station, saved.stations.any { it.id == station.id }, playback.id == station.item().id,
                                onPlay = { selected = station; play(station.item()); scope.launch { catalogue.stationPlayed(station.id) } },
                                onSave = { save { old -> old.copy(stations = if (old.stations.any { it.id == station.id }) old.stations.filterNot { it.id == station.id } else old.stations + station) } })
                        }
                        item { Text("Stations: Radio Browser · Map: Natural Earth", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(12.dp)) }
                    }
                }
                if (maxWidth > 720.dp) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    PodioGlobe(mapStations, selected?.id, { selected = it }, Modifier.weight(1f).fillMaxHeight())
                    stationList(Modifier.weight(1f).fillMaxHeight())
                } else Column(Modifier.fillMaxSize()) {
                    if (!savedOnly) PodioGlobe(mapStations, selected?.id, { selected = it }, Modifier.fillMaxWidth().height(220.dp))
                    stationList(Modifier.weight(1f).fillMaxWidth())
                }
            } else {
                val feed = opened
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
                    if (feed != null) {
                        item {
                            TextButton(onClick = ::browse) { Text("Back to podcasts") }
                            Text(feed.show.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text(feed.show.author, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(feed.show.source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row {
                                val following = saved.shows.any { it.id == feed.show.id }
                                FilledTonalButton(onClick = { save { old -> old.copy(shows = if (following) old.shows.filterNot { it.id == feed.show.id } else old.shows + feed.show) } }) { Text(if (following) "Following" else "Follow") }
                                TextButton(onClick = { open(feed.show) }) { Text("Refresh") }
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
                        if (list.isEmpty() && !loading) item { Text(if (savedOnly) "Follow a podcast to keep it here." else "Search a show, host or topic—or add its RSS feed.", Modifier.padding(16.dp)) }
                        items(list, key = { it.id }) { show ->
                            ListItem(headlineContent = { Text(show.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                supportingContent = { Text(show.author, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingContent = { AsyncImage(show.artwork, null, Modifier.size(54.dp)) }, modifier = Modifier.clickable { open(show) })
                        }
                        item { Text("Discovery: Apple Podcasts and fyyd · Audio: publisher feeds", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(12.dp)) }
                    }
                }
            }
        }
        if (playback.id?.startsWith("podio:") == true) PodioPlayerBar(playback, onToggle, onSeek, onSpeed)
    }
    }
    if (addFeed) AlertDialog(onDismissRequest = { addFeed = false }, title = { Text("Open a podcast") },
        text = { OutlinedTextField(feedAddress, { feedAddress = it }, label = { Text("RSS feed or Spotify podcast link") }, singleLine = true) },
        confirmButton = { TextButton(onClick = {
            val url = PodioCatalogue.httpUrl(feedAddress)
            if (url == null) { error = "Enter an HTTP or HTTPS feed URL."; addFeed = false }
            else { addFeed = false; tab = "Podcasts"; open(PodcastShow(PodioCatalogue.identity(url), "", "", url)) }
        }) { Text("Open podcast") } }, dismissButton = { TextButton(onClick = { addFeed = false }) { Text("Cancel") } })
}

@Composable private fun RadioRow(station: RadioStation, saved: Boolean, current: Boolean, onPlay: () -> Unit, onSave: () -> Unit) {
    ListItem(headlineContent = { Text(station.name, fontWeight = if (current) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(listOf(station.country, station.language, listOf(station.codec, station.bitrate.takeIf { it > 0 }?.let { "$it kbps" }.orEmpty()).filter(String::isNotBlank).joinToString(" · ")).filter(String::isNotBlank).joinToString(" · "), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        leadingContent = { if (station.artwork.isNotBlank()) AsyncImage(station.artwork, null, Modifier.size(42.dp)) else Icon(Icons.Outlined.Radio, null) },
        trailingContent = { IconButton(onClick = onSave) { Icon(if (saved) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder, if (saved) "Remove favourite" else "Save favourite") } },
        modifier = Modifier.clickable(onClick = onPlay))
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
