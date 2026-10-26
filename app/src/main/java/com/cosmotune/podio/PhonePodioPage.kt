package com.cosmotune.podio

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
        onSeek = { controller?.seekTo(it) }, onSpeed = { controller?.playbackParameters = PlaybackParameters(it) }, onMusic = onMusic)
    }
}
