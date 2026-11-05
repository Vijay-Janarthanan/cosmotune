package com.cosmotune.music.data.sources

import com.cosmotune.music.data.YtMusicRepository
import com.cosmotune.music.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** Fill absent album/runtime from the exact played ID, not a title search's first result. */
object RecordingMetadata {
    private data class Entry(val song: Song?, val expiresAt: Long)
    private val cache = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > 256
    }

    suspend fun enrich(target: TrackMatcher.Target): TrackMatcher.Target =
        enrich(target) { YtMusicRepository.trackLinks(it).getOrNull() }

    internal suspend fun enrich(target: TrackMatcher.Target, lookup: suspend (String) -> Song?): TrackMatcher.Target {
        if (!target.album.isNullOrBlank() && target.durationSec != null) return target
        val id = target.videoId?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) } ?: return target
        val saved = synchronized(cache) { cache[id]?.takeIf { it.expiresAt > System.currentTimeMillis() } }
        val metadata = if (saved != null) saved.song else {
            val fetched = try { withTimeoutOrNull(2_500) { lookup(id) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            synchronized(cache) { cache[id] = Entry(fetched, System.currentTimeMillis() + if (fetched == null) 30_000 else 600_000) }
            fetched
        } ?: return target
        return merge(target, metadata)
    }

    /** A different queue row must never donate its album, even when its name looks similar. */
    internal fun merge(target: TrackMatcher.Target, metadata: Song): TrackMatcher.Target {
        if (metadata.videoId != target.videoId) return target
        if (TrackMatcher.parseTitle(target.title, target.artist).core !=
            TrackMatcher.parseTitle(metadata.title, metadata.artist).core) return target
        return target.copy(
            album = target.album?.takeIf { it.isNotBlank() } ?: metadata.albumName,
            durationSec = target.durationSec ?: TrackMatcher.secondsOf(metadata.durationText),
        )
    }
}
