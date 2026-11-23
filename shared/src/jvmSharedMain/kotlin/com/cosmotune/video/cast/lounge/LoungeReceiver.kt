package com.cosmotune.video.cast.lounge

import com.cosmotune.video.player.PlaybackUiState
import com.cosmotune.video.player.PlayerStatus
import com.cosmotune.video.player.VideoPlayback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** What the "Link with TV code" sheet shows: the code to type (null while it is being made), the phones linked, a failure. */
data class LoungeUi(val code: String? = null, val remotes: List<LoungeRemote> = emptyList(), val failed: Boolean = false)

/** The part of a lounge screen the receiver uses (a fake in tests). */
interface LoungeLink {
    suspend fun screenId(saved: String?): String
    suspend fun connect(screenId: String): String
    suspend fun register(screenId: String, code: String)
    suspend fun run(onCommand: suspend (LoungeCommand) -> Unit)
    suspend fun send(events: List<Pair<String, Map<String, String>>>)

    /** This device's id in the lounge. */
    val deviceId: String get() = ""

    /** The lounge token once connected. */
    val loungeToken: String? get() = null
}

/**
 * Plays here what a phone casts with "Link with TV code" in the YouTube app: [link] is the lounge screen, [playback] the
 * player. A video sent is opened with [open] (its watch page); play, pause, seek, mute, next, previous and stop drive the
 * player; the player's state goes back so the phone's controls follow. A new code is registered every [codeEveryMs].
 */
class LoungeReceiver(
    private val link: LoungeLink,
    private val playback: VideoPlayback,
    private val savedScreenId: String?,
    private val saveScreenId: (String) -> Unit,
    private val open: (videoId: String, startMs: Long) -> Unit,
    private val codeEveryMs: Long = 4 * 60_000L,
    private val newCode: () -> String = { LoungeProtocol.newCode() },
) {
    private val ui = MutableStateFlow(LoungeUi())
    val state: StateFlow<LoungeUi> = ui.asStateFlow()

    @Volatile private var screenId: String? = null

    /** The lounge screen for a phone that finds this device on the home network (null until connected). */
    fun dialScreen(): com.cosmotune.video.cast.dial.DialScreen? =
        screenId?.let { com.cosmotune.video.cast.dial.DialScreen(it, link.deviceId, link.loungeToken) }

    /** Registers the code a phone on the home network sent with its launch, so it joins without the code typed. */
    suspend fun pair(code: String) {
        val id = screenId ?: return
        try {
            link.register(id, code)
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
        }
    }

    private var queue: List<String> = emptyList()
    private var index = 0
    private var volume = 100

    /** Runs until cancelled; a failure to reach the lounge shows on [state] and is tried again a little later. */
    suspend fun run() {
        while (coroutineContext.isActive) {
            try {
                session()
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                ui.update { it.copy(failed = true, code = null) }
                delay(30_000)
            }
        }
    }

    private suspend fun session() = coroutineScope {
        val id = link.screenId(savedScreenId)
        if (id != savedScreenId) saveScreenId(id)
        link.connect(id)
        screenId = id
        ui.update { it.copy(failed = false) }
        launch {
            while (isActive) {
                val code = newCode()
                link.register(id, code)
                ui.update { it.copy(code = code) }
                delay(codeEveryMs)
            }
        }
        launch {
            playback.state.map { Report(it.item?.id, stateOf(it), it.durationMs) }.distinctUntilChanged().collect { report ->
                val fields = LoungeProtocol.stateFields(report.videoId, playback.positionMs(), report.durationMs, report.state, indexOf(report.videoId))
                link.send(listOf("onStateChange" to fields, "nowPlaying" to fields))
            }
        }
        link.run { handle(it) }
    }

    private data class Report(val videoId: String?, val state: LoungeState, val durationMs: Long)

    internal suspend fun handle(command: LoungeCommand) {
        val now = playback.state.value
        when (command) {
            is LoungeCommand.SetPlaylist -> {
                queue = command.videoIds
                index = command.index.coerceIn(0, (queue.size - 1).coerceAtLeast(0))
                open(command.videoId, command.startMs)
            }
            LoungeCommand.Play -> if (!now.playing) playback.togglePlayPause()
            LoungeCommand.Pause -> if (now.playing) playback.togglePlayPause()
            is LoungeCommand.SeekTo -> playback.seekTo(command.ms)
            is LoungeCommand.SetVolume -> {
                volume = command.volume
                playback.setMuted(command.muted || command.volume == 0)
                link.send(listOf("onVolumeChanged" to volumeFields(command.muted || command.volume == 0)))
            }
            LoungeCommand.GetVolume -> link.send(listOf("onVolumeChanged" to volumeFields(now.muted)))
            LoungeCommand.Next -> if (index + 1 < queue.size) { index++; open(queue[index], 0) } else playback.playNext()
            LoungeCommand.Previous -> if (index > 0) { index--; open(queue[index], 0) } else playback.seekTo(0)
            LoungeCommand.Stop -> playback.stop()
            LoungeCommand.GetNowPlaying -> link.send(
                listOf("nowPlaying" to if (now.item == null) emptyMap() else LoungeProtocol.stateFields(now.item.id, playback.positionMs(), now.durationMs, stateOf(now), indexOf(now.item.id))),
            )
            is LoungeCommand.Remotes -> ui.update { it.copy(remotes = command.remotes) }
            is LoungeCommand.RemoteConnected -> ui.update { s -> s.copy(remotes = (s.remotes.filterNot { it.id == command.remote.id } + command.remote)) }
            is LoungeCommand.RemoteDisconnected -> ui.update { s -> s.copy(remotes = s.remotes.filterNot { it.id == command.id }) }
            is LoungeCommand.Session, is LoungeCommand.GSession, is LoungeCommand.Other -> Unit
        }
    }

    private fun indexOf(videoId: String?): Int? = videoId?.let { queue.indexOf(it).takeIf { i -> i >= 0 } }

    private fun volumeFields(muted: Boolean) = mapOf("volume" to volume.toString(), "muted" to muted.toString())

    companion object {
        internal fun stateOf(s: PlaybackUiState): LoungeState = when {
            s.item == null || s.status == PlayerStatus.IDLE || s.status == PlayerStatus.ERROR -> LoungeState.STOPPED
            s.status == PlayerStatus.ENDED -> LoungeState.ENDED
            s.status == PlayerStatus.LOADING || s.status == PlayerStatus.BUFFERING -> LoungeState.BUFFERING
            s.playing -> LoungeState.PLAYING
            else -> LoungeState.PAUSED
        }
    }
}
