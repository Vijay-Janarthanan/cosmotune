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

    /**
     * Upper bound for the whole lookup: the searches' own budget (about 15 s on a slow line), the measuring of the
     * candidates and the stream. A lookup cut short here is abandoned with its search, so it must not be shorter.
     */
    private const val LOOKUP_TIMEOUT_MS = 30_000L

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
        CanvasVideoPicks.keepIn(cacheDirectory)
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
        val picked = CanvasVideoPicks.pick(title, artist)
        if (picked == CanvasVideoPicks.NO_VIDEO) return
        OpenCanvas.prefetch(
            title = title,
            artist = artist,
            durationSec = durationSec,
            mode = OpenCanvasMode.FULL_SYNCED_VIDEO,
            resolution = OpenCanvasResolution.fromLabel(resolutionLabel),
            trackVideoId = trackVideoId,
            videoId = picked,
            album = album,
        )
    }

    /**
     * Searches for a synced music video for [title] and [artist].
     *
     * [trackVideoId] is the YouTube (Music) id of the song being played: the map is measured between
     * that audio and the video's, so without it nothing can be aligned and the answer is null.
     * [durationSec] (0 when unknown) helps tell the music video from clips and live takes.
     * [background] marks a lookup for a song that is not playing yet (the next in the queue): it waits while the
     * playing song is searching, and is promoted when the same song is asked for without it.
     */
    suspend fun search(
        title: String,
        artist: String,
        album: String?,
        trackVideoId: String?,
        resolutionLabel: String = "480p",
        durationSec: Long = 0L,
        background: Boolean = false,
    ): CanvasArtwork? = withContext(Dispatchers.IO) {
        if (trackVideoId.isNullOrBlank()) return@withContext null
        if (!configured) configure(null)
        // A video picked by hand for this song is the one measured (the library then searches for nothing); "No video" is none.
        val picked = CanvasVideoPicks.pick(title, artist)
        if (picked == CanvasVideoPicks.NO_VIDEO) return@withContext null
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
            videoId = picked,
            album = query.album,
            background = background,
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

    /**
     * The music videos found for a song, best first, for "Choose the video": the search the automatic lookup makes, without
     * measuring any of them. Empty when nothing is found or the search gets no answer.
     */
    suspend fun videoChoices(title: String, artist: String, album: String?, durationSec: Long = 0L): List<VideoChoice> = withContext(Dispatchers.IO) {
        if (!configured) configure(null)
        runCatching { OpenCanvas.videoChoices(title, artist, durationSec, album) }.getOrDefault(emptyList()).map {
            VideoChoice(it.videoId, it.title, it.channelTitle, it.durationSec)
        }
    }
}

/** One video found for a song: its id, title, channel and length (0 when the search did not say). */
data class VideoChoice(val videoId: String, val title: String, val channel: String, val durationSec: Long) {
    /** The video's small 16:9 still. */
    val thumbnailUrl: String get() = "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"
}

/**
 * The videos picked by hand for songs ("Choose the video" on Now Playing), kept for each song (by its name and artists, so
 * another copy or stream of the same song keeps the pick): a video id, or [NO_VIDEO] for the song with its cover. A song
 * without a pick is looked up as usual. Kept in a small file beside OpenCanvas's maps, or only in memory without a folder.
 * [revision] moves on every change, so a player showing the song looks it up again.
 */
object CanvasVideoPicks {
    /** The pick that means "play the song with its cover". */
    const val NO_VIDEO = "-"

    private val lock = Any()
    private val picks = java.util.Properties()
    @Volatile private var file: File? = null
    private val revisionFlow = kotlinx.coroutines.flow.MutableStateFlow(0)
    val revision: kotlinx.coroutines.flow.StateFlow<Int> = revisionFlow

    /** Reads the picks kept in [directory] (once, at configure), and keeps new ones there. */
    fun keepIn(directory: File?) {
        val kept = directory?.let { File(it, FILE_NAME) }
        synchronized(lock) {
            file = kept
            if (kept != null && kept.isFile) runCatching { kept.inputStream().use { picks.load(it) } }
        }
    }

    /** The pick for the song, or null when it is looked up as usual. */
    fun pick(title: String, artist: String): String? {
        val key = key(title, artist) ?: return null
        return synchronized(lock) { picks.getProperty(key) }
    }

    /** Keeps [videoId] (a video id, [NO_VIDEO], or null for "look it up as usual") as the song's pick. */
    fun set(title: String, artist: String, videoId: String?) {
        val key = key(title, artist) ?: return
        synchronized(lock) {
            if (videoId == null) picks.remove(key) else picks.setProperty(key, videoId)
            file?.let { target ->
                runCatching {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { picks.store(it, "Videos picked by hand, per song") }
                }.onFailure { DebugLog.d("OpenCanvas", "could not keep the video pick: ${it.message}") }
            }
        }
        revisionFlow.value = revisionFlow.value + 1
    }

    /** The song's name and artists, cleaned as the lookup cleans them; its length is left out (services round it differently). */
    internal fun key(title: String, artist: String): String? =
        com.opencanvas.core.stream.SongIdentity.of(MusicVideoQuery.from(title, null).title, artist, 0L)?.key

    private const val FILE_NAME = "video-picks.properties"
}
