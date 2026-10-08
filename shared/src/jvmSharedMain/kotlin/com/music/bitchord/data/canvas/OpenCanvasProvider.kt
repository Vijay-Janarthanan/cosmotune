package com.music.bitchord.data.canvas

import com.music.bitchord.data.DebugLog
import com.opencanvas.core.OpenCanvas
import com.opencanvas.core.OpenCanvasConfig
import com.opencanvas.core.models.OpenCanvasMode
import com.opencanvas.core.models.OpenCanvasResolution
import com.opencanvas.core.models.OpenCanvasTrack
import com.opencanvas.core.models.SyncStage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Dynamic OpenCanvas provider for BitChord.
 *
 * Finds the artist's official music video for a track when no label-published motion artwork
 * exists, and returns it as a [CanvasArtwork] that *follows the song*: it carries the map from the
 * song's timeline to the video's, so the player can keep the picture at the song's position, jump
 * when the user seeks or skips, and never loop.
 *
 * Everything happens inside the app: the library reads the headers of the song's and the video's
 * files (a few hundred kilobytes) and aligns them on the device, so no server and no helper is
 * involved. A music video is only worth showing when it lines up with the audio, so a track whose
 * map could not be measured with confidence (a live cut, a cover, a different mix) answers null and
 * the still cover stays.
 *
 * Attribution: Powered by OpenCanvas (Created by Vijay).
 */
object OpenCanvasProvider {

    private const val TAG = "OpenCanvas"

    /** How long a taller stream may still arrive once the map is known; after that the canvas starts with what it has. */
    private const val UPGRADE_GRACE_MS = 1_500L

    /** Upper bound for the whole lookup. */
    private const val LOOKUP_TIMEOUT_MS = 20_000L

    @Volatile
    private var configured = false

    /**
     * Prepares the engine. [cacheDirectory] is where measured maps are kept so a song starts instantly
     * the next time; null keeps them in memory. Called once at startup; a lookup that comes before it
     * configures the engine with its defaults. Everything else is the library's own: it resolves the
     * streams (360p at once, 720p a moment later) and measures the map without help from the app.
     */
    fun configure(cacheDirectory: File?) {
        OpenCanvas.configure(OpenCanvasConfig(cacheDirectory = cacheDirectory, log = { DebugLog.d(TAG, it) }))
        configured = true
    }

    /**
     * Starts the lookup for a track that has just begun, before anything asks for its canvas, so the
     * player screen (which may open seconds later, or never) finds the work done or well under way.
     * Takes the same arguments as [search] and returns at once.
     */
    fun prefetch(
        title: String,
        artist: String,
        trackVideoId: String?,
        resolutionLabel: String = "480p",
        durationSec: Long = 0L,
    ) {
        if (trackVideoId.isNullOrBlank()) return
        if (!configured) configure(null)
        OpenCanvas.prefetch(
            title = title,
            artist = artist,
            durationSec = durationSec,
            mode = OpenCanvasMode.FULL_SYNCED_VIDEO,
            resolution = OpenCanvasResolution.fromLabel(resolutionLabel),
            trackVideoId = trackVideoId,
        )
    }

    /**
     * Searches for a synced music video for [title] and [artist].
     *
     * [trackVideoId] is the YouTube (Music) id of the song being played: the map is measured between
     * that audio and the video's, so without it nothing can be aligned and the answer is null.
     * [durationSec] (0 when unknown) helps tell the music video from clips and live takes.
     */
    suspend fun search(
        title: String,
        artist: String,
        album: String?,
        trackVideoId: String?,
        resolutionLabel: String = "480p",
        durationSec: Long = 0L,
    ): CanvasArtwork? = withContext(Dispatchers.IO) {
        if (trackVideoId.isNullOrBlank()) return@withContext null
        if (!configured) configure(null)
        val resolution = OpenCanvasResolution.fromLabel(resolutionLabel)
        val began = System.currentTimeMillis()

        val track = withTimeoutOrNull(LOOKUP_TIMEOUT_MS) {
            val tracks = OpenCanvas.resolveFlow(
                title = title,
                artist = artist,
                durationSec = durationSec,
                mode = OpenCanvasMode.FULL_SYNCED_VIDEO,
                resolution = resolution,
                trackVideoId = trackVideoId,
            )
            var latest: OpenCanvasTrack? = null
            // Until the map is known (or known not to exist) ...
            tracks.takeWhile { latest = it; it.syncStage == SyncStage.PENDING }.collect { }
            if (latest?.syncStage != SyncStage.MEASURED) return@withTimeoutOrNull null
            // ... then a short grace for a sharper stream that is still being resolved.
            withTimeoutOrNull(UPGRADE_GRACE_MS) { tracks.collect { latest = it } }
            latest
        } ?: return@withContext null

        if (track.syncStage != SyncStage.MEASURED) return@withContext null
        DebugLog.d(TAG, "search for '$title' done in ${System.currentTimeMillis() - began} ms")

        CanvasArtwork(
            url = track.videoStreamUrl,
            title = title,
            artist = artist,
            album = album,
            source = CanvasSource.OTHER,
            syncMap = track.syncMap(),
            videoDurationMs = track.videoDurationMs,
            headers = track.videoHeaders,
        )
    }
}
