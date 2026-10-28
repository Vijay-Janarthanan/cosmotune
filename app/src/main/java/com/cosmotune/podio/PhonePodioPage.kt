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
import com.cosmotune.podio.ui.PodioApp
import com.cosmotune.podio.ui.PodioPlayback
import java.io.File

/** Uses the existing foreground media service, notification, audio focus and Bluetooth controls. */
@Composable fun PhonePodioPage(onMusic: () -> Unit) {
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
        picker.launch(Intent(if (saving) Intent.ACTION_CREATE_DOCUMENT else Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = if (saving) "text/x-opml" else "*/*"
            if (saving) putExtra(Intent.EXTRA_TITLE, "Cosmotune-podcasts.opml")
        })
        return answer.await()
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
    val sleepMinutes by com.cosmotune.music.playback.SleepTimer.minutes.collectAsState()
    val sleepAfterEpisode by com.cosmotune.music.playback.SleepTimer.afterTrack.collectAsState()
    var pending by remember { mutableStateOf<Pair<PodioItem, Long>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(controller, pending) {
        val selected = pending ?: return@LaunchedEffect
        val player = controller ?: return@LaunchedEffect
        try {
            player.playSongs(listOf(selected.first.song()), 0)
            if (selected.second > 0) player.seekTo(selected.second)
            pending = null
        } catch(e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { failed = true; pending = null }
    }
    val current = pending?.first
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
    PodioApp(File(context.filesDir, "podio/library.json"),
        PodioPlayback(current?.id ?: state.song?.videoId, current?.title ?: state.song?.title.orEmpty(),
            current?.author ?: state.song?.artist.orEmpty(), state.isPlaying, pending != null || state.isLoading,
            state.position.positionMs, state.durationMs, if (failed) "Playback failed" else state.error, rate = controller?.playbackParameters?.speed ?: 1f),
        onPlay = { item, position ->
            com.cosmotune.music.data.NerdStats.recordSource(item.id, podioSourceName(item.id))
            failed = false; pending = item to position
        },
        onToggle = { controller?.let { if (it.isPlaying) it.pause() else it.play() } },
        onSeek = { controller?.seekTo(it) }, onSpeed = { controller?.playbackParameters = PlaybackParameters(it) }, onMusic = onMusic,
        sleepTimerMinutes = sleepMinutes, sleepAfterEpisode = sleepAfterEpisode,
        onSleepTimer = { minutes -> if (minutes == null) com.cosmotune.music.playback.SleepTimer.cancel() else com.cosmotune.music.playback.SleepTimer.start(minutes) },
        onSleepAfterEpisode = { com.cosmotune.music.playback.SleepTimer.startAfterTrack() },
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
        episodeDownloadStatus = activeDownloads.mapValues { (_, status) -> when (status) {
            DownloadState.Queued -> "Queued"
            is DownloadState.Running -> "Downloading ${(status.fraction.coerceIn(0f, 1f) * 100).toInt()}%"
            is DownloadState.Failed -> "Download failed: ${status.reason} · tap to retry"
        } },
        episodeDownloadsRunning = activeDownloads.filterValues { it !is DownloadState.Failed }.keys,
        onImportOpmlFile = { chooseFile(false)?.let { uri -> withContext(Dispatchers.IO) {
            requireNotNull(context.contentResolver.openInputStream(uri)).use(PodioOpml::read)
        } } },
        onExportOpmlFile = { text ->
            val uri = chooseFile(true)
            if (uri == null) false else withContext(Dispatchers.IO) {
                requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).bufferedWriter(Charsets.UTF_8).use { it.write(text) }; true
            }
        },
        onShareOpmlFile = { text ->
            val file = withContext(Dispatchers.IO) { File(context.cacheDir, "shared/Cosmotune-podcasts.opml").apply { parentFile?.mkdirs(); writeText(text, Charsets.UTF_8) } }
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/x-opml"; putExtra(Intent.EXTRA_STREAM, uri); clipData = android.content.ClipData.newRawUri("Podcast subscriptions", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share podcast subscriptions"))
        })
    }
}
