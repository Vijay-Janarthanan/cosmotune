package com.cosmotune.listening

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.os.LocaleListCompat
import androidx.media3.session.MediaController
import com.cosmotune.ui.listening.*
import com.music.bitchord.data.LocalMediaRepository
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.AudioQuality
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.data.spotify.LocalPlaylistStore
import com.music.bitchord.data.stats.ListeningStats
import com.music.bitchord.data.stats.ReplayPeriod
import com.music.bitchord.download.Downloads
import com.music.bitchord.playback.QueueShuffle
import com.music.bitchord.playback.playSongs
import com.music.bitchord.playback.toMediaItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

@Composable
fun PhoneListeningTools(
    open: Boolean, onDismiss: () -> Unit, library: ListeningLibrary, queue: () -> ListeningQueue,
    controller: MediaController?, findMusic: () -> Unit, importMusic: () -> Unit,
    openRelease: (String, String) -> Unit, startLocal: () -> Unit,
) {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<CompletableDeferred<Uri?>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        pending?.complete(result.data?.data.takeIf { result.resultCode == Activity.RESULT_OK })
        pending = null
    }
    DisposableEffect(Unit) { onDispose { pending?.cancel() } }
    val downloaded by Downloads.saved.collectAsState()
    val deviceSongs by produceState<List<Song>>(emptyList(), open, downloaded) {
        if (open) value = withContext(Dispatchers.IO) {
            val downloads = Downloads.getDownloadedSongs(context)
            val local = runCatching { LocalMediaRepository.getLocalMusic(context) }.getOrDefault(emptyList())
            downloads + local
        }
    }
    val completeLibrary = remember(library, deviceSongs, downloaded) {
        library.copy(songs = (deviceSongs + library.songs).distinctBy { it.videoId }, downloadedIds = downloaded.keys)
    }
    val host = ListeningToolsHost(
        file = File(context.filesDir, "cosmotune-listening.json"), library = completeLibrary, queue = queue,
        replay = { window ->
            val summary = ListeningStats.summary(if (window == ReplayWindow.MONTH) ReplayPeriod.THIS_MONTH else ReplayPeriod.THIS_YEAR)
            ReplayTracks("Replay · ${summary.label}", summary.songs.map { it.song })
        },
        createPlaylist = { title, songs ->
            require(songs.isNotEmpty()) { "There are no tracks to save." }
            val same = LocalPlaylistStore.playlists.value.any { it.title == title && it.songs.map(Song::videoId) == songs.map(Song::videoId) }
            if (!same) LocalPlaylistStore.savePlaylist(title, songs)
        },
        restore = { session ->
            check(!com.music.bitchord.data.listentogether.ListenTogether.state.value.inParty) { "Leave Listen Together before opening a saved session." }
            val player = checkNotNull(controller) { "The player is starting. Try again in a moment." }
            val items = withContext(Dispatchers.Default) { session.tracks.map { it.song().toMediaItem() } }
            player.pause()
            QueueShuffle.restoreSavedOrder(session.tracks.map { it.song() }, session.shuffle)
            player.repeatMode = session.repeat
            AppSettings.setRepeatMode(session.repeat)
            player.setMediaItems(items, session.index, session.positionMs)
            player.prepare()
            player.pause()
        },
        play = { songs ->
            check(!com.music.bitchord.data.listentogether.ListenTogether.state.value.inParty) { "Leave Listen Together before opening a smart playlist." }
            checkNotNull(controller) { "The player is starting. Try again in a moment." }.playSongs(songs, 0)
        },
        openRelease = openRelease, findMusic = findMusic, importMusic = importMusic,
        applySetup = { preferences, lowData ->
            if (lowData) {
                AppSettings.setAudioQualityWifi(AudioQuality.LOW)
                AppSettings.setAudioQualityCellular(AudioQuality.LOW)
                AppSettings.setAnimatedCanvas(false)
            }
            if (preferences.preferLocal) startLocal()
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(preferences.contentLanguage))
        },
        exportFile = { name, text ->
            check(pending == null) { "Finish the open file chooser first." }
            val answer = CompletableDeferred<Uri?>().also { pending = it }
            picker.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = if (name.endsWith(".txt")) "text/plain" else "application/json"
                putExtra(Intent.EXTRA_TITLE, name)
            })
            val uri = answer.await()
            if (uri == null) false else withContext(Dispatchers.IO) {
                checkNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "The selected file could not be opened." }
                    .bufferedWriter(Charsets.UTF_8).use { it.write(text) }
                true
            }
        },
        importFile = {
            check(pending == null) { "Finish the open file chooser first." }
            val answer = CompletableDeferred<Uri?>().also { pending = it }
            picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" })
            answer.await()?.let { uri -> withContext(Dispatchers.IO) {
                checkNotNull(context.contentResolver.openInputStream(uri)) { "The selected file could not be opened." }.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        require(output.size() + read <= 16 * 1024 * 1024) { "Choose a listening backup smaller than 16 MB." }
                        output.write(buffer, 0, read)
                    }
                    output.toString("UTF-8")
                }
            } }
        },
        mediaCheck = { song -> ListeningNetwork.timed("Media stream") {
            withTimeout(30_000) {
                val owned = SourceRegistry.parseTrackKey(song.videoId)
                val stream = if (owned != null) SourceResolver.resolve(owned.first, owned.second, TrackMatcher.targetOf(song))
                    else SourceResolver.substituteForYouTube(TrackMatcher.targetOf(song))
                if (stream != null) ListeningNetwork.readMedia(stream.url, stream.headers)
                else {
                    require(owned == null) { "Source unavailable" }
                    val url = com.music.bitchord.data.innertube.StreamResolver.resolve(song.videoId)
                    ListeningNetwork.readMedia(url, com.music.bitchord.data.innertube.StreamResolver.mediaHeadersFor(url))
                }
            }
        } },
        available = { track ->
            when {
                track.path?.let { File(it).isFile } == true -> true
                track.uri != null -> runCatching { context.contentResolver.openAssetFileDescriptor(Uri.parse(track.uri), "r")?.use { true } ?: false }.getOrDefault(false)
                track.path != null -> false
                else -> true
            }
        },
        sourceSummary = "Checks use your enabled sources and current streaming quality.",
        languageChoices = listOf("" to context.getString(com.music.bitchord.R.string.system)) +
            com.music.bitchord.ui.components.SUPPORTED_LANGUAGES.map { it.tag to context.getString(it.nameRes) },
        existingUser = library.songs.isNotEmpty(),
    )
    ListeningTools(open, host, onDismiss)
}
