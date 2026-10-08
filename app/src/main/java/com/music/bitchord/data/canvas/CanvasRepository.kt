package com.music.bitchord.data.canvas

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.model.durationMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Finds the looping video that belongs behind a track's or a release's cover
 * art — Spotify's Canvas, Apple's motion artwork.
 *
 * Four sources, asked in turn until one answers. They cover genuinely
 * different ground rather than being four routes to the same catalogue:
 * Apple has the most, Tidal has square covers on a lot of what Apple misses,
 * the community index is the only one that reaches back catalogue, and
 * Spotify has the original Canvas but needs the listener's own session
 * cookie to reach (see [SpotifyCanvas]) and is a free no-op without one.
 * Spotify goes last by default even once it's set up: it's the heaviest of the
 * four to reach (an offscreen WebView, not just a request) and the other three
 * between them already cover most of what it would have answered. The Spotify
 * integration setting can deliberately put it first when its original Canvas
 * is more important to the listener than that lookup cost.
 *
 * Every one of them is a public endpoint belonging to someone else, reached
 * without an account, and all of them will confidently answer a search with
 * the wrong record. So the shape of this is: ask, then re-check the answer
 * against what was asked for ([CanvasArtwork.matches]), and treat any failure
 * — network, parse, mismatch — as simply no canvas. The still art is always
 * underneath, so nothing here can break the player or the album page.
 *
 * Results are cached, misses included: nothing having a canvas is the common
 * case, and without negative caching every revisit would pay for three
 * lookups again to learn the same thing.
 */
object CanvasRepository {

    private const val TAG = "CanvasRepository"
    private const val CACHE_SIZE = 64
    private const val ENTRY_TTL_MS = 3 * 60 * 60 * 1000L


    /**
     * A settled answer for one track or release.
     *
     * [withAlbum] records whether the album name was known when this was
     * worked out. It is the one thing that can turn a miss into a hit later:
     * the album is what makes the catalogue searches land, and on the player
     * it resolves a beat after the track starts. A miss reached without it is
     * therefore provisional; everything else is final.
     */
    private class Entry(val artwork: CanvasArtwork?, val withAlbum: Boolean, val at: Long = System.currentTimeMillis()) {
        /** A stream URL is minted for a few hours; an older answer is looked up again rather than served dead. */
        val fresh: Boolean get() = System.currentTimeMillis() - at < ENTRY_TTL_MS
    }

