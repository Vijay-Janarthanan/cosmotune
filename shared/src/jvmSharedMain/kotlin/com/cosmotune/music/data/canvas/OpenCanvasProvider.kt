package com.cosmotune.music.data.canvas

import com.cosmotune.music.data.DebugLog
import com.opencanvas.core.OpenCanvas
import com.opencanvas.core.OpenCanvasConfig
import com.opencanvas.core.models.OpenCanvasMode
import com.opencanvas.core.models.OpenCanvasResolution
import com.cosmotune.canvas.resolvedCanvas
import com.opencanvas.core.models.SyncStage
import com.opencanvas.core.stream.MusicVideoQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Dynamic OpenCanvas provider for Cosmotune.
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

    /** Upper bound for the whole lookup. */
    private const val LOOKUP_TIMEOUT_MS = 20_000L

    @Volatile
    private var configured = false

    /**
     * Prepares the engine. [cacheDirectory] is where measured maps are kept so a song starts instantly
     * the next time; null keeps them in memory. Called once at startup; a lookup that comes before it
     * configures the engine with its defaults. Everything else is the library's own: it resolves the
     * streams (a quick preview, then the requested resolution) and measures the map without help from the app.
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
        album: String? = null,
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
            album = album,
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
        val query = MusicVideoQuery.from(title, album)
        val resolution = OpenCanvasResolution.fromLabel(resolutionLabel)
        val began = System.currentTimeMillis()

        val track = OpenCanvas.resolveFlow(
            title = query.title,
            artist = artist,
            durationSec = durationSec,
            mode = OpenCanvasMode.FULL_SYNCED_VIDEO,
            resolution = resolution,
            trackVideoId = trackVideoId,
            album = query.album,
        ).resolvedCanvas(LOOKUP_TIMEOUT_MS) ?: return@withContext null

        if (track.syncStage != SyncStage.MEASURED) return@withContext null
        DebugLog.d(TAG, "search for '$title' done in ${System.currentTimeMillis() - began} ms")

        CanvasArtwork(
            url = track.videoStreamUrl,
            title = query.title,
            artist = artist,
            album = album,
            source = CanvasSource.OTHER,
            syncMap = ForwardOnlySyncMap.of(track.syncMap()),
            videoDurationMs = track.videoDurationMs,
            headers = track.videoHeaders,
            videoId = track.videoId,
            trajectory = track.trajectory,
        )
    }
}
