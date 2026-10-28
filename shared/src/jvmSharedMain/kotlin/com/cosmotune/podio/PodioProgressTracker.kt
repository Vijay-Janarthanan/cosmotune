package com.cosmotune.podio

/** Kept by the playback owner so dismissing Podio does not stop recording episode positions. */
class PodioProgressTracker(private val library: PodioLibrary,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 }) : AutoCloseable {
    private data class Position(val id: String, val positionMs: Long, val playing: Boolean, val durationMs: Long)
    private var previous: Position? = null
    private var writtenAt = Long.MIN_VALUE
    private var written: Position? = null

    @Synchronized fun observe(id: String?, positionMs: Long, playing: Boolean, loading: Boolean = false, durationMs: Long = 0) {
        val knownDuration = durationMs.takeIf { it > 0 } ?: previous?.takeIf { it.id == id }?.durationMs ?: 0
        val next = id?.takeIf { it.startsWith("podio:episode:") && !loading }
            ?.let { Position(it, positionMs.coerceAtLeast(0), playing, knownDuration) }
        val old = previous
        if (old != null && old.id != next?.id) persist(old)
        // A decoder reset while the same episode is loading must not replace its saved position.
        if (loading && id == old?.id) return
        previous = next
        if (next == null) return
        val now = clockMs()
        if (!next.playing || writtenAt == Long.MIN_VALUE || now - writtenAt >= 5_000) persist(next)
    }

    private fun persist(position: Position) {
        if (written?.id == position.id && written?.positionMs == position.positionMs && written?.durationMs == position.durationMs) return
        runCatching { library.recordProgress(position.id, position.positionMs, position.durationMs) }.onSuccess {
            written = position
            writtenAt = clockMs()
        }
    }

    @Synchronized override fun close() { previous?.let(::persist); previous = null }
}
