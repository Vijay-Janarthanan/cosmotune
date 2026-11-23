package com.cosmotune.reading

import androidx.compose.runtime.*
import androidx.media3.common.PlaybackParameters
import com.cosmotune.music.playback.playSongs
import com.cosmotune.music.playback.rememberMediaController
import com.cosmotune.music.playback.rememberPlayerState
import com.cosmotune.reading.ui.ReadingAudioHost
import com.cosmotune.reading.ui.ReadingAudioItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The phone's player lent to Read for audiobooks: they play in the background like any track, with Music's small player. */
@Composable
fun rememberPhoneReadingAudio(): ReadingAudioHost {
    val controller = rememberMediaController()
    val state = rememberPlayerState(controller)
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(controller)
    val host = remember {
        ReadingAudioHost(
            play = { items: List<ReadingAudioItem>, index: Int, positionMs: Long ->
                scope.launch {
                    val player = latest ?: return@launch
                    try { player.playSongs(items.map(ReadingAudioItem::song), index, positionMs) }
                    catch (e: CancellationException) { throw e } catch (_: Exception) { }
                }
            },
            toggle = { latest?.let { if (it.isPlaying) it.pause() else it.play() } },
            seek = { latest?.seekTo(it) },
            speed = { latest?.playbackParameters = PlaybackParameters(it) },
        )
    }
    SideEffect {
        host.update(state.song?.videoId, state.isPlaying, state.isLoading, state.position.positionMs, state.durationMs, controller?.playbackParameters?.speed ?: 1f)
    }
    return host
}
