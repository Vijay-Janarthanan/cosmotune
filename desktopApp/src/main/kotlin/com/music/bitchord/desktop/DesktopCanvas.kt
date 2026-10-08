package com.music.bitchord.desktop

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * A looping video that stands in for a track's cover art — what Spotify calls a Canvas and Apple
 * calls motion artwork.
 *
 * [url] is what the player mounts; [fallbackUrl] is tried once if that errors, which is how the
 * Apple provider hands over a second rendition of the same clip. The metadata is not decoration:
 * providers search by free text and will happily return the wrong album's clip, so [matches]
 * re-checks the answer against what is playing.
 */
data class DesktopCanvasArtwork(
    val url: String,
    val fallbackUrl: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val source: DesktopCanvasSource = DesktopCanvasSource.OTHER,
    /** Set for a music video that follows the song: how its timeline maps onto the video's. */
    val syncMap: com.opencanvas.core.sync.SyncMap? = null,
    val videoDurationMs: Long = 0L,
    /** HTTP headers every request for [url] must carry. */
    val headers: Map<String, String> = emptyMap(),
) {
    /**
     * Whether this clip really belongs to the track we asked about.
     *
     * Title and artists must match exactly once punctuation, case and accents are stripped — a near
     * miss is a different song by the same artist, which is the failure people notice. The album is
     * only held to that standard when both sides know it, since a track's album resolves after the
     * player opens and one queued from search may never get one.
     */
    fun matches(wantTitle: String, wantArtist: String, wantAlbum: String?): Boolean {
        val titleOk = title == null || wantTitle.isBlank() ||
            title.normalizeForCanvasMatch() == wantTitle.normalizeForCanvasMatch()
        val wanted = splitCanvasArtists(wantArtist)
        val ours = splitCanvasArtists(artist.orEmpty())
        val artistOk = artist == null || wantArtist.isBlank() ||
            (wanted.isNotEmpty() && ours.isNotEmpty() && wanted.all { want -> ours.any { it == want } })
        val albumOk = album.isNullOrBlank() || wantAlbum.isNullOrBlank() ||
            album.normalizeForCanvasMatch() == wantAlbum.normalizeForCanvasMatch()
        return titleOk && artistOk && albumOk
    }
}

/**
 * The four providers, asked in turn until one answers with a clip that is really this track's.
 *
 * Apple and Tidal first because their clips are square and belong to the release; the community
 * index next because it is the only one covering back catalogue; Spotify last because it is the
 * one that needs a credential.
 */
object DesktopCanvasClient {

    private const val CACHE_SIZE = 64
    private const val ENTRY_TTL_MS = 3 * 60 * 60 * 1000L


    /**
     * A settled answer for one track or release.
     *
     * [withAlbum] records whether the album name was known when this was worked out. It is the one
     * thing that can turn a miss into a hit later: the album is what makes the catalogue searches
     * land, and on the player it resolves a beat after the track starts.
     */
    private class Entry(val artwork: DesktopCanvasArtwork?, val withAlbum: Boolean, val at: Long = System.currentTimeMillis()) {
        /** A stream URL is minted for a few hours; an older answer is looked up again rather than served dead. */
        val fresh: Boolean get() = System.currentTimeMillis() - at < ENTRY_TTL_MS

        /** A hit is a hit — the album could only have confirmed it. A miss stands too, unless it
         * was reached blind and there is now an album name to try. */
        fun reusable(nowWithAlbum: Boolean): Boolean =
            artwork != null || withAlbum || !nowWithAlbum
    }

