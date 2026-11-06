package com.cosmotune.music.ui.tv.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.playback.PlayerState
import com.cosmotune.music.playback.playSongs
import com.cosmotune.music.ui.tv.components.ProvideTvComfortScrolling
import com.cosmotune.music.ui.tv.components.TvActivityIndicator
import com.cosmotune.music.ui.tv.components.TvButton
import com.cosmotune.music.ui.tv.components.TvChromeScrollEffect
import com.cosmotune.music.ui.tv.components.TvColourCard
import com.cosmotune.music.ui.tv.components.TvLockup
import com.cosmotune.music.ui.tv.components.TvSegmented
import com.cosmotune.music.ui.tv.components.TvShelfTitle
import com.cosmotune.music.ui.tv.components.TvSongRow
import com.cosmotune.music.ui.tv.components.TvTextField
import com.cosmotune.music.ui.tv.theme.TvDimensions
import com.cosmotune.music.ui.tv.theme.TvGlass
import com.cosmotune.music.ui.tv.theme.TvType
import com.cosmotune.podio.PodcastFeed
import com.cosmotune.podio.PodcastShow
import com.cosmotune.podio.PodioCatalogue
import com.cosmotune.podio.PodioItem
import com.cosmotune.podio.PodioLibrary
import com.cosmotune.podio.RadioStation
import com.cosmotune.podio.YouTubePodcasts
import com.cosmotune.podio.item
import com.cosmotune.podio.ui.PodioLocal
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val PodioTabs = listOf("Radio", "Podcasts", "Saved")

/** How many station discs sit in one row of the grid. */
private const val StationsPerRow = 6

/**
 * Podio on the TV (board batch-be section C, made for the sofa): public radio and podcasts, with playback in the TV's own player.
 * The page is "Radio." with Radio, Podcasts and Saved as chips; what Podio is playing is the colour card at the top ("● LIVE" for a
 * station) with play in the bite. Stations are discs with their logo inside, the one on air wearing the orbit; select plays one,
 * holding select saves or unsaves it. Podcasts and their episodes are Orbit rows; a show's page is the album page — the show's
 * colour card, then its episodes with length, date and how much is heard.
 */
