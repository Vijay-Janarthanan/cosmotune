package com.cosmotune.podio

/**
 * How long a download still needs (gPodder's remaining time), from how fast it has gone since it was first seen: "about 3 min
 * left". Nothing is said in the first seconds or before it has moved, so the guess is never wild.
 */
class PodioDownloadEta(private val nowMs: () -> Long = System::currentTimeMillis) {
    private val first = HashMap<String, Pair<Long, Float>>()

    @Synchronized fun left(id: String, fraction: Float): String? {
        val now = nowMs()
        val (startedAt, startedFrom) = first.getOrPut(id) { now to fraction }
        val done = fraction - startedFrom
        val elapsed = now - startedAt
        if (done < 0.02f || elapsed < 3_000 || fraction >= 1f) return null
        val restMs = ((1f - fraction) / done * elapsed).toLong()
        val minutes = (restMs + 30_000) / 60_000
        return when {
            restMs < 60_000 -> "under a minute left"
            minutes < 60 -> "about $minutes min left"
            else -> "about ${minutes / 60} h ${minutes % 60} min left"
        }
    }

    /** Forgets the downloads that are no longer running. */
    @Synchronized fun keepOnly(running: Set<String>) { first.keys.retainAll(running) }
}

/** Words the hosts show for downloads. */
object PodioFormatting {
    /** "Downloading 40% · about 2 min left". */
    fun downloading(fraction: Float, left: String?): String = "Downloading ${(fraction.coerceIn(0f, 1f) * 100).toInt()}%" + left?.let { " · $it" }.orEmpty()
}