    private val cache = object : LinkedHashMap<String, Entry>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) = size > CACHE_SIZE
    }

    // Skipping through a queue fires a lookup per track. Serialising them keeps four providers'
    // worth of requests off the wire at once.
    private val gate = Mutex()

    /**
     * The canvas for [song], or null when there is not one. Never throws.
     *
     * Only a local file with no catalogue identity is answered as a miss without a request. A
     * download carries both a videoId and a local path, and skipping on the path alone is what
     * left downloaded tracks with no canvas.
     */
    suspend fun lookup(song: Song, provisional: ((DesktopCanvasArtwork) -> Unit)? = null): DesktopCanvasArtwork? {
        if (song.videoId.isBlank() && (song.localUri != null || song.localPath != null)) return null
        val title = song.title.cleanedForCanvas()
        val artist = song.artist.cleanedForCanvas()
        if (title.isBlank() || artist.isBlank()) return null
        val album = song.albumName
        // Keyed on the track alone: the album arrives after the player opens, and keying on it made
        // the late arrival look like a different question.
        val openCanvasActive = DesktopAppearanceSettings.openCanvasEnabled.value
        val openCanvasRes = DesktopAppearanceSettings.openCanvasResolution.value
        val seconds = song.durationMillis() / 1_000L
        val key = "song|${song.videoId}"
        // An answer that already stands needs neither the lock nor any lookup.
        synchronized(cache) { cache[key]?.takeIf { it.fresh && it.reusable(album != null) } }?.let { return it.artwork }

        return coroutineScope {
            // The music video is looked up from the moment the question is asked, not when the label
            // sources get their turn: it is the last resort, so its answer should already be there when
            // they come up empty, and a lookup queued behind another one must not hold it back.
            val musicVideo = if (openCanvasActive) async(Dispatchers.IO) {
                DesktopOpenCanvasProvider.search(title, artist, album, trackVideoId = song.videoId, resolutionLabel = openCanvasRes, durationSec = seconds)
            } else null

            val answer = resolve(key, album != null) {
                // A music video that lines up with the song beats a label's short loop, so it is asked
                // first; the label sources are only the fallback for songs without one.
                val video = musicVideo?.let { pending ->
                    runCatching { pending.await() }.getOrNull()?.takeIf { it.matches(title, artist, album) }
                }
                video ?: firstHit(
                    { DesktopAppleMusicCanvas.search(title, artist, album) },
                    { DesktopTidalCanvas.search(title, artist, album) },
                    { DesktopCommunityCanvas.search(title, artist, album) },
                    { DesktopSpotifyCanvas.search(title, artist, album) },
                ) { it.matches(title, artist, album) }
            }
            musicVideo?.cancel()
            answer
        }
    }

    /** A canvas already worked out for [song], without going near the network. */
    fun cached(song: Song): DesktopCanvasArtwork? =
        synchronized(cache) { cache["song|${song.videoId}"]?.takeIf { it.fresh }?.artwork }

    /**
     * The canvas for a release, for an album page's header.
     *
     * A separate lookup rather than the first track's: the services hang motion artwork off the
     * album, so asking directly is both fewer requests and a better match.
     */
    suspend fun lookupAlbum(album: String, artist: String): DesktopCanvasArtwork? {
        val name = album.cleanedForCanvas()
        val credit = artist.cleanedForCanvas()
        if (name.isBlank() || credit.isBlank()) return null
        return resolve("album|$name|$credit", withAlbum = true) {
            firstHit(
                { DesktopAppleMusicCanvas.searchAlbum(name, credit) },
                { DesktopTidalCanvas.searchAlbum(name, credit) },
                { DesktopCommunityCanvas.searchAlbum(name, credit) },
                { DesktopSpotifyCanvas.searchAlbum(name, credit) },
            ) { it.matches(name, credit, name) }
        }
    }

    private suspend fun resolve(
        key: String,
        withAlbum: Boolean,
        lookUp: suspend () -> DesktopCanvasArtwork?,
    ): DesktopCanvasArtwork? = gate.withLock {
        synchronized(cache) {
            cache[key]?.let { if (it.fresh && it.reusable(withAlbum)) return@withLock it.artwork }
        }
        val found = withContext(Dispatchers.IO) { lookUp() }
        synchronized(cache) { cache[key] = Entry(found, withAlbum) }
        found
    }

    /**
     * The first source answering with something that survives [accept].
     *
     * Sources are passed unevaluated so each is only reached if the ones before came up empty. One
     * that throws is treated as one that found nothing: none of these hosts are ours.
     */
    private suspend fun firstHit(
        vararg sources: suspend () -> DesktopCanvasArtwork?,
        accept: (DesktopCanvasArtwork) -> Boolean,
    ): DesktopCanvasArtwork? {
        for (source in sources) {
            // A lookup that has been given up on stops here rather than working through the rest of
            // the sources while holding everyone else up.
            currentCoroutineContext().ensureActive()
            val found = try {
                source()
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                null
            } ?: continue
            if (!accept(found)) continue
            return found
        }
        return null
    }
}

/**
 * YouTube Music titles carry packaging the catalogue services never see. Searching with it finds
 * nothing and matching against it rejects everything, so it comes off before either.
 */
