package com.opencanvas.core

import com.opencanvas.core.models.OpenCanvasMode
import com.opencanvas.core.models.OpenCanvasResolution
import com.opencanvas.core.models.OpenCanvasTrack
import com.opencanvas.core.models.SyncStage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile

/**
 * OpenCanvas Engine Entry Point.
 *
 * Resolves, for any song, the music video that goes with it and - in
 * [OpenCanvasMode.FULL_SYNCED_VIDEO] - the map that says which second of the video belongs to which
 * second of the song, so a player can show the picture locked to the music. Everything runs inside
 * the app that uses it: no server, no helper process, the same code on Android and on the desktop.
 *
 * ```kotlin
 * OpenCanvas.resolveFlow("Blinding Lights", "The Weeknd", mode = OpenCanvasMode.FULL_SYNCED_VIDEO,
 *     trackVideoId = "J7p4bzqLvCw").collect { track ->
 *     player.show(track.videoStreamUrl, track.syncMap())   // called again when 720p or the map arrives
 * }
 * ```
 *
 * Attribution: OpenCanvas by Vijay (Apache 2.0).
 */
object OpenCanvas {

    const val VERSION = "1.0.0"
    const val AUTHOR = "Vijay"

    private const val MAX_SESSIONS = 64

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var environment: Environment? = null
    private val sessions = object : LinkedHashMap<String, CanvasSession>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CanvasSession>): Boolean = size > MAX_SESSIONS
    }

    /**
     * Replaces the defaults (see [OpenCanvasConfig]); call it once, before the first resolve. Work in
     * flight under the old configuration is cancelled and forgotten.
     */
    fun configure(config: OpenCanvasConfig) {
        synchronized(lock) {
            sessions.values.forEach { it.cancel() }
            sessions.clear()
            environment = Environment(config)
        }
    }

    /**
     * Resolves an [OpenCanvasTrack] and keeps it up to date: a cold flow that emits the track as soon
     * as its video stream is known ([SyncStage.PENDING], buffer but do not show), again when the
     * song-to-video map is ready ([SyncStage.MEASURED]) and again if a taller stream turns up, then
     * completes. Collectors of the same request share one measurement; abandoning the flow before the
     * map exists cancels the work.
     *
     * The map is measured from the song's audio, which the library reads from [songAudio] or from the
     * YouTube video [trackVideoId]; without either the track comes back unsynced
     * ([SyncStage.NONE]) and a player should show a still image instead. A song measured before (or
     * published to the community maps) starts with its map already in place.
     *
     * @param title Track title (e.g., "Starboy")
     * @param artist Artist name (e.g., "The Weeknd")
     * @param durationSec Track duration in seconds (optional, helps candidate verification)
     * @param mode [OpenCanvasMode.LOOP_CANVAS] (8-12s chorus loop) or [OpenCanvasMode.FULL_SYNCED_VIDEO]
     * @param resolution Target [OpenCanvasResolution]. The built-in client serves 360p at once; a
     *   taller stream from [OpenCanvasConfig.streamBackend] replaces it when it arrives.
     * @param trackVideoId YouTube (Music) video id of the song being played; shorthand for
     *   `songAudio = SongAudio.YouTube(trackVideoId)`.
     * @param songAudio Where the song's own audio is readable, for songs that do not come from YouTube.
     * @param videoId Music video to use instead of searching for one.
     */
    fun resolveFlow(
        title: String,
        artist: String,
        durationSec: Long = 0L,
        mode: OpenCanvasMode = OpenCanvasMode.LOOP_CANVAS,
        resolution: OpenCanvasResolution = OpenCanvasResolution.STANDARD_480P,
        trackVideoId: String? = null,
        songAudio: SongAudio? = null,
        videoId: String? = null,
    ): Flow<OpenCanvasTrack> = flow {
        val session = session(request(title, artist, durationSec, mode, resolution, trackVideoId, songAudio, videoId))
        session.attach()
        try {
            session.updates().collect { emit(it) }
        } finally {
            session.detach()
        }
    }

    /**
     * Starts resolving a track the host expects to ask about in a moment (the song that has just begun
     * playing, before its player screen exists) so the later [resolveFlow] or [resolve] with the same
     * arguments finds the work done or well under way. Returns at once; the work runs on the
     * library's own scope and is dropped if nothing asks for it within a short while and no map is
     * known.
     */
    fun prefetch(
        title: String,
        artist: String,
        durationSec: Long = 0L,
        mode: OpenCanvasMode = OpenCanvasMode.LOOP_CANVAS,
        resolution: OpenCanvasResolution = OpenCanvasResolution.STANDARD_480P,
        trackVideoId: String? = null,
        songAudio: SongAudio? = null,
        videoId: String? = null,
    ) {
        session(request(title, artist, durationSec, mode, resolution, trackVideoId, songAudio, videoId)).warm()
    }

    private fun request(
        title: String,
        artist: String,
        durationSec: Long,
        mode: OpenCanvasMode,
        resolution: OpenCanvasResolution,
        trackVideoId: String?,
        songAudio: SongAudio?,
        videoId: String?,
    ): CanvasRequest {
        val song = songAudio ?: trackVideoId?.trim()?.takeIf { it.isNotEmpty() }?.let { SongAudio.YouTube(it) }
        return CanvasRequest(title, artist, durationSec, mode, resolution, song, videoId?.trim()?.takeIf { it.isNotEmpty() })
    }

    /**
     * Resolves an [OpenCanvasTrack] and returns as soon as it is usable: with its song-to-video map
     * in [OpenCanvasMode.FULL_SYNCED_VIDEO] mode, otherwise with its stream. If the map cannot be
     * measured the track comes back with [SyncStage.NONE] (hosts decide whether to show the video).
     * Use [resolveFlow] to also receive the sharper stream that may follow.
     *
     * @return The resolved track, or null if no official video was matched.
     */
    suspend fun resolve(
        title: String,
        artist: String,
        durationSec: Long = 0L,
        mode: OpenCanvasMode = OpenCanvasMode.LOOP_CANVAS,
        resolution: OpenCanvasResolution = OpenCanvasResolution.STANDARD_480P,
        trackVideoId: String? = null,
        songAudio: SongAudio? = null,
        videoId: String? = null,
    ): OpenCanvasTrack? {
        var latest: OpenCanvasTrack? = null
        resolveFlow(title, artist, durationSec, mode, resolution, trackVideoId, songAudio, videoId)
            .takeWhile { track ->
                latest = track
                track.syncStage == SyncStage.PENDING
            }
            .collect { }
        return latest
    }

    /** Clears every session and every measured map, in memory and on disk. */
    fun clearCache() {
        synchronized(lock) {
            sessions.values.forEach { it.cancel() }
            sessions.clear()
            env().store.clear()
        }
    }

    private fun env(): Environment = synchronized(lock) { environment ?: Environment(OpenCanvasConfig()).also { environment = it } }

    private fun session(request: CanvasRequest): CanvasSession = synchronized(lock) {
        val existing = sessions[request.key]
        if (existing != null && !existing.isStale()) return existing
        CanvasSession(request, env(), scope).also { sessions[request.key] = it }
    }
}