    private val cache = object : LinkedHashMap<String, Entry>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) =
            size > CACHE_SIZE
    }

    // Skipping through a queue fires a lookup per track. Serialising them
    // keeps three providers' worth of requests off the wire at once, and means
    // a track that was already resolved by the time its turn comes up is
    // answered from the cache instead of fetched again.
    private val lock = Mutex()

    /**
     * The canvas for [song], or null when there isn't one. Never throws.
     *
     * A local file with no catalogue identity (no [Song.videoId]) is answered
     * as a miss without a request — there is nothing to search on.  Downloaded
     * tracks carry a videoId and full metadata, so they get the same canvas
     * lookup as streaming ones; network guards live in the caller
     * ([NowPlayingScreen], which checks [AppSettings.canvasOverCellular]).
     */
    suspend fun canvasFor(song: Song, provisional: ((CanvasArtwork) -> Unit)? = null): CanvasArtwork? {
        // Only skip when there is no catalogue identity to search on.
        if (song.videoId.isBlank() && (song.localUri != null || song.localPath != null)) return null

        val title = song.title.cleaned()
        val artist = song.artist.cleaned()
        if (title.isBlank() || artist.isBlank()) return null

        // Keyed on the track alone. The album is deliberately not part of
        // this: it arrives after the player opens, and keying on it made the
        // late arrival look like a different question and run the whole chain
        // a second time. [reusable] decides when the earlier answer still
        // stands instead.
        val album = song.albumName
        val spotifyFirst = AppSettings.prioritizeSpotifyCanvas.value
        val key = cacheKey("song|${song.videoId}", spotifyFirst)

        val openCanvasActive = AppSettings.openCanvasEnabled.value
        val openCanvasRes = AppSettings.openCanvasResolution.value

        val seconds = song.durationMillis() / 1_000L

        // An answer that already stands needs neither the lock nor any lookup.
        settled(key, album != null)?.let { return it.artwork }

        return coroutineScope {
            // The music video is looked up from the moment the question is asked, not when the label
            // sources get their turn: it is the last resort, so its answer should already be there when
            // they come up empty, and a lookup queued behind another one must not hold it back.
            val musicVideo = if (openCanvasActive) async(Dispatchers.IO) {
                OpenCanvasProvider.search(title, artist, album, trackVideoId = song.videoId, resolutionLabel = openCanvasRes, durationSec = seconds)
            } else null

            val answer = resolve(key, album != null) {
                Log.d(TAG, "lookup start '$title' (album=${album != null})")
                // A music video that lines up with the song beats a label's short loop, so it is asked
                // first; the label sources are only the fallback for songs without one.
                val video = musicVideo?.let { pending ->
                    runCatching { pending.await() }
                        .onFailure { Log.d(TAG, "music video lookup failed: ${it.message}") }
                        .getOrNull()
                        ?.takeIf { it.matches(title, artist, album) }
                }
                video ?: if (spotifyFirst) {
                    firstHit(
                        { SpotifyCanvas.search(title, artist, album) },
                        { AppleMusicCanvas.search(title, artist, album) },
                        { TidalCanvas.search(title, artist, album) },
                        { CommunityCanvas.search(title, artist, album) },
                    ) { it.matches(title, artist, album) }
                } else {
                    firstHit(
                        { AppleMusicCanvas.search(title, artist, album) },
                        { TidalCanvas.search(title, artist, album) },
                        { CommunityCanvas.search(title, artist, album) },
                        { SpotifyCanvas.search(title, artist, album) },
                    ) { it.matches(title, artist, album) }
                }
            }
            musicVideo?.cancel()
            answer
        }
    }

    /** The entry for [key] if it is fresh and still answers the question as asked now. */
    private fun settled(key: String, withAlbum: Boolean): Entry? =
        synchronized(cache) { cache[key]?.takeIf { it.fresh && it.reusable(withAlbum) } }

    /**
     * Gets the music-video lookup going the moment [song] starts playing, rather than when its player
     * screen opens and the label sources have had their say: the video, its alignment and the sharper
     * stream are then usually ready by the time anyone looks. The lookup that follows asks the same
     * question and picks up the work in flight. Honours the same settings as the player does.
     */
    fun prefetch(song: Song) {
        if (!AppSettings.animatedCanvas.value || !AppSettings.openCanvasEnabled.value) return
        if (AppSettings.meteredConnection.value == true && !AppSettings.canvasOverCellular.value) return
        if (song.videoId.isBlank()) return
        val title = song.title.cleaned()
        val artist = song.artist.cleaned()
        if (title.isBlank() || artist.isBlank()) return
        OpenCanvasProvider.prefetch(
            title = title,
            artist = artist,
            trackVideoId = song.videoId,
            resolutionLabel = AppSettings.openCanvasResolution.value,
            durationSec = song.durationMillis() / 1_000L,
        )
    }

    /**
     * A canvas already worked out for [song], without going near the network.
     *
     * Lets a caller paint what it knows before it starts waiting on anything —
     * reopening the player on a track resolved a minute ago should not go
     * through the settling delay again to arrive back at the same clip.
     */
    fun cached(song: Song): CanvasArtwork? {
        val key = cacheKey(
            base = "song|${song.videoId}",
            spotifyFirst = AppSettings.prioritizeSpotifyCanvas.value,
        )
        return synchronized(cache) { cache[key]?.takeIf { it.fresh }?.artwork }
    }

    /**
     * The canvas for a release, for the album page's header artwork.
     *
     * A separate lookup rather than the first track's: the services hang
     * motion artwork off the album, so asking for it directly is both fewer
     * requests and a better match than picking a song and hoping it sits on
     * the right edition.
     */
    suspend fun canvasForAlbum(album: String, artist: String): CanvasArtwork? {
        val name = album.cleaned()
        val credit = artist.cleaned()
        if (name.isBlank() || credit.isBlank()) return null

        val spotifyFirst = AppSettings.prioritizeSpotifyCanvas.value
        return resolve(cacheKey("album|$name|$credit", spotifyFirst), withAlbum = true) {
            if (spotifyFirst) {
                firstHit(
                    { SpotifyCanvas.searchAlbum(name, credit) },
                    { AppleMusicCanvas.searchAlbum(name, credit) },
                    { TidalCanvas.searchAlbum(name, credit) },
                    { CommunityCanvas.searchAlbum(name, credit) },
                ) { it.matches(name, credit, name) }
            } else {
                firstHit(
                    { AppleMusicCanvas.searchAlbum(name, credit) },
                    { TidalCanvas.searchAlbum(name, credit) },
                    { CommunityCanvas.searchAlbum(name, credit) },
                    { SpotifyCanvas.searchAlbum(name, credit) },
                ) { it.matches(name, credit, name) }
            }
        }
    }

    /** Priority is part of the question, so a toggle never reuses the other order's answer. */
    private fun cacheKey(base: String, spotifyFirst: Boolean): String =
        "$base|spotifyFirst=$spotifyFirst"

    private suspend fun resolve(
        key: String,
        withAlbum: Boolean,
        lookUp: suspend () -> CanvasArtwork?,
    ): CanvasArtwork? = lock.withLock {
        synchronized(cache) {
            cache[key]?.let { if (it.fresh && it.reusable(withAlbum)) return@withLock it.artwork }
        }
        val found = withContext(Dispatchers.IO) { lookUp() }
        synchronized(cache) { cache[key] = Entry(found, withAlbum) }
        found
    }

    /**
     * Whether this answer can stand in for a lookup that now knows [withAlbum].
     *
     * A hit is a hit — the album could only have confirmed it. A miss stands
     * too, unless it was reached blind and there is now an album name to try,
     * which is the one case worth spending a second round of requests on.
     */
    private fun Entry.reusable(withAlbum: Boolean): Boolean =
        artwork != null || this.withAlbum || !withAlbum

    /**
     * The first source that answers with something that survives [accept].
     *
     * Sources are passed unevaluated so each is only reached — and only paid
     * for — if the ones before it came up empty. One that throws is treated as
     * one that found nothing: none of these hosts are ours, and a missing
     * canvas is not worth surfacing as an error.
     */
    private suspend fun firstHit(
        vararg sources: suspend () -> CanvasArtwork?,
        accept: (CanvasArtwork) -> Boolean,
    ): CanvasArtwork? {
        for (source in sources) {
            // A lookup that has been given up on (the album arrived and asked the question again) stops
            // here instead of working through the rest of the sources while holding everyone else up.
            currentCoroutineContext().ensureActive()
            val found = try {
                source()
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                Log.d(TAG, "source failed: ${failure.message}")
                null
            } ?: continue
            if (!accept(found)) {
                Log.d(TAG, "rejected '${found.title}' by '${found.artist}'")
                continue
            }
            return found
        }
        return null
    }

    /**
     * YouTube Music titles carry packaging the catalogue services never see —
     * "| Official Video", bracketed tags, "(Lyrical)". Searching with it finds
     * nothing and matching against it rejects everything, so it comes off
     * before either. Same treatment as the lyrics lookup gives it.
     */
    private fun String.cleaned(): String = replace(NOISE, " ")
        .substringBefore(" | ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .ifBlank { this }

    private val NOISE = Regex(
        """\((?:from|official|lyrical|video|audio)[^)]*\)|\[[^]]*]|""" +
            """\b(?:official (?:video|audio|music video)|lyrical|full song|4k video)\b""",
        RegexOption.IGNORE_CASE,
    )
}
