package com.cosmotune.music.data.scrobbling

import com.cosmotune.music.data.DebugLog as Log
import com.cosmotune.music.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToLong

class ScrobbleManager(
    private val scope: CoroutineScope,
    /** Listens that could not be sent while offline; null keeps the old send-once behaviour. */
    private val queue: com.cosmotune.listening.ScrobbleQueue? = null,
    var minSongDuration: Int = 30,
    var scrobbleDelayPercent: Float = 0.5f,
    var scrobbleDelaySeconds: Int = 180,
) {
    private var scrobbleJob: Job? = null
    private var scrobbleRemainingMillis: Long = 0L
    private var scrobbleTimerStartedAt: Long = 0L
    private var songStartedAt: Long = 0L
    private var songStarted = false
    var useNowPlaying = true
    var usePrimaryArtistOnly = false

    fun destroy() {
        scrobbleJob?.cancel()
        scrobbleRemainingMillis = 0L
        scrobbleTimerStartedAt = 0L
        songStartedAt = 0L
        songStarted = false
    }

    fun onSongStart(
        song: Song?,
        durationMs: Long? = null,
    ) {
        if (song == null) return
        songStartedAt = System.currentTimeMillis() / 1000
        songStarted = true
        startScrobbleTimer(song, durationMs)
        if (useNowPlaying) {
            updateNowPlaying(song)
        }
    }

    fun onSongResume(song: Song) {
        resumeScrobbleTimer(song)
    }

    fun onSongPause() {
        pauseScrobbleTimer()
    }

    fun onSongStop() {
        stopScrobbleTimer()
        songStarted = false
    }

    private fun startScrobbleTimer(
        song: Song,
        durationMs: Long? = null,
    ) {
        scrobbleJob?.cancel()
        val resolvedDurationSeconds = durationMs?.toInt()?.div(1000)
            ?: song.durationText?.let { parseDurationSeconds(it) }
            ?: return

        if (resolvedDurationSeconds <= minSongDuration) return

        val thresholdMs = (resolvedDurationSeconds * 1000L * scrobbleDelayPercent).roundToLong()
        scrobbleRemainingMillis = min(thresholdMs, scrobbleDelaySeconds * 1000L)

        if (scrobbleRemainingMillis <= 0) {
            scrobbleSong(song, resolvedDurationSeconds)
            return
        }
        scrobbleTimerStartedAt = System.currentTimeMillis()
        scrobbleJob =
            scope.launch {
                delay(scrobbleRemainingMillis)
                scrobbleSong(song, resolvedDurationSeconds)
                scrobbleJob = null
            }
    }

    private fun pauseScrobbleTimer() {
        scrobbleJob?.cancel()
        if (scrobbleTimerStartedAt != 0L) {
            val elapsed = System.currentTimeMillis() - scrobbleTimerStartedAt
            scrobbleRemainingMillis -= elapsed
            if (scrobbleRemainingMillis < 0) scrobbleRemainingMillis = 0
            scrobbleTimerStartedAt = 0L
        }
    }

    private fun resumeScrobbleTimer(song: Song) {
        if (scrobbleRemainingMillis <= 0) return
        scrobbleJob?.cancel()
        scrobbleTimerStartedAt = System.currentTimeMillis()
        scrobbleJob =
            scope.launch {
                delay(scrobbleRemainingMillis)
                val durationSeconds = song.durationText?.let { parseDurationSeconds(it) } ?: 0
                scrobbleSong(song, durationSeconds)
                scrobbleJob = null
            }
    }

    private fun stopScrobbleTimer() {
        scrobbleJob?.cancel()
        scrobbleJob = null
        scrobbleRemainingMillis = 0
    }

    private fun scrobbleSong(song: Song, durationSeconds: Int) {
        val scrobbleArtist = song.artist.forScrobble()
        val listen = com.cosmotune.listening.PendingScrobble("lastfm", scrobbleArtist, song.title, songStartedAt, song.albumName, durationSeconds)
        scope.launch {
            // Earlier listens first, so the service receives them in order; then this one.
            queue?.flush(submit = ::submitQueued)
            val outcome = submitQueued(listen)
            if (outcome == com.cosmotune.listening.ScrobbleOutcome.SENT) Log.d(TAG, "Scrobbled: ${song.title} by ${song.artist}")
            else if (outcome == com.cosmotune.listening.ScrobbleOutcome.RETRY_LATER) {
                queue?.add(listen)
                Log.d(TAG, "Offline; kept scrobble for later: ${song.title}")
            }
        }
    }

    /** Retries listens saved while offline; called on start and after each successful scrobble. */
    fun flushPending() {
        scope.launch { queue?.flush(service = "lastfm", submit = ::submitQueued) }
    }

    private suspend fun submitQueued(item: com.cosmotune.listening.PendingScrobble): com.cosmotune.listening.ScrobbleOutcome {
        val result = LastFM.scrobble(artist = item.artist, track = item.track, duration = item.durationSeconds,
            timestamp = item.timestampSeconds, album = item.album)
        val error = result.exceptionOrNull()
        if (error is CancellationException) throw error
        if (error != null) Log.e(TAG, "Failed to scrobble: ${item.track}", error)
        return when {
            error is LastFM.LastFmException && (error.code == 11 || error.code == 16 || error.code >= 500) -> com.cosmotune.listening.ScrobbleOutcome.RETRY_LATER
            else -> com.cosmotune.listening.ScrobbleQueue.outcomeOf(error)
        }
    }

    private fun updateNowPlaying(song: Song) {
        val scrobbleArtist = song.artist.forScrobble()
        scope.launch {
            LastFM
                .updateNowPlaying(
                    artist = scrobbleArtist,
                    track = song.title,
                    album = song.albumName,
                    duration = song.durationText?.let { parseDurationSeconds(it) },
                ).onSuccess {
                    Log.d(TAG, "Updated now playing: ${song.title}")
                }.onFailure { throwable ->
                    if (throwable is CancellationException) throw throwable
                    Log.e(TAG, "Failed to update now playing: ${song.title}", throwable)
                }
        }
    }

    fun onPlayerStateChanged(
        isPlaying: Boolean,
        song: Song?,
        durationMs: Long? = null,
    ) {
        if (song == null) return
        if (isPlaying) {
            if (!songStarted) {
                onSongStart(song, durationMs)
            } else {
                onSongResume(song)
            }
        } else {
            onSongPause()
        }
    }

    /**
     * Parse "M:SS" or "MM:SS" duration text to total seconds.
     */
    private fun parseDurationSeconds(text: String): Int {
        val parts = text.split(":")
        if (parts.size != 2) return 0
        val minutes = parts[0].toIntOrNull() ?: return 0
        val seconds = parts[1].toIntOrNull() ?: return 0
        return minutes * 60 + seconds
    }

    private fun String.forScrobble(): String = if (usePrimaryArtistOnly) primaryArtist() else this

    companion object {
        private const val TAG = "ScrobbleManager"
    }
}
