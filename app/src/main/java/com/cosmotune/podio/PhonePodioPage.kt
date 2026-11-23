package com.cosmotune.podio

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.app.Activity
import android.content.Intent
import android.net.Uri
import com.cosmotune.music.download.Downloads
import com.cosmotune.music.download.DownloadState
import com.cosmotune.music.data.model.Song
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.media3.common.PlaybackParameters
import com.cosmotune.music.playback.rememberMediaController
import com.cosmotune.music.playback.rememberPlayerState
import com.cosmotune.music.playback.playSongs
import com.cosmotune.music.playback.toMediaItem
import com.cosmotune.podio.ui.PodioApp
import com.cosmotune.podio.ui.PodioPlayback
import java.io.File

/** Uses the existing foreground media service, notification, audio focus and Bluetooth controls. */
@Composable fun PhonePodioPage(remoteGlobeControls: Boolean = false, onMusic: () -> Unit) {
    val context = LocalContext.current
    val controller = rememberMediaController()
    val state = rememberPlayerState(controller)
    var pendingFile by remember { mutableStateOf<CompletableDeferred<Uri?>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        pendingFile?.complete(result.data?.data.takeIf { result.resultCode == Activity.RESULT_OK }); pendingFile = null
    }
    DisposableEffect(Unit) { onDispose { pendingFile?.cancel() } }
    suspend fun chooseFile(saving: Boolean): Uri? {
        check(pendingFile == null) { "Finish the open file chooser first." }
        val answer = CompletableDeferred<Uri?>().also { pendingFile = it }
        return try {
            picker.launch(Intent(if (saving) Intent.ACTION_CREATE_DOCUMENT else Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = if (saving) "text/x-opml" else "*/*"
                if (saving) putExtra(Intent.EXTRA_TITLE, "Cosmotune-podcasts.opml")
            })
            answer.await()
        } finally {
            if (pendingFile === answer) pendingFile = null
        }
    }
    val savedDownloads by Downloads.saved.collectAsState()
    val activeDownloads by Downloads.active.collectAsState()
    val offlineEpisodes by produceState<List<PodioItem>>(emptyList(), savedDownloads) {
        value = withContext(Dispatchers.IO) { Downloads.getDownloadedSongs(context).mapNotNull(Song::offlinePodioItem) }
    }
    var pendingLegacyDownload by remember { mutableStateOf<Song?>(null) }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pendingLegacyDownload?.let { song -> if (granted) Downloads.enqueue(context, song, "Podio") else android.widget.Toast.makeText(context, "Storage permission is needed to export this episode.", android.widget.Toast.LENGTH_SHORT).show() }
        pendingLegacyDownload = null
    }
    // The background refresh of followed shows runs while it is on and something is followed (Settings > Podio).
    val podioSaved by remember(context) { PodioLibrary(PodioRefreshWorker.libraryFile(context)).state }.collectAsState()
    val refreshWanted = podioSaved.refreshInBackground && podioSaved.shows.isNotEmpty()
    LaunchedEffect(refreshWanted) { runCatching { PodioRefreshWorker.schedule(context, refreshWanted) } }
    // How long each running download still needs.
    val eta = remember { PodioDownloadEta() }
    val sleepMinutes by com.cosmotune.music.playback.SleepTimer.minutes.collectAsState()
    val sleepAfterEpisode by com.cosmotune.music.playback.SleepTimer.afterTrack.collectAsState()
    var pending by remember { mutableStateOf<Pair<PodioItem, Long>?>(null) }
    // What the queue puts after the episode that starts ([pending]); handed to the player once it plays.
    var pendingUpcoming by remember { mutableStateOf<List<PodioItem>>(emptyList()) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(controller, pending) {
        val selected = pending ?: return@LaunchedEffect
        val player = controller ?: return@LaunchedEffect
        try {
            player.playSongs(listOf(selected.first.song()), 0)
            if (selected.second > 0) player.seekTo(selected.second)
            replaceUpcomingEpisodes(player, pendingUpcoming)
            pending = null
        } catch(e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { failed = true; pending = null }
    }
    val current = pending?.first
    // System Back leaves for Music; with no handler it closed the whole app.
    androidx.activity.compose.BackHandler(onBack = onMusic)
    // The page draws under the status bar (its bars pad themselves, the full-screen globe runs edge to edge) and the dock keeps
    // above the navigation bar; only the side insets (a landscape cut-out) are taken here.
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).imePadding()) {
    PodioApp(PodioRefreshWorker.libraryFile(context),
        PodioPlayback(current?.id ?: state.song?.videoId, current?.title ?: state.song?.title.orEmpty(),
            current?.author ?: state.song?.artist.orEmpty(), state.isPlaying, pending != null || state.isLoading,
            state.position.positionMs, state.durationMs, if (failed) "Playback failed" else state.error, rate = controller?.playbackParameters?.speed ?: 1f),
        onPlay = { item, position ->
            com.cosmotune.music.data.NerdStats.recordSource(item.id, podioSourceName(item.id))
            failed = false; pendingUpcoming = emptyList(); pending = item to position
        },
        // The queue goes into the player after the episode, so it plays on with the screen off or after leaving Podio.
        onPlayQueue = { item, position, upcoming ->
            com.cosmotune.music.data.NerdStats.recordSource(item.id, podioSourceName(item.id))
            failed = false; pendingUpcoming = upcoming; pending = item to position
        },
        onUpcoming = { upcoming -> if (pending == null) controller?.let { replaceUpcomingEpisodes(it, upcoming) } else pendingUpcoming = upcoming },
        onToggle = { controller?.let { if (it.isPlaying) it.pause() else it.play() } },
        onSeek = { controller?.seekTo(it) }, onSpeed = { controller?.playbackParameters = PlaybackParameters(it) }, onMusic = onMusic,
        sleepTimerMinutes = sleepMinutes, sleepAfterEpisode = sleepAfterEpisode,
        onSleepTimer = { minutes -> if (minutes == null) com.cosmotune.music.playback.SleepTimer.cancel() else com.cosmotune.music.playback.SleepTimer.start(minutes) },
        onSleepAfterEpisode = { com.cosmotune.music.playback.SleepTimer.startAfterTrack() },
        onRadioListen = { listen -> PodioRadioScrobbles.send?.invoke(listen) },
        onDownloadEpisode = { item ->
            when (activeDownloads[item.id]) {
                DownloadState.Queued, is DownloadState.Running -> Downloads.cancel(item.id)
                else -> {
                    val song = item.song()
                    if (com.cosmotune.music.data.settings.AppSettings.exportDownloads.value && com.cosmotune.music.download.DownloadStore.needsLegacyPermission() &&
                        androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        pendingLegacyDownload = song; storagePermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    } else Downloads.enqueue(context, song, "Podio")
                }
            }
        },
        downloadedEpisodes = offlineEpisodes,
        onRemoveDownload = { item -> Downloads.delete(context, item.id) },
        remoteGlobeControls = remoteGlobeControls,
        episodeDownloadStatus = activeDownloads.also { eta.keepOnly(it.keys) }.mapValues { (id, status) -> when (status) {
            DownloadState.Queued -> "Queued"
            is DownloadState.Running -> PodioFormatting.downloading(status.fraction, eta.left(id, status.fraction.coerceIn(0f, 1f)))
            is DownloadState.Failed -> "Download failed: ${status.reason} · tap to retry"
        } },
        episodeDownloadsRunning = activeDownloads.filterValues { it !is DownloadState.Failed }.keys,
        onShare = { subject, text ->
            runCatching {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, text), null))
            }
        },
        // Station recordings stay in the app's own Music folder on the device (no storage permission needed).
        recordingsFolder = remember(context) { File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC) ?: context.filesDir, "Cosmotune Radio") },
        onImportOpmlFile = { chooseFile(false)?.let { uri -> withContext(Dispatchers.IO) {
            requireNotNull(context.contentResolver.openInputStream(uri)).use(PodioOpml::read)
        } } })
    // The pages scroll under the status bar: its ground keeps the clock readable and the titles from running under it.
    com.cosmotune.ui.orbit.OrbitStatusBarScrim(Modifier.align(androidx.compose.ui.Alignment.TopCenter))
    }
}

/**
 * Puts [upcoming] after the episode the player is on, in place of whatever followed it, so Podio's queue plays on by itself. Nothing
 * changes while something other than an episode plays, or when the player already holds the same episodes in the same order.
 */
private fun replaceUpcomingEpisodes(player: androidx.media3.session.MediaController, upcoming: List<PodioItem>) {
    val current = player.currentMediaItem?.mediaId ?: return
    if (!current.startsWith("podio:episode:")) return
    val index = player.currentMediaItemIndex
    val count = player.mediaItemCount
    val existing = (index + 1 until count).map { player.getMediaItemAt(it).mediaId }
    val wanted = upcoming.map { it.id }.filter { it != current }
    if (existing == wanted) return
    if (count > index + 1) player.removeMediaItems(index + 1, count)
    if (wanted.isNotEmpty()) player.addMediaItems(upcoming.filter { it.id != current }.map { it.song().toMediaItem() })
}
