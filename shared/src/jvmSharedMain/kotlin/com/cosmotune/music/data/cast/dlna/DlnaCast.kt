package com.cosmotune.music.data.cast.dlna

import com.cosmotune.music.data.innertube.StreamResolver
import com.cosmotune.music.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val ownership = Mutex()
    @Volatile private var requestEpoch = 0L

    suspend fun connect(device: DlnaDevice): Boolean = ownership.withLock {
        requestEpoch++
        val old = rendererFlow.value
        if (old != null && !old.stopConfirmed()) {
            errorFlow.value = "${old.device.name} did not confirm Stop. The new receiver stays paused."
            return@withLock false
        }
        disconnectOwned(stopPlayback = false)
        val candidate = DlnaRenderer(device)
        try {
                candidate.connect()
                rendererFlow.value = candidate
                errorFlow.value = null
                followQueue(candidate)
                true
        } catch (cancelled: CancellationException) {
                candidate.close(stopPlayback = false)
                throw cancelled
        } catch (_: Exception) {
                candidate.close(stopPlayback = false)
                errorFlow.value = "${device.name} did not answer."
                false
        }
    }

    suspend fun cast(song: Song, startMs: Long = 0): Boolean = ownership.withLock {
        castOwned(song, startMs)
    }

    /** Caller owns [ownership]; a finished old queue must never target whatever receiver replaced it. */
    private suspend fun castOwned(song: Song, startMs: Long): Boolean {
        currentCoroutineContext().ensureActive()
        val target = rendererFlow.value ?: return false
        val token = ++requestEpoch
        val media = try { mediaFor(song, target.device) } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            errorFlow.value = failure.message ?: "This song could not be prepared for the renderer."
            return false
        }
        try { target.load(media, startMs) } catch (cancelled: CancellationException) {
            // A cancelled load may already have reached Play on the receiver while local audio is still running.
            val stopped = withContext(NonCancellable) { target.stopConfirmed() }
            if (!stopped) {
                errorFlow.value = "${target.device.name} did not confirm Stop after the cancelled load. Local playback was paused."
                pauseLocal?.invoke()
            }
            throw cancelled
        }
        if (requestEpoch != token || rendererFlow.value !== target) return false
        if (target.state.value.failed) {
            errorFlow.value = "${target.device.name} would not play this song."
            return false
        }
        songFlow.value = song
        errorFlow.value = null
        pauseLocal?.invoke()
        return true
    }

    /** Pauses the renderer that has the song, if one has; the sleep timer's way to reach it. Returns at once. */
    fun pauseRenderer() {
        val target = rendererFlow.value ?: return
        scope.launch { ownership.withLock { if (rendererFlow.value === target) runCatching { target.pause() } } }
    }

    suspend fun disconnect(stopPlayback: Boolean = true) = ownership.withLock {
        requestEpoch++
        disconnectOwned(stopPlayback)
    }

    private suspend fun disconnectOwned(stopPlayback: Boolean) {
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
                val token = ownership.withLock { requestEpoch.takeIf { rendererFlow.value === target } } ?: return@collect
                val next = try { nextSong?.invoke() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null } ?: return@collect
                ownership.withLock {
                    currentCoroutineContext().ensureActive()
                    if (rendererFlow.value === target && requestEpoch == token) castOwned(next, 0)
                }
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