@Composable
fun TvPodioScreen(controller: MediaController?, playerState: PlayerState, onNowPlaying: () -> Unit) {
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
        // Stations near the listener first (their country in their language, then their country, then their language).
        if (tab == "Radio") stations = PodioLocal.rank(catalogue.stations(query), PodioLocal.current())
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

    val playing = playerState.song?.takeIf { it.videoId.startsWith("podio:") }
    val togglePlay: () -> Unit = { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
    val listState = rememberLazyListState()
    TvChromeScrollEffect(listState)
    val side = TvDimensions.SafeMarginHorizontal

    ProvideTvComfortScrolling(top = TvDimensions.ContentTop, bottom = 48.dp) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(top = TvDimensions.ContentTop, bottom = 64.dp),
        ) {
            val opened = feed
            if (opened != null) {
                item(key = "show") {
                    val first = opened.episodes.firstOrNull()?.item()
                    val following = saved.shows.any { it.feed == opened.show.feed }
                    TvColourCard(
                        cover = opened.show.artwork.takeIf { it.isNotBlank() },
                        key = opened.show.feed,
                        label = "Podcast · " + (if (opened.episodes.size == 1) "1 episode" else "${opened.episodes.size} episodes"),
                        title = opened.show.title,
                        subtitle = opened.show.author,
                        playing = first != null && playing?.videoId == first.id && playerState.isPlaying,
                        onPlay = { first?.let(::play) },
                        playEnabled = first != null && controller != null,
                        ring = false,
                        height = 232.dp,
                        modifier = Modifier.padding(horizontal = side).padding(bottom = 12.dp),
                        below = {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 14.dp)) {
                                TvButton("Back", ::back)
                                TvButton(if (following) "Unfollow" else "Follow", { follow(opened.show) })
                            }
                        },
                    )
                }
                if (loading) item(key = "loading") { PodioLoading() }
                error?.let { message -> item(key = "error") { PodioNote(message) } }
                items(opened.episodes, key = { it.id }) { episode ->
                    val item = episode.item()
                    val heard = saved.progress[item.id] ?: 0L
                    val length = episode.durationSeconds.takeIf { it > 0 }?.let { "${(it + 30) / 60} min" }
                    val state = when {
                        item.id in saved.completedEpisodes -> "heard"
                        heard > 0 && episode.durationSeconds > 0 -> "${(episode.durationSeconds * 1000 - heard).coerceAtLeast(0) / 60_000 + 1} min left"
                        heard > 0 -> "resume"
                        else -> "new"
                    }
                    TvSongRow(
                        song = Song(
                            videoId = item.id,
                            title = episode.title,
                            artist = listOfNotNull(episode.published.takeIf { it.isNotBlank() }, length, state).joinToString(" · "),
                            thumbnailUrl = item.artwork.takeIf { it.isNotBlank() },
                        ),
                        onClick = { if (controller != null) play(item) },
                        isCurrent = playing?.videoId == item.id,
                        isPlaying = playerState.isPlaying,
                        modifier = Modifier.padding(horizontal = side),
                    )
                }
                if (opened.nextPage != null) item(key = "older") {
                    Box(Modifier.padding(horizontal = side, vertical = 8.dp)) {
                        TvButton("Load older episodes", { run { feed = catalogue.moreEpisodes(opened) } }, enabled = !loading)
                    }
                }
            } else {
                item(key = "head") {
                    Column(Modifier.padding(horizontal = side).padding(bottom = 8.dp)) {
                        Text(
                            text = when (tab) {
                                "Radio" -> if (stations.isEmpty()) "PUBLIC RADIO" else "${stations.size} STATIONS"
                                "Podcasts" -> "PODCASTS"
                                else -> "${saved.stations.size} STATIONS · ${saved.shows.size} SHOWS"
                            },
                            style = TvType.Label,
                            color = TvGlass.TextSecondary,
                        )
                        Text(
                            buildAnnotatedString {
                                append(tab)
                                withStyle(SpanStyle(color = TvGlass.Peach)) { append(".") }
                            },
                            style = TvType.LargeTitle,
                            color = TvGlass.TextPrimary,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
                item(key = "tabs") {
                    TvSegmented(
                        options = PodioTabs,
                        selectedIndex = PodioTabs.indexOf(tab),
                        onSelect = { i -> work?.cancel(); feed = null; query = ""; tab = PodioTabs[i] },
                        modifier = Modifier.padding(horizontal = side).padding(bottom = 10.dp),
                    )
                }
                if (playing != null) item(key = "playing") {
                    val live = playing.videoId.startsWith("podio:radio:")
                    val duration = playerState.durationMs
                    val position = playerState.position
                    TvColourCard(
                        cover = playing.thumbnailUrl,
                        key = playing.videoId,
                        label = if (live) listOf("● Live", playing.artist).filter(String::isNotBlank).joinToString(" · ") else "Podcast · " + playing.artist,
                        title = playing.title,
                        subtitle = if (live) "Live radio" else null,
                        playing = playerState.isPlaying,
                        onPlay = togglePlay,
                        onClick = onNowPlaying,
                        spinning = playerState.isPlaying,
                        progress = if (live) null else ({ if (duration > 0) position.positionMs.toFloat() / duration else null }),
                        discContent = if (playing.thumbnailUrl == null) ({ StationMark(playing.title) }) else null,
                        modifier = Modifier.padding(horizontal = side).padding(top = 4.dp, bottom = 14.dp),
                    )
                }
                if (tab != "Saved") item(key = "search") {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(horizontal = side).padding(bottom = 6.dp)) {
                        TvTextField(query, { query = it }, if (tab == "Radio") "Station name" else "Podcast name or public feed/show link", Modifier.weight(1f), onImeAction = ::search)
                        TvButton("Search", ::search, enabled = !loading)
                    }
                }
                if (loading) item(key = "loading") { PodioLoading() }
                error?.let { message -> item(key = "error") { PodioNote(message) } }

                val radio = if (tab == "Saved") saved.stations else if (tab == "Radio") stations else emptyList()
                if (radio.isNotEmpty()) {
                    item(key = "stationsTitle") {
                        TvShelfTitle(title = "Stations", subtitle = "Select to play · hold select to save or unsave", modifier = Modifier.padding(top = 10.dp))
                    }
                    items(radio.chunked(StationsPerRow), key = { row -> "stations:" + row.first().id }) { row ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(TvDimensions.CardSpacing),
                            modifier = Modifier.padding(horizontal = side).padding(top = 10.dp, bottom = 6.dp),
                        ) {
                            row.forEach { station ->
                                val item = station.item()
                                val isSaved = saved.stations.any { it.id == station.id }
                                TvLockup(
                                    title = station.name,
                                    subtitle = listOfNotNull("Saved".takeIf { isSaved }, station.country.takeIf { it.isNotBlank() }).joinToString(" · ").ifBlank { "Live" },
                                    artworkUrl = station.artwork.takeIf { it.isNotBlank() },
                                    width = 112.dp,
                                    now = playing?.videoId == item.id,
                                    onClick = { if (controller != null) play(item) },
                                    onLongClick = { favorite(station) },
                                    overlay = if (station.artwork.isBlank()) ({ StationMark(station.name) }) else null,
                                )
                            }
                        }
                    }
                }
                val podcasts = if (tab == "Saved") saved.shows else if (tab == "Podcasts") shows else emptyList()
                if (podcasts.isNotEmpty()) {
                    item(key = "showsTitle") { TvShelfTitle(title = if (tab == "Saved") "Following" else "Shows", modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)) }
                    items(podcasts, key = { "show:${it.id}" }) { show ->
                        TvSongRow(
                            song = Song(videoId = "show:" + show.id, title = show.title, artist = show.author, thumbnailUrl = show.artwork.takeIf { it.isNotBlank() }, durationText = "EPISODES"),
                            onClick = { run { feed = catalogue.feed(show.feed) } },
                            modifier = Modifier.padding(horizontal = side),
                        )
                    }
                }
                if (!loading && radio.isEmpty() && podcasts.isEmpty()) item(key = "empty") {
                    PodioNote(if (tab == "Saved") "Save a station or follow a podcast to find it here." else "No results. Try another search.")
                }
            }
        }
    }
}

/** A station with no logo: the first letters of its name inside the disc, as the board draws "FM 98". */
@Composable
private fun StationMark(name: String) {
    Box(Modifier.fillMaxSize().padding(10.dp), contentAlignment = Alignment.Center) {
        Text(
            text = name.split(' ').filter { it.isNotBlank() }.take(2).joinToString(" ") { it.take(5) }.uppercase(),
            style = TvType.Label,
            color = TvGlass.TextPrimary,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PodioLoading() {
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.Center) { TvActivityIndicator() }
}

@Composable
private fun PodioNote(text: String) {
    Text(
        text,
        style = TvType.Callout,
        color = TvGlass.TextSecondary,
        modifier = Modifier.padding(horizontal = TvDimensions.SafeMarginHorizontal, vertical = 12.dp),
    )
}
