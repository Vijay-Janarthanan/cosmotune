package com.opencanvas.core

import com.opencanvas.core.stream.YouTubeStreamResolver
import com.opencanvas.core.sync.Measurement
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Whether a measurement lines the video up with the song well enough to be shown. */
internal fun Measurement.isAligned(): Boolean = align.error == null &&
    align.confidence >= YouTubeStreamResolver.MIN_SYNC_CONFIDENCE && align.segments.isNotEmpty()

/**
 * The measurements of a song against a video, shared by every lookup for that song (the library's sessions are keyed on the
 * song's stream id, so a stream id that changes mid-song starts a second lookup of the same song):
 *  - a measurement that lined up is kept, and a later lookup of the same song and video takes it without measuring again;
 *  - while one lookup is measuring a video, another that wants the same song and video waits for that answer instead of
 *    measuring it a second time beside it (on a slow device two measurings at once is what ran past the batch's wait).
 * A lookup that was cancelled hands nothing on: whoever waited for it measures for itself. A measurement that did not line up
 * is never kept (it may have been a dropped connection), so the next lookup measures again.
 */
internal class MeasurementShare(private val capacity: Int = 64) {
    private val lock = Any()
    private val kept = object : LinkedHashMap<String, Measurement>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Measurement>): Boolean = size > capacity
    }
    private val running = HashMap<String, CompletableDeferred<Measurement?>>()

    /** The kept measurement for [key], if there is one. */
    fun kept(key: String): Measurement? = synchronized(lock) { kept[key] }

    /**
     * The measurement for [key]: the kept one, the one another lookup is making now, or [block]'s. A null [key] (a song with no
     * identity to share it under) always measures.
     */
    suspend fun measure(key: String?, block: suspend () -> Measurement?): Measurement? {
        if (key == null) return block()
        while (true) {
            var mine: CompletableDeferred<Measurement?>? = null
            val other: CompletableDeferred<Measurement?>? = synchronized(lock) {
                kept[key]?.let { return it }
                running[key] ?: run {
                    mine = CompletableDeferred<Measurement?>().also { running[key] = it }
                    null
                }
            }
            if (other != null) {
                val shared = try {
                    other.await()
                } catch (failure: Throwable) {
                    // The other lookup was cancelled (or failed) before it had an answer: measure for ourselves, unless it is
                    // this lookup that is being cancelled.
                    currentCoroutineContext().ensureActive()
                    continue
                }
                // An answer that did not line up is not trusted for this lookup's copy of the song: it measures for itself.
                return if (shared != null && shared.isAligned()) shared else block()
            }
            val own = mine!!
            try {
                val result = block()
                if (result != null && result.isAligned()) synchronized(lock) { kept[key] = result }
                own.complete(result)
                return result
            } catch (failure: Throwable) {
                own.completeExceptionally(failure)
                throw failure
            } finally {
                synchronized(lock) { if (running[key] === own) running.remove(key) }
            }
        }
    }

    companion object {
        /**
         * The key a measurement of [videoId] is shared under: the song (its name, artists and length) when the length is known,
         * so a new stream id of the same song finds it; else the song's own stream id; null when there is neither.
         */
        fun key(identity: com.opencanvas.core.stream.SongIdentity?, songCacheId: String?, videoId: String): String? = when {
            identity != null && identity.durationSec > 0L -> "${identity.key}|${identity.durationSec}|$videoId"
            !songCacheId.isNullOrBlank() -> "$songCacheId|$videoId"
            else -> null
        }
    }
}

/**
 * How long a batch of candidates is waited for, counting only the time the device was running: a stall (a long
 * garbage-collection pause, a device short of memory) stops the measuring and the clock that waits for it alike, so the time it
 * took is given back, up to [maxExtraMs] in all. [clock] is in milliseconds.
 */
internal class BatchDeadline(timeoutMs: Long, private val maxExtraMs: Long = timeoutMs, private val clock: () -> Long = System::currentTimeMillis) {
    private var endsAt = clock() + timeoutMs
    private var extra = 0L

    /** Milliseconds left (0 when the wait is over). */
    fun remaining(): Long = (endsAt - clock()).coerceAtLeast(0L)

    /** Waits [ms] from now instead (the short grace once a usable map is in); what was given back for stalls stays spent. */
    fun restart(ms: Long) {
        endsAt = clock() + ms
    }

    /**
     * A wait of [askedMs] took [tookMs]: past [STALL_SLACK_MS] over what was asked, the device was stalled, and the stall is
     * added to the deadline (within [maxExtraMs]).
     */
    fun waited(askedMs: Long, tookMs: Long) {
        val stall = tookMs - askedMs - STALL_SLACK_MS
        if (stall <= 0L || extra >= maxExtraMs) return
        val given = minOf(stall, maxExtraMs - extra)
        extra += given
        endsAt += given
    }

    companion object {
        /** How late a wait may wake without counting as a stall: ordinary scheduling. */
        const val STALL_SLACK_MS = 150L

        /** The slice the batch is waited in, so a stall is noticed while it waits. */
        const val SLICE_MS = 250L
    }
}
