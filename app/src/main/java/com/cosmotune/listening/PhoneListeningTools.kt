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
import com.cosmotune.music.data.LocalMediaRepository
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.settings.AppSettings
import com.cosmotune.music.data.settings.AudioQuality
import com.cosmotune.music.data.sources.SourceRegistry
import com.cosmotune.music.data.sources.SourceResolver
import com.cosmotune.music.data.sources.TrackMatcher
import com.cosmotune.music.data.spotify.LocalPlaylistStore
import com.cosmotune.music.data.stats.ListeningStats
import com.cosmotune.music.data.stats.ReplayPeriod
import com.cosmotune.music.download.Downloads
import com.cosmotune.music.playback.QueueShuffle
import com.cosmotune.music.playback.playSongs
import com.cosmotune.music.playback.toMediaItem
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
    /** False until the library, history and restored queue behind [library] have had a chance to load. */
    libraryKnown: Boolean = true,
    openOn: com.cosmotune.ui.listening.ListeningTab? = null,
) {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<CompletableDeferred<Uri?>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        pending?.complete(result.data?.data.takeIf { result.resultCode == Activity.RESULT_OK })
        pending = null
    }
    var trashPending by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    val trashConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        trashPending?.complete(result.resultCode == Activity.RESULT_OK)
        trashPending = null
    }
    DisposableEffect(Unit) { onDispose { pending?.cancel(); trashPending?.cancel() } }
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
    val latestController by rememberUpdatedState(controller)
    val latestLibrary by rememberUpdatedState(completeLibrary)
    val remotePlayback = remember { kotlinx.coroutines.flow.MutableStateFlow(com.cosmotune.remote.RemotePlayback()) }
    LaunchedEffect(controller) {
        while (true) {
            val player = controller
            remotePlayback.value = if (player == null) com.cosmotune.remote.RemotePlayback() else
                com.cosmotune.remote.RemotePlayback(player.mediaMetadata.title?.toString().orEmpty(), player.mediaMetadata.artist?.toString().orEmpty(),
                    player.isPlaying, player.currentPosition.coerceAtLeast(0), player.duration.takeIf { it > 0 }, player.volume,
                    player.shuffleModeEnabled, when (player.repeatMode) { 1 -> com.cosmotune.remote.RemoteRepeat.ONE; 2 -> com.cosmotune.remote.RemoteRepeat.ALL; else -> com.cosmotune.remote.RemoteRepeat.OFF })
            kotlinx.coroutines.delay(500)
        }
    }
    val remote = remember {
        com.cosmotune.remote.LanRemoteController(object : com.cosmotune.remote.LanRemoteHost {
            override fun playback() = remotePlayback.value
            override suspend fun command(command: com.cosmotune.remote.RemoteCommand) = withContext(Dispatchers.Main.immediate) {
                val player = checkNotNull(latestController) { "Player is not ready" }
                when (command.action) {
                    com.cosmotune.remote.RemoteAction.PLAY -> player.play()
                    com.cosmotune.remote.RemoteAction.PAUSE -> player.pause()
                    com.cosmotune.remote.RemoteAction.TOGGLE -> if (player.isPlaying) player.pause() else player.play()
                    com.cosmotune.remote.RemoteAction.NEXT -> player.seekToNextMediaItem()
                    com.cosmotune.remote.RemoteAction.PREVIOUS -> player.seekToPreviousMediaItem()
                    com.cosmotune.remote.RemoteAction.SEEK -> player.seekTo(checkNotNull(command.positionMs))
                    com.cosmotune.remote.RemoteAction.VOLUME -> player.volume = checkNotNull(command.volume)
                    com.cosmotune.remote.RemoteAction.SHUFFLE -> player.shuffleModeEnabled = !player.shuffleModeEnabled
                    com.cosmotune.remote.RemoteAction.REPEAT -> player.repeatMode = when (player.repeatMode) { 0 -> 2; 2 -> 1; else -> 0 }
                    com.cosmotune.remote.RemoteAction.PLAY_TRACK -> player.playSongs(listOf(checkNotNull(latestLibrary.songs.firstOrNull { it.videoId == command.trackId })), 0)
                    com.cosmotune.remote.RemoteAction.PLAY_QUEUE_INDEX -> {
                        val index = checkNotNull(command.index)
                        require(index in 0 until player.mediaItemCount)
                        player.seekToDefaultPosition(index); player.play()
                    }
                    com.cosmotune.remote.RemoteAction.LIKE -> error("Change favourites from this device")
                }
            }
            override suspend fun search(query: String) = latestLibrary.songs.filter { (it.title + " " + it.artist).contains(query, ignoreCase = true) }
                .take(50).map { com.cosmotune.remote.RemoteTrack(it.videoId, it.title, it.artist) }
            override suspend fun queue() = withContext(Dispatchers.Main.immediate) {
                val player = latestController
                if (player == null) com.cosmotune.remote.RemoteQueue() else com.cosmotune.remote.RemoteQueue(player.currentMediaItemIndex,
                    (0 until minOf(player.mediaItemCount, 100)).map { index ->
                        val metadata = player.getMediaItemAt(index).mediaMetadata
                        com.cosmotune.remote.RemoteQueueItem(index, metadata.title?.toString().orEmpty(), metadata.artist?.toString().orEmpty())
                    })
            }
        })
    }
    DisposableEffect(remote) { onDispose { remote.close() } }
    val localPlaylists by LocalPlaylistStore.playlists.collectAsState()
    val host = ListeningToolsHost(
        file = File(context.filesDir, "cosmotune-listening.json"), library = completeLibrary, queue = queue,
        remote = ListeningRemoteHost(remote) { link -> com.cosmotune.music.ui.components.QrCode(link) },
        syncLibraryReady = true,
        backgroundPlaylistUpdates = true,
        syncPlaylists = localPlaylists.map { playlist -> com.cosmotune.remote.SyncPlaylist(playlist.id.removePrefix("synced:"), playlist.title,
            playlist.songs.map { song -> com.cosmotune.remote.SyncTrack(song.videoId, song.title, song.artist, song.albumName, song.durationText) }) },
        applySyncedPlaylists = { records, files -> LocalPlaylistStore.applySyncedPlaylists(records, files) },
        queuePlaylistAdditions = { songs, title ->
            require(AppSettings.downloadsAllowedNow) { "Downloads are restricted to an unmetered connection." }
            songs.forEach { Downloads.enqueue(context, it, title) }
            Downloads.flushQueueJournal()
            require(songs.all { Downloads.savedUri(context, it.videoId) != null || Downloads.active.value[it.videoId] is com.cosmotune.music.download.DownloadState.Queued ||
                Downloads.active.value[it.videoId] is com.cosmotune.music.download.DownloadState.Running }) { "Some additions could not be queued. Check Downloads and retry." }
        },
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
            check(!com.cosmotune.music.data.listentogether.ListenTogether.state.value.inParty) { "Leave Listen Together before opening a saved session." }
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
            check(!com.cosmotune.music.data.listentogether.ListenTogether.state.value.inParty) { "Leave Listen Together before opening a smart playlist." }
            checkNotNull(controller) { "The player is starting. Try again in a moment." }.playSongs(songs, 0)
        },
        openRelease = openRelease, findMusic = findMusic, importMusic = importMusic,
        requestLosslessCopies = { songs ->
            check(AppSettings.downloadsAllowedNow) { "Downloads are limited to Wi-Fi right now." }
            songs.forEach { Downloads.enqueue(context, it, from = "Lossless copies", quality = com.cosmotune.music.data.settings.DownloadQuality.LOSSLESS) }
            songs.size
        },
        applySetup = { preferences, lowData ->
            if (lowData) {
                AppSettings.setAudioQualityWifi(AudioQuality.LOW)
                AppSettings.setAudioQualityCellular(AudioQuality.LOW)
                AppSettings.setAnimatedCanvas(false)
            }
            val wanted = LocaleListCompat.forLanguageTags(preferences.contentLanguage)
            // Setting the app locale recreates the activity; only do it when the choice really changes it,
            // and do not open local music (and its permission prompt) underneath that recreation.
            val localeChanges = AppCompatDelegate.getApplicationLocales().toLanguageTags() != wanted.toLanguageTags()
            if (preferences.preferLocal && !localeChanges) startLocal()
            if (localeChanges) AppCompatDelegate.setApplicationLocales(wanted)
        },
        exportFile = { name, text ->
            check(pending == null) { "Finish the open file chooser first." }
            val answer = CompletableDeferred<Uri?>().also { pending = it }
            picker.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = when { name.endsWith(".m3u8", true) -> "application/vnd.apple.mpegurl"; name.endsWith(".txt") -> "text/plain"; else -> "application/json" }
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
        importPlaylistFile = {
            check(pending == null) { "Finish the open file chooser first." }
            val answer = CompletableDeferred<Uri?>().also { pending = it }
            picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/*", "audio/x-mpegurl", "application/x-mpegurl",
                    "application/vnd.apple.mpegurl", "application/octet-stream"))
            })
            answer.await()?.let { uri -> withContext(Dispatchers.IO) {
                val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } ?: "Imported playlist"
                val bytes = checkNotNull(context.contentResolver.openInputStream(uri)) { "The selected file could not be opened." }.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= MusicPlaylistFile.MAX_BYTES) { "Choose a music playlist smaller than 4 MB." }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                MusicPlaylistFile.decode(name, bytes)
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
                    val url = com.cosmotune.music.data.innertube.StreamResolver.resolve(song.videoId)
                    ListeningNetwork.readMedia(url, com.cosmotune.music.data.innertube.StreamResolver.mediaHeadersFor(url))
                }
            }
        } },
        available = { track ->
            when {
                track.path?.let { File(it).let { file -> file.isFile && file.canRead() } } == true -> true
                track.uri != null -> runCatching { context.contentResolver.openAssetFileDescriptor(Uri.parse(track.uri), "r")?.use { true } ?: false }.getOrDefault(false)
                track.path != null -> false
                else -> true
            }
        },
        libraryAccess = remember(context) { PhoneLibraryAccess(context) },
        pcmDecoder = remember(context) { PhonePcmDecoder(context) },
        onLocalFileChanged = { path -> android.media.MediaScannerConnection.scanFile(context, arrayOf(path), null, null) },
        // Shared-storage media go to the system trash after the platform's own consent dialog; app downloads are managed in Downloads.
        trashFiles = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) { songs ->
            val uris = songs.mapNotNull { song -> song.localUri?.let(Uri::parse)?.takeIf { it.scheme == "content" && it.authority == android.provider.MediaStore.AUTHORITY } }
            if (uris.isEmpty()) 0 else {
                check(trashPending == null) { "Finish the open confirmation first." }
                val answer = CompletableDeferred<Boolean>().also { trashPending = it }
                val request = android.provider.MediaStore.createTrashRequest(context.contentResolver, uris, true)
                trashConsent.launch(androidx.activity.result.IntentSenderRequest.Builder(request.intentSender).build())
                if (answer.await()) uris.size else 0
            }
        } else null,
        sourceSummary = "Checks use your enabled sources and current streaming quality.",
        languageChoices = listOf("" to context.getString(com.cosmotune.music.R.string.system)) +
            com.cosmotune.music.ui.components.SUPPORTED_LANGUAGES.map { it.tag to context.getString(it.nameRes) },
        existingUser = library.songs.isNotEmpty(),
        existingUserKnown = libraryKnown,
    )
    ListeningTools(open, host, onDismiss, openOn = openOn)
}
