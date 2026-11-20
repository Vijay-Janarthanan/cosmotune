package com.cosmotune.music.data.lyrics

import com.cosmotune.cache.CacheDisk
import com.cosmotune.cache.FileCacheDisk
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One lyrics lookup per song, shared by everyone who wants it, and kept on the disk.
 *
 * The player screen and the playback service (the notification's lyric line) used to ask [LyricsRepository] for the same song
 * each on their own, so every song was fetched twice, and nothing was kept between plays. Here:
 *
 *  - A lookup already running for the same song and settings is joined, not started again; whoever joins late is told every
 *    provider's progress so far, so the player's provider drawer reads the same as if it had asked itself.
 *  - An answer is kept on the disk with the source it came from, for [FOUND_FOR_MS]; "no lyrics" for only [NONE_FOR_MS], and
 *    not at all when a provider failed (that was the network, not the song).
 *  - [prefetch] looks the next song up ahead, so its lyrics are there when it starts.
 *
 * The sources, their order and the timing rules are [LyricsRepository]'s, unchanged: this decides only whether to ask it.
 * One copy per song on the disk: a change of settings (another source order, syllable priority) asks again and replaces it.
 */
object LyricsStore {
    /** How long found lyrics are kept. */
    const val FOUND_FOR_MS: Long = 30L * 24 * 60 * 60 * 1000

    /** How long "no lyrics" is believed: briefly, as a provider may add them, or a miss may have been a bad moment. */
    const val NONE_FOR_MS: Long = 6L * 60 * 60 * 1000

    /** How far apart two durations may be and still be the same recording (the player's length against a listing's). */
    const val DURATION_SLACK_MS: Long = 3_000

    private const val MEMORY_ENTRIES = 64
    private const val VERSION = "v1"

    /** What is asked: the song and the settings, as [LyricsRepository.lyrics] takes them. */
    data class Request(
        val videoId: String,
        val title: String,
        val artist: String,
        val durationMs: Long,
        val album: String? = null,
        val sources: Set<LyricsSource> = LyricsSource.entries.toSet(),
        val order: List<LyricsSource> = LyricsSource.entries,
        val prioritizeSyllableSync: Boolean = false,
        val isrc: String? = null,
    ) {
        /** The sources that will really be asked, in order: what [LyricsRepository] does with [order] and [sources]. */
        internal val asked: List<LyricsSource> get() = (order + LyricsSource.offered).distinct().filter { it in sources && !it.hidden }

        /** Everything but the duration, which is matched with [DURATION_SLACK_MS] instead. */
        internal val key: String get() = listOf(VERSION, videoId, title, artist, album.orEmpty(), isrc.orEmpty(),
            asked.joinToString(",") { it.name }, prioritizeSyllableSync.toString()).joinToString("|")
    }

    /** A kept answer: [source] and [lines] are both null for "no lyrics". */
    @Serializable
    internal data class Saved(
        val key: String,
        val savedAtMs: Long,
        val durationMs: Long,
        val source: LyricsSource? = null,
        val lines: List<LyricLine>? = null,
    ) {
        val result: LyricsRepository.Result? get() = if (source != null && lines != null) LyricsRepository.Result(source, lines) else null
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Writes and removals one at a time, in the order asked: a removal never overtakes the write it undoes. */
    private val diskOrder = Dispatchers.IO.limitedParallelism(1)
    @Volatile private var disk: CacheDisk? = null
    @Volatile internal var clock: () -> Long = System::currentTimeMillis

    /** The lookup itself; a test hands in its own. */
    @Volatile internal var lookup: suspend (Request, Listener) -> LyricsRepository.Result? = ::ask

    private val memory = object : LinkedHashMap<String, Saved>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Saved>?): Boolean = size > MEMORY_ENTRIES
    }
    private val flights = HashMap<String, Flight>()

    /** Gives the store its folder (the app's cache folder, "lyrics" under it), called once at start-up. */
    fun configure(directory: File, maxBytes: Long = 24L * 1024 * 1024) { disk = FileCacheDisk(directory, maxBytes) }

    /** Sets the disk directly (tests); null keeps answers in memory only. */
    internal fun configure(disk: CacheDisk?) { this.disk = disk }

    /** Forgets everything kept in memory and running (tests). */
    internal fun reset() {
        synchronized(memory) { memory.clear() }
        synchronized(flights) { flights.clear() }
        clock = System::currentTimeMillis
        lookup = ::ask
    }

    /**
     * The lyrics for [request]: the kept answer when there is a fresh one (reported through [onSourceResult] as its source's
     * find), else the running lookup for the same song, else a new one. The callbacks mean what [LyricsRepository.lyrics]'s do.
     * Cancelling the caller does not cancel a lookup someone else may be waiting on; it runs on and is kept.
     */
    suspend fun lyrics(
        request: Request,
        onSourceStarted: ((LyricsSource) -> Unit)? = null,
        onSourceResult: ((LyricsSource, LyricsRepository.Result?) -> Unit)? = null,
        onSourceCancelled: ((LyricsSource) -> Unit)? = null,
    ): LyricsRepository.Result? {
        if (request.asked.isEmpty()) return null
        kept(request)?.let { saved ->
            val result = saved.result
            if (result != null) onSourceResult?.invoke(result.source, result)
            return result
        }
        // Null: a lookup for the song finished between the check above and now, and left its answer in memory.
        val flight = flightFor(request) ?: return keptInMemory(request)?.result?.also { onSourceResult?.invoke(it.source, it) }
        val listener = Listener(onSourceStarted, onSourceResult, onSourceCancelled)
        flight.listen(listener)
        try {
            return flight.job.await()
        } finally {
            flight.unlisten(listener)
        }
    }

    /** Looks [request] up ahead (the next song in the queue), unless it is kept or already being looked up. */
    fun prefetch(request: Request) {
        if (request.asked.isEmpty() || request.durationMs <= 0L) return
        scope.launch {
            if (kept(request) == null) runCatching { flightFor(request)?.job?.await() }
        }
    }

    /** Forgets what is kept for [videoId] (its lyrics were rejected as the wrong ones), so the next lookup asks again. */
    fun forget(videoId: String) {
        synchronized(memory) { memory.values.removeIf { it.key.split('|').getOrNull(1) == videoId } }
        // A lookup still running would keep the same answer again: it finishes for whoever waits on it, but is not kept.
        synchronized(flights) {
            val gone = flights.filterKeys { it.split('|').getOrNull(1) == videoId }
            gone.forEach { (key, f) -> f.forgotten.set(true); flights.remove(key) }
        }
        val d = disk ?: return
        scope.launch(diskOrder) { runCatching { d.remove(diskKey(videoId)) } }
    }

    private fun keptInMemory(request: Request): Saved? =
        synchronized(memory) { memory[request.key] }?.takeIf { fresh(it, request.durationMs) }

    /** How many lookups are running (tests). */
    internal fun running(): Int = synchronized(flights) { flights.size }

    /** A fresh kept answer for [request], from memory or the disk. */
    internal suspend fun kept(request: Request): Saved? {
        val key = request.key
        val inMemory = synchronized(memory) { memory[key] }
        val saved = inMemory ?: disk?.let { d ->
            withContext(Dispatchers.IO) {
                runCatching { d.read(diskKey(request.videoId))?.let { json.decodeFromString(Saved.serializer(), it) } }.getOrNull()
            }
        }?.takeIf { it.key == key }?.also { found -> synchronized(memory) { memory[key] = found } }
        return saved?.takeIf { fresh(it, request.durationMs) }
    }

    private fun fresh(saved: Saved, durationMs: Long): Boolean {
        val age = clock() - saved.savedAtMs
        if (age < 0 || age >= (if (saved.result != null) FOUND_FOR_MS else NONE_FOR_MS)) return false
        // Unknown on the asking side (a download with no length): any kept answer will do. Unknown on the kept side, while the
        // asker knows: the lengthed lookup matches better, so it is asked.
        if (durationMs <= 0L) return true
        return saved.durationMs > 0L && abs(saved.durationMs - durationMs) <= DURATION_SLACK_MS
    }

    private fun flightFor(request: Request): Flight? {
        val key = request.key
        val (flight, started) = synchronized(flights) {
            val running = flights[key]?.takeIf { sameLength(it.durationMs, request.durationMs) }
            if (running != null) running to false
            // A lookup keeps its answer before it leaves [flights], so with none running, memory has the latest.
            else if (keptInMemory(request) != null) return null
            else {
                val f = Flight(request.durationMs)
                f.job = scope.async(start = CoroutineStart.LAZY) {
                    try {
                        run(request, f)
                    } finally {
                        synchronized(flights) { if (flights[key] === f) flights.remove(key) }
                    }
                }
                flights[key] = f
                f to true
            }
        }
        if (started) flight.job.start()
        return flight
    }

    private fun sameLength(a: Long, b: Long) = a <= 0L || b <= 0L || abs(a - b) <= DURATION_SLACK_MS

    private suspend fun run(request: Request, flight: Flight): LyricsRepository.Result? {
        val listener = Listener(
            onStarted = { flight.emit(Event.Started(it)) },
            onResult = { source, result -> flight.emit(Event.Finished(source, result)) },
            onCancelled = { flight.emit(Event.Cancelled(it)) },
        )
        val found = lookup(request, listener)
        // "No lyrics" is kept only when every provider gave a real answer: one that failed may have them after all.
        if (flight.forgotten.get()) return found
        if (found != null || !listener.failed.get()) keep(Saved(request.key, clock(), request.durationMs, found?.source, found?.lines))
        return found
    }

    private fun keep(saved: Saved) {
        synchronized(memory) { memory[saved.key] = saved }
        val d = disk ?: return
        val videoId = saved.key.split('|')[1]
        scope.launch(diskOrder) { runCatching { d.write(diskKey(videoId), json.encodeToString(Saved.serializer(), saved)) } }
    }

    private fun diskKey(videoId: String) = "lyrics:$VERSION:$videoId"

    private suspend fun ask(request: Request, listener: Listener): LyricsRepository.Result? = LyricsRepository.lyrics(
        videoId = request.videoId,
        title = request.title,
        artist = request.artist,
        durationMs = request.durationMs,
        album = request.album,
        sources = request.sources,
        order = request.order,
        prioritizeSyllableSync = request.prioritizeSyllableSync,
        isrc = request.isrc,
        onSourceStarted = listener.onStarted,
        onSourceResult = listener.onResult,
        onSourceCancelled = listener.onCancelled,
        onSourceFailed = { listener.failed.set(true) },
    )

    /** One caller's callbacks; for the lookup's own listener, [failed] says whether a provider failed rather than answered. */
    internal class Listener(
        val onStarted: ((LyricsSource) -> Unit)?,
        val onResult: ((LyricsSource, LyricsRepository.Result?) -> Unit)?,
        val onCancelled: ((LyricsSource) -> Unit)?,
    ) {
        val failed = AtomicBoolean(false)

        fun deliver(event: Event) {
            when (event) {
                is Event.Started -> onStarted?.invoke(event.source)
                is Event.Finished -> onResult?.invoke(event.source, event.result)
                is Event.Cancelled -> onCancelled?.invoke(event.source)
            }
        }
    }

    internal sealed interface Event {
        data class Started(val source: LyricsSource) : Event
        data class Finished(val source: LyricsSource, val result: LyricsRepository.Result?) : Event
        data class Cancelled(val source: LyricsSource) : Event
    }

    /** A lookup in progress: what its providers have done so far, replayed to whoever joins, and told to everyone listening. */
    private class Flight(val durationMs: Long) {
        lateinit var job: Deferred<LyricsRepository.Result?>
        /** Set when the song's lyrics were rejected while this ran: its answer is handed over but not kept. */
        val forgotten = AtomicBoolean(false)
        private val events = ArrayList<Event>()
        private val listeners = ArrayList<Listener>()

        // Delivered under the lock, so a listener that joins hears the events in their order, each once.
        fun emit(event: Event) = synchronized(this) {
            events += event
            listeners.forEach { runCatching { it.deliver(event) } }
        }

        fun listen(listener: Listener) = synchronized(this) {
            events.forEach { runCatching { listener.deliver(it) } }
            listeners += listener
        }

        fun unlisten(listener: Listener) = synchronized(this) { listeners -= listener; Unit }
    }
}