internal fun String.cleanedForCanvas(): String = replace(CANVAS_NOISE, " ")
    .substringBefore(" | ")
    .replace(Regex("\\s+"), " ")
    .trim()
    .ifBlank { this }

private val CANVAS_NOISE = Regex(
    """\((?:from|official|lyrical|video|audio)[^)]*\)|\[[^]]*]|""" +
        """\b(?:official (?:video|audio|music video)|lyrical|full song|4k video)\b""",
    RegexOption.IGNORE_CASE,
)

/** Whether this URL points at a playlist rather than at the media itself. */
internal fun isManifest(url: String): Boolean {
    val path = url.substringBefore('?').substringBefore('#').lowercase()
    return path.endsWith(".m3u8") || path.endsWith(".mpd")
}

/** The clip itself, on disk. */
internal object DesktopCanvasCache {

    // Ranged requests only: a stream URL fetched with one plain GET is throttled to roughly the
    // playback rate (a 720p clip took about three minutes), where ranges run at line speed.
    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val requestHeaders = mapOf("User-Agent" to DESKTOP_CANVAS_UA, "Accept" to "*/*")

    /** The clip as a file, if it has been kept from an earlier play. */
    fun existing(url: String): java.nio.file.Path? = DesktopMediaCache.existing(url, "mp4")

    /** Whether [url] is something to fetch over the network rather than a path on disk. */
    fun isRemote(url: String): Boolean = url.startsWith("http://") || url.startsWith("https://")

    /**
     * A reader that fetches [url] progressively: a small first range so decoding starts at once,
     * larger ones as playback continues, and a small range at the target after any seek.
     */
    fun progressiveSource(url: String, headers: Map<String, String> = emptyMap()) =
        com.opencanvas.core.stream.ProgressiveRangeSource(url, client, requestHeaders + headers)

    /**
     * Keeps what [source] fetches once it has all of it, so the next play (and every loop) reads a
     * file. The fetching itself already happened while the clip was playing; this only waits.
     */
    fun persistInBackground(url: String, source: com.opencanvas.core.stream.ProgressiveRangeSource) {
        if (existing(url) != null) return
        Thread({
            runCatching {
                if (source.awaitComplete(PERSIST_WAIT_MS)) {
                    source.writeCompleteTo(DesktopMediaCache.pathFor(url, "mp4"))
                    // The only moment the cache can be over its limit is just after a write.
                    DesktopMediaCache.trim()
                }
            }
        }, "canvas-cache-writer").apply { isDaemon = true }.start()
    }

    /** What opening a clip produced: the result, the network reader if one is in use, and how it was opened. */
    class Opened(
        val result: Result<Unit>,
        val streaming: com.opencanvas.core.stream.ProgressiveRangeSource?,
        val how: String,
    )

    /**
     * Opens the first of [candidates] that [decoder] can play, preferring whatever starts fastest: a
     * manifest as is, a clip already on disk, and otherwise a progressive stream - decoding begins on
     * the first range instead of after the whole download, and the rest is kept for next time.
     */
    fun open(decoder: DesktopCanvasDecoder, candidates: List<String>, headers: Map<String, String> = emptyMap()): Opened {
        var last: Result<Unit> = Result.failure(IllegalStateException("the clip could not be fetched"))
        var streaming: com.opencanvas.core.stream.ProgressiveRangeSource? = null
        var how = "file"
        for (candidate in candidates) {
            val cached = existing(candidate)
            last = when {
                // A manifest names its segments relative to the host it came from, so saving the
                // playlist to disk and opening that leaves FFmpeg with nothing it can resolve.
                // Apple's motion artwork is HLS, which is how "could not open the clip" happened.
                isManifest(candidate) -> { how = "manifest"; decoder.open(candidate) }
                cached != null -> { how = "cache"; decoder.open(cached.toAbsolutePath().toString()) }
                isRemote(candidate) -> {
                    how = "stream"
                    val source = progressiveSource(candidate, headers)
                    decoder.open(source).also { result ->
                        if (result.isSuccess) {
                            streaming = source
                            persistInBackground(candidate, source)
                        } else {
                            source.close()
                        }
                    }
                }
                else -> fileFor(candidate)?.let {
                    how = "file"
                    decoder.open(it.toAbsolutePath().toString())
                } ?: continue
            }
            if (last.isSuccess) break
        }
        return Opened(last, streaming, how)
    }

