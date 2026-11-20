package com.cosmotune.cache

import com.cosmotune.music.data.model.MoodGenre
import com.cosmotune.music.data.model.MoodGenreSection
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * The covers of Explore's mood and genre tiles, kept on the disk ([PageCache]'s folder) between runs. A tile carries no art of
 * its own: its cover is the first one among the playlists it opens, which costs a whole browse request per tile (52 to 125 of
 * them). So each cover is asked for once and kept, a tile that has none is remembered as having none, and the whole set is asked
 * again in the background at most once a day ([REFRESH_MS]); in between, only a tile never asked (a new category, or one whose
 * request failed) is asked. Only the address is kept, never the shelves.
 */
class ExploreCoverStore(
    private val cache: () -> PageCache = { PageCache.shared },
    private val clock: () -> Long = System::currentTimeMillis,
    private val parallel: Int = 4,
) {
    /** What is kept: when every tile was last asked ([refreshedAtMs]), and each tile's cover by [key] (null: it has none). */
    @Serializable
    data class Saved(val refreshedAtMs: Long = 0L, val covers: Map<String, String?> = emptyMap())

    private val lock = Mutex()
    private val running = Mutex()
    @Volatile private var memory: Saved? = null

    /** The saved covers (read from the disk the first time). */
    suspend fun saved(): Saved = lock.withLock {
        memory ?: (cache().readSaved(KEY, CODEC)?.second ?: Saved()).also { memory = it }
    }

    /** [sections] with their saved covers in: a tile that came with art of its own keeps it. */
    suspend fun apply(sections: List<MoodGenreSection>): List<MoodGenreSection> = withCovers(sections, saved().covers)

    /** The tiles to ask for now: every tile once a day has passed since the last full pass, else only the tiles never asked. */
    fun due(sections: List<MoodGenreSection>, saved: Saved, now: Long = clock()): List<MoodGenre> {
        val tiles = sections.flatMap(MoodGenreSection::items).filter { it.thumbnailUrl == null || saved.covers[key(it)] != null }
            .distinctBy(::key)
        return if (now - saved.refreshedAtMs >= REFRESH_MS) tiles else tiles.filter { key(it) !in saved.covers }
    }

    /**
     * Asks [fetch] for the covers that are [due], [parallel] at a time, and hands each one found to [onCover] as it arrives. Each
     * answer is kept (a failed request is not, so that tile is asked again next time); a pass over every tile marks the day. What
     * was learnt is saved even when the pass is cancelled. One pass at a time: a second call waits for the first and then finds
     * nothing due.
     */
    suspend fun resolve(
        sections: List<MoodGenreSection>,
        fetch: suspend (MoodGenre) -> Result<String?>,
        onCover: (MoodGenre, String) -> Unit,
    ) = running.withLock {
        val start = clock()
        val before = saved()
        val due = due(sections, before, start)
        if (due.isEmpty()) return@withLock
        val full = start - before.refreshedAtMs >= REFRESH_MS
        val learnt = java.util.concurrent.ConcurrentHashMap<String, Optional>()
        val limiter = Semaphore(parallel)
        var finished = false
        try {
            coroutineScope {
                due.map { tile ->
                    async {
                        val answer = limiter.withPermit { runCatching { fetch(tile).getOrThrow() } }
                        answer.onSuccess { url ->
                            learnt[key(tile)] = Optional(url)
                            if (url != null) onCover(tile, url)
                        }
                    }
                }.awaitAll()
            }
            finished = true
        } finally {
            val found = learnt.mapValues { it.value.url }
            // A full pass that ran to its end marks the day, even when a few tiles failed: those keep their old cover until
            // tomorrow, and a tile that never had an answer is asked again at the next start.
            val markDay = full && finished
            if (found.isNotEmpty() || markDay) {
                val next = withContext(NonCancellable) {
                    lock.withLock {
                        val current = memory ?: before
                        Saved(if (markDay) start else current.refreshedAtMs, current.covers + found).also { memory = it }
                    }
                }
                cache().writeSaved(KEY, next, CODEC)
            }
        }
    }

    /** Forgets what is kept in memory, so the next read is from the disk (tests). */
    internal fun forgetMemory() { memory = null }

    private class Optional(val url: String?)

    companion object {
        /** At most this often is every cover asked again. */
        const val REFRESH_MS: Long = 24 * 60 * 60 * 1000L
        private const val KEY = "music:explore:covers"
        private val CODEC = CacheCodec.json(Saved.serializer())

        /** One tile's key: the exact browse request it opens. */
        fun key(tile: MoodGenre): String = tile.browseId + ":" + tile.params.orEmpty()

        /** [sections] with [covers] filled in where a tile has no art of its own. */
        fun withCovers(sections: List<MoodGenreSection>, covers: Map<String, String?>): List<MoodGenreSection> =
            if (covers.isEmpty()) sections else sections.map { section ->
                section.copy(items = section.items.map { tile ->
                    if (tile.thumbnailUrl != null) tile else covers[key(tile)]?.let { tile.copy(thumbnailUrl = it) } ?: tile
                })
            }

        /** [sections] with [tile]'s cover set to [url] (every tile that opens the same request). */
        fun withCover(sections: List<MoodGenreSection>, tile: MoodGenre, url: String): List<MoodGenreSection> {
            val k = key(tile)
            return sections.map { section ->
                section.copy(items = section.items.map { if (key(it) == k) it.copy(thumbnailUrl = url) else it })
            }
        }
    }
}

/** The app's Explore covers, for the process. */
val ExploreCovers: ExploreCoverStore = ExploreCoverStore()
