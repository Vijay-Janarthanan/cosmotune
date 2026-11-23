package com.cosmotune.music.wear.ui

import android.content.ComponentName
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.cosmotune.music.wear.data.WatchPodioDownloads
import com.cosmotune.music.wear.playback.StandalonePodioService
import com.cosmotune.podio.PodioCatalogue
import com.cosmotune.podio.PodcastShow
import java.net.URI
import java.util.Locale
import kotlinx.coroutines.CancellationException

private enum class PodioDirectory { RADIO, PODCASTS, SAVED }

private data class WatchAudio(val id: String, val title: String, val detail: String, val url: String, val hls: Boolean = false, val episode: Boolean = false)

private data class WatchPlayerState(
    val title: String,
    val playing: Boolean,
    val seekable: Boolean,
    val failed: Boolean,
    val buffering: Boolean,
)

@Composable
internal fun StandalonePodioScreen() {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val catalogue = remember { PodioCatalogue() }
    val scroll = rememberScalingLazyListState(initialCenterItemIndex = 1)
    var directory by rememberSaveable { mutableStateOf(PodioDirectory.RADIO) }
    var draft by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var feed by rememberSaveable { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf(emptyList<WatchAudio>()) }
    var shows by remember { mutableStateOf(emptyList<PodcastShow>()) }
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var playerState by remember { mutableStateOf<WatchPlayerState?>(null) }
    var connectionFailed by remember { mutableStateOf(false) }
    var connectAttempt by remember { mutableIntStateOf(0) }
    // The episode the player was last given, so it can be saved to the watch.
    var lastAudio by remember { mutableStateOf<WatchAudio?>(null) }
    val savedEpisodes by WatchPodioDownloads.saved.collectAsState()
    val saving by WatchPodioDownloads.running.collectAsState()
    val saveFailed by WatchPodioDownloads.failed.collectAsState()
    LaunchedEffect(Unit) { WatchPodioDownloads.load(context) }

    DisposableEffect(context, connectAttempt) {
        val token = SessionToken(context, ComponentName(context, StandalonePodioService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        fun updatePlayer(player: Player) {
            playerState = player.currentMediaItem?.let {
                WatchPlayerState(
                    it.mediaMetadata.title.toString(), player.playWhenReady, player.isCurrentMediaItemSeekable,
                    player.playerError != null, player.playbackState == Player.STATE_BUFFERING,
                )
            }
        }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = updatePlayer(player)
        }
        future.addListener({
            if (!future.isCancelled) {
                controller = runCatching { future.get() }.getOrNull()?.also {
                    it.addListener(listener)
                    updatePlayer(it)
                }
                connectionFailed = controller == null
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            controller?.removeListener(listener)
            MediaController.releaseFuture(future)
        }
    }

    LaunchedEffect(directory, query, feed, attempt) {
        loading = true
        error = false
        results = emptyList()
        shows = emptyList()
        try {
            val address = feed
            if (address != null) {
                results = catalogue.publisherFeed(address).episodes.mapNotNull { episode ->
                    httpsUrl(episode.url)?.let { WatchAudio(episode.id, episode.title, episode.show, it, episode = true) }
                }
            } else if (directory == PodioDirectory.SAVED) {
                results = emptyList()
            } else if (directory == PodioDirectory.RADIO) {
                results = catalogue.stations(query = query, limit = 180).asSequence().mapNotNull { station ->
                    httpsUrl(station.url)?.let {
                        WatchAudio(station.id, station.name, station.country, it, station.codec.equals("HLS", true))
                    }
                }.take(50).toList()
            } else {
                val country = Locale.getDefault().country.ifBlank { "US" }
                val found = if (query.isBlank()) catalogue.publisherPodcastCharts(country, limit = 20)
                else catalogue.searchPublisherPodcasts(query, country)
                shows = found.filter { httpsUrl(it.feed) != null }.take(40)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = true
        } finally {
            loading = false
        }
    }

    fun search() {
        keyboard?.hide()
        if (directory == PodioDirectory.PODCASTS) {
            httpsUrl(draft)?.let {
                feed = it
                return
            }
        }
        query = draft.trim()
        attempt++
    }
    fun selectDirectory(selection: PodioDirectory) {
        directory = selection
        draft = ""
        query = ""
        feed = null
    }

    BackHandler(feed != null) { feed = null }
    ScreenScaffold(scrollState = scroll) { padding ->
        WearList(state = scroll, padding = padding, spacing = 5.dp) {
            item("title") {
                Text("Podio on watch", style = WearType.header, color = WearOrbit.Ink,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            playerState?.let { current ->
                item("playing") {
                    val action = when {
                        current.failed -> "Retry"
                        current.playing -> "Pause"
                        else -> "Play"
                    }
                    PodioCell(action, if (current.buffering) "Buffering · ${current.title}" else current.title, play = true) {
                        controller?.let {
                            when {
                                current.failed -> { it.prepare(); it.play() }
                                current.playing -> it.pause()
                                else -> it.play()
                            }
                        }
                    }
                }
                if (current.seekable) {
                    item("rewind") { PodioCell("Back 10 seconds") { controller?.seekBack() } }
                    item("forward") { PodioCell("Forward 15 seconds") { controller?.seekForward() } }
                }
                // An episode playing from the internet can be kept on the watch for later, with no phone and no connection.
                lastAudio?.takeIf { it.episode && it.title == current.title }?.let { audio ->
                    val stored = savedEpisodes.firstOrNull { it.id == audio.id }
                    val progress = saving[audio.id]
                    item("save") {
                        when {
                            stored != null -> PodioCell("Saved on this watch", "Remove it to free space") { WatchPodioDownloads.remove(context, audio.id) }
                            progress != null -> PodioCell("Saving to watch…", if (progress >= 0) "${(progress * 100).toInt()}% · tap to stop" else "Tap to stop") {
                                WatchPodioDownloads.cancel(audio.id)
                            }
                            else -> PodioCell("Save to watch", "Listen later without a connection") {
                                WatchPodioDownloads.save(context, audio.id, audio.title, audio.detail, audio.url)
                            }
                        }
                    }
                }
                item("stop") { PodioCell("Stop playback") { controller?.stop(); controller?.clearMediaItems() } }
            }
            if (connectionFailed) item("reconnect") {
                PodioCell("Reconnect player") {
                    connectionFailed = false
                    connectAttempt++
                }
            }
            if (feed != null) {
                item("back") { PodioCell("Back to podcasts") { feed = null } }
            } else {
                item("directory") {
                    val (title, next) = when (directory) {
                        PodioDirectory.RADIO -> "Radio" to PodioDirectory.PODCASTS
                        PodioDirectory.PODCASTS -> "Podcasts" to PodioDirectory.SAVED
                        PodioDirectory.SAVED -> "On this watch" to PodioDirectory.RADIO
                    }
                    val nextName = when (next) { PodioDirectory.RADIO -> "radio"; PodioDirectory.PODCASTS -> "podcasts"; PodioDirectory.SAVED -> "episodes on this watch" }
                    PodioCell(title, "Switch to $nextName") { selectDirectory(next) }
                }
                if (directory != PodioDirectory.SAVED) item("query") {
                    BasicTextField(
                        value = draft, onValueChange = { draft = it.take(200) }, singleLine = true,
                        textStyle = WearType.row.copy(color = WearOrbit.Ink, textAlign = TextAlign.Center),
                        cursorBrush = SolidColor(WearOrbit.Peach),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { search() }),
                        modifier = Modifier.fillMaxWidth().padding(12.dp).semantics { contentDescription = "Search Podio" },
                        decorationBox = { input ->
                            Box {
                                if (draft.isEmpty()) Text(if (directory == PodioDirectory.RADIO) "Station name" else "Show title or RSS URL",
                                    color = WearOrbit.Dim, style = WearType.rowSub,
                                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                                input()
                            }
                        },
                    )
                }
                if (directory != PodioDirectory.SAVED) item("search") { PodioCell("Search", enabled = !loading) { search() } }
            }
            if (loading) item("loading") { PodioHint("Loading…") }
            if (error) item("error") { PodioCell("Retry", "Check this watch's internet connection") { attempt++ } }
            saveFailed?.let { message -> item("save-failed") { PodioHint(message) } }
            if (directory == PodioDirectory.SAVED && feed == null) {
                if (savedEpisodes.isEmpty()) item("saved-empty") { PodioHint("Episodes you save while they play are kept here.") }
                items(savedEpisodes, key = { "saved:" + it.id }) { episode ->
                    PodioCell(episode.title, episode.show, mark = episode.title, enabled = controller != null) {
                        controller?.let {
                            it.setMediaItem(MediaItem.Builder().setMediaId(episode.id).setUri(android.net.Uri.fromFile(episode.file))
                                .setMediaMetadata(MediaMetadata.Builder().setTitle(episode.title).setArtist(episode.show).build()).build())
                            it.prepare()
                            it.play()
                        }
                    }
                }
            }
            if (directory != PodioDirectory.SAVED && !loading && !error && results.isEmpty() && shows.isEmpty()) {
                item("empty") { PodioHint("No results with secure streams.") }
            }
            items(shows, key = { it.id }) { show ->
                PodioCell(show.title, show.author, mark = show.title) { feed = show.feed }
            }
            items(results, key = { it.id }) { audio ->
                PodioCell(audio.title, audio.detail, mark = audio.title, enabled = controller != null) {
                    controller?.let {
                        lastAudio = audio
                        val media = MediaItem.Builder().setMediaId(audio.id).setUri(audio.url)
                            .setMediaMetadata(MediaMetadata.Builder().setTitle(audio.title).setArtist(audio.detail).build())
                        if (audio.hls) media.setMimeType(MimeTypes.APPLICATION_M3U8)
                        it.setMediaItem(media.build())
                        it.prepare()
                        it.play()
                    }
                }
            }
        }
    }
}

private fun httpsUrl(url: String): String? = PodioCatalogue.httpUrl(url)?.takeIf { URI(it).scheme.equals("https", true) }
