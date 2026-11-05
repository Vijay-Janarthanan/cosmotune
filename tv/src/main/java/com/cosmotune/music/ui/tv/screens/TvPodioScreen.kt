package com.cosmotune.music.ui.tv.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import com.cosmotune.music.playback.playSongs
import com.cosmotune.music.ui.tv.components.*
import com.cosmotune.music.ui.tv.theme.TvGlass
import com.cosmotune.podio.*
import java.io.File
import kotlinx.coroutines.*

/** Remote-friendly public radio and podcast lists; playback stays in the TV foreground service. */
@Composable fun TvPodioScreen(controller: MediaController?, onNowPlaying: () -> Unit) {
    val context = LocalContext.current
    val catalogue = remember { PodioCatalogue(youtubePodcasts = YouTubePodcasts()) }
    val library by produceState<PodioLibrary?>(null) { value = withContext(Dispatchers.IO) { PodioLibrary(File(context.filesDir, "podio/library.json")) } }
    val store = library ?: return
    val saved by store.state.collectAsState()
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf("Radio") }
    var query by remember { mutableStateOf("") }
    var stations by remember { mutableStateOf<List<RadioStation>>(emptyList()) }
    var shows by remember { mutableStateOf<List<PodcastShow>>(emptyList()) }
    var feed by remember { mutableStateOf<PodcastFeed?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var work by remember { mutableStateOf<Job?>(null) }
    var serial by remember { mutableIntStateOf(0) }
    fun run(block: suspend () -> Unit) {
        work?.cancel(); val token = ++serial; work = scope.launch {
            loading = true; error = null
            try { block() } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { if (token == serial) error = "Podio could not load this selection. Please retry." }
            finally { if (token == serial) loading = false }
        }
    }
    fun search() = run {
        feed = null
        if (tab == "Radio") stations = catalogue.stations(query)
        else if (tab == "Podcasts" && PodioCatalogue.httpUrl(query.trim()) != null) feed = catalogue.feed(query.trim())
        else if (tab == "Podcasts") shows = catalogue.podcasts(query)
    }
    fun play(item: PodioItem) {
        val player = controller ?: return
        run {
            val position = withContext(Dispatchers.IO) { store.resumePosition(item.id, item.durationSeconds * 1000) }
            player.playSongs(listOf(item.song()), 0)
            if (position > 0) player.seekTo(position)
            if (item.live) scope.launch { catalogue.stationPlayed(item.id.removePrefix("podio:radio:")) }
            onNowPlaying()
        }
    }
    fun favorite(station: RadioStation) = run { withContext(Dispatchers.IO) { store.update { old -> old.copy(stations = if (old.stations.any { it.id == station.id }) old.stations.filterNot { it.id == station.id } else old.stations + station) } } }
    fun follow(show: PodcastShow) = run { withContext(Dispatchers.IO) { store.update { old -> old.copy(shows = if (old.shows.any { it.feed == show.feed }) old.shows.filterNot { it.feed == show.feed } else old.shows + show) } } }
    LaunchedEffect(tab) { if (tab != "Saved") search() }
    fun back() { work?.cancel(); ++serial; loading = false; feed = null }
    BackHandler(feed != null, ::back)
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 44.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(top = 20.dp, bottom = 80.dp)) {
        item { Text("Podio · Radio & Podcasts", color = TvGlass.TextPrimary) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("Radio", "Podcasts", "Saved").forEach { label -> TvButton(label, { work?.cancel(); feed = null; query = ""; tab = label }, isPrimary = label == tab) }
        } }
        if (tab != "Saved" && feed == null) item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvTextField(query, { query = it }, if (tab == "Radio") "Station name" else "Podcast name or public feed/show link", Modifier.weight(1f), onImeAction = ::search)
            TvButton("Search", ::search, enabled = !loading)
        } }
        if (loading) item { Text("Loading…", color = TvGlass.TextSecondary) }
        error?.let { message -> item { Text(message, color = TvGlass.TextPrimary) } }
        val opened = feed
        if (opened != null) {
            item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton("Back", ::back)
                TvButton(if (saved.shows.any { it.feed == opened.show.feed }) "Unfollow" else "Follow", { follow(opened.show) })
            } }
            item { Text(opened.show.title, color = TvGlass.TextPrimary) }
            items(opened.episodes, key = { it.id }) { episode ->
                val item = episode.item()
                TvListRow(episode.title, { play(item) }, subtitle = episode.published, value = if (item.id in saved.completedEpisodes) "Played" else if ((saved.progress[item.id] ?: 0) > 0) "Resume" else "Play", enabled = controller != null)
            }
            if (opened.nextPage != null) item { TvButton("Load older episodes", { run { feed = catalogue.moreEpisodes(opened) } }, enabled = !loading) }
        } else {
            val radio = if (tab == "Saved") saved.stations else if (tab == "Radio") stations else emptyList()
            items(radio, key = { "radio:${it.id}" }) { station -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvListRow(station.name, { play(station.item()) }, Modifier.weight(1f), subtitle = listOf(station.country, station.language).filter(String::isNotBlank).joinToString(" · "), value = "Live", enabled = controller != null)
                TvButton(if (saved.stations.any { it.id == station.id }) "Unsave" else "Save", { favorite(station) })
            } }
            val podcasts = if (tab == "Saved") saved.shows else if (tab == "Podcasts") shows else emptyList()
            items(podcasts, key = { "show:${it.id}" }) { show -> TvListRow(show.title, { run { feed = catalogue.feed(show.feed) } }, subtitle = show.author, value = "Episodes") }
            if (!loading && radio.isEmpty() && podcasts.isEmpty()) item { Text(if (tab == "Saved") "Save a station or follow a podcast to find it here." else "No results. Try another search.", color = TvGlass.TextSecondary) }
        }
    }
}
