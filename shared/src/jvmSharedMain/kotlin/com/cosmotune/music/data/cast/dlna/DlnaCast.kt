package com.cosmotune.music.data.cast.dlna

import com.cosmotune.music.data.innertube.StreamResolver
import com.cosmotune.music.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.net.URI

/**
 * Casting to DLNA / UPnP renderers on the local network, beside the phone's Chromecast support and on the desktop too.
 *
 * [discovery] finds renderers, [connect] takes one, and [cast] sends it a song through [DlnaMediaRelay] (YouTube's audio as
 * AAC in MP4, the format renderers most often play, or a file on this device). Play, pause, seek and volume go to the
 * renderer itself. The apps install [pauseLocal], so the device stops playing when the renderer starts, and [nextSong],
 * so the renderer moves on to the queue's next song when one ends.
 */
object DlnaCast {

    val discovery = DlnaDiscovery()
    private val relay = DlnaMediaRelay()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val rendererFlow = MutableStateFlow<DlnaRenderer?>(null)
    val renderer: StateFlow<DlnaRenderer?> = rendererFlow.asStateFlow()

    private val songFlow = MutableStateFlow<Song?>(null)
    val song: StateFlow<Song?> = songFlow.asStateFlow()

    private val errorFlow = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = errorFlow.asStateFlow()

    /** Pauses this device's own playback once a renderer has the song. */
    @Volatile var pauseLocal: (() -> Unit)? = null

    /** Moves the local queue on and answers the song now current, or null at its end. */
    @Volatile var nextSong: (suspend () -> Song?)? = null

    private var follow: Job? = null

    suspend fun connect(device: DlnaDevice): Boolean {
        disconnect(stopPlayback = false)
        val candidate = DlnaRenderer(device)
        return runCatching { candidate.connect() }.fold(
            onSuccess = {
                rendererFlow.value = candidate
                errorFlow.value = null
                followQueue(candidate)
                true
            },
            onFailure = {
                errorFlow.value = "${device.name} did not answer."
                false
            },
        )
    }

    suspend fun cast(song: Song, startMs: Long = 0): Boolean {
        val target = rendererFlow.value ?: return false
        val media = runCatching { mediaFor(song, target.device) }.getOrElse {
            errorFlow.value = it.message ?: "This song could not be prepared for the renderer."
            return false
        }
        target.load(media, startMs)
        if (target.state.value.failed) {
            errorFlow.value = "${target.device.name} would not play this song."
            return false
        }
        songFlow.value = song
        errorFlow.value = null
        pauseLocal?.invoke()
        return true
    }

    suspend fun disconnect(stopPlayback: Boolean = true) {
        follow?.cancel()
        follow = null
        rendererFlow.value?.close(stopPlayback)
        rendererFlow.value = null
        songFlow.value = null
    }

    private fun followQueue(target: DlnaRenderer) {
        follow?.cancel()
        follow = scope.launch {
            target.state.map { it.finished }.distinctUntilChanged().collect { finished ->
                if (!finished || rendererFlow.value !== target) return@collect
                val next = runCatching { nextSong?.invoke() }.getOrNull() ?: return@collect
                cast(next)
            }
        }
    }

    private suspend fun mediaFor(song: Song, device: DlnaDevice): DlnaMedia {
        val source = sourceFor(song)
        val url = relay.publish(source, DlnaMediaRelay.hostOf(device.avTransportUrl))
        return DlnaMedia(
            url = url,
            mimeType = source.mimeType,
            title = song.title,
            artist = song.artist,
            album = song.albumName,
            artworkUrl = song.thumbnailUrl?.takeIf { it.startsWith("http") },
            durationMs = (com.cosmotune.music.data.sources.TrackMatcher.secondsOf(song.durationText) ?: 0) * 1000L,
        )
    }

    /** Where the renderer's bytes come from: a file here, or YouTube's AAC stream relayed with its headers. */
    suspend fun sourceFor(song: Song): DlnaMediaRelay.Source {
        song.localUri?.let { local ->
            val file = when {
                local.startsWith("file:") -> runCatching { File(URI(local)) }.getOrNull()
                local.startsWith("/") || Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(local) -> File(local)
                else -> null
            }
            if (file != null && file.isFile) return DlnaMediaRelay.Source.LocalFile(file, mimeOf(file.name))
            error("Songs stored on the phone itself cannot be sent to a renderer yet.")
        }
        val stream = StreamResolver.resolveForDownload(song.videoId, Int.MAX_VALUE, requireM4a = true)
        return DlnaMediaRelay.Source.Remote(
            url = stream.url,
            headers = StreamResolver.mediaHeadersFor(stream.url),
            mimeType = stream.mimeType.substringBefore(';').trim().ifEmpty { "audio/mp4" },
        )
    }

    fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "flac" -> "audio/flac"
        "mp3" -> "audio/mpeg"
        "m4a", "mp4", "aac", "alac" -> "audio/mp4"
        "ogg", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        "webm" -> "audio/webm"
        else -> "application/octet-stream"
    }
}