    /** The clip as a file, fetched in ranges; blocks until it is all there. */
    fun fileFor(url: String): java.nio.file.Path? {
        existing(url)?.let { return it }
        return runCatching {
            val target = DesktopMediaCache.pathFor(url, "mp4")
            progressiveSource(url).use { source ->
                check(source.awaitComplete(PERSIST_WAIT_MS)) { "canvas download did not finish" }
                check(source.writeCompleteTo(target)) { "canvas came back incomplete" }
            }
            DesktopMediaCache.trim()
            target
        }.onFailure { DesktopTrackLog.log("canvas: could not fetch the clip — ${it.message}") }.getOrNull()
    }

    private const val PERSIST_WAIT_MS = 10 * 60 * 1000L
}

/** The motion artwork, drawn as frames rather than played by a native child. */
@Composable
fun DesktopCanvasView(
    url: String,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = true,
    contentScale: ContentScale = ContentScale.Crop,
    /** Tried once if [url] will not decode — the Apple provider's second rendition of the clip. */
    fallbackUrl: String? = null,
) {
    var frame by remember(url) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(url, fallbackUrl, isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        // Round and round until the track ends or the player is closed. A canvas is a few seconds
        // long, so one pass through it is not the feature — and the decoder cannot be relied on to
        // rewind itself: its own seek does not take on an HLS manifest, which is what Apple serves,
        // and the clip then stopped dead on its last frame for the rest of the song.
        while (isActive) {
            val decoder = DesktopCanvasDecoder()
            val startedAt = System.nanoTime()
            val attempt = withContext(Dispatchers.IO) {
                DesktopCanvasCache.open(decoder, listOfNotNull(url, fallbackUrl))
            }
            val opened = attempt.result
            // Set when the clip is read through the network rather than from a file, so it can be
            // released with the decoder.
            val streaming = attempt.streaming
            val how = attempt.how
            if (opened.isFailure) {
                DesktopTrackLog.log("canvas: ${opened.exceptionOrNull()?.message}")
                withContext(Dispatchers.IO) { decoder.close() }
                streaming?.close()
                return@LaunchedEffect
            }
            var shown = 0
            try {
                val pixels = ByteArray(decoder.width * decoder.height * 4)
                val info = ImageInfo.makeN32(decoder.width, decoder.height, ColorAlphaType.OPAQUE)
                while (isActive) {
                    val started = System.currentTimeMillis()
                    val decoded = withContext(Dispatchers.IO) { decoder.nextFrame(pixels) }
                    if (!decoded) break
                    shown++
                    // `pixels` is handed to Skia rather than copied into it, so the array cannot be
                    // the one the decoder writes the next frame into.
                    val next = Image.makeRaster(info, pixels.copyOf(), decoder.width * 4).toComposeImageBitmap()
                    frame = next
                    // The backdrop reads the first frame and holds it: re-meshing every frame would
                    // be a full resample twenty-five times a second for a wash nobody is watching
                    // closely, and the clip's palette does not change much across it anyway.
                    if (shown == 1) {
                        DesktopCanvasBackdrop.publish(url, next)
                        val millis = (System.nanoTime() - startedAt) / 1_000_000
                        DesktopTrackLog.log("canvas: first frame in $millis ms ($how)")
                    }
                    val spent = System.currentTimeMillis() - started
                    delay((decoder.frameIntervalMillis - spent).coerceAtLeast(0L))
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    decoder.close()
                    // Only after the decoder: it may still be inside a read callback until it closes.
                    streaming?.close()
                }
            }
            // A pass that drew nothing would spin: reopening cannot fix a clip that has no frames
            // in it, and retrying immediately is a busy loop over the network.
            if (shown == 0) {
                DesktopTrackLog.log("canvas: the clip decoded no frames; not looping it")
                return@LaunchedEffect
            }
        }
    }

    DisposableEffect(url) {
        onDispose { DesktopCanvasBackdrop.clear(url) }
    }

    // Nothing at all until the first frame: a placeholder here would flash between the still cover
    // and the clip on every track change.
    frame?.let {
        Image(
            bitmap = it,
            contentDescription = null,
            contentScale = contentScale,
            modifier = modifier,
        )
    }
}

private const val DESKTOP_CANVAS_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
