package com.cosmotune.podio

/** Content changes, rather than opening/closing a page, own the temporary spoken-audio rate. */
object PodioPlaybackPolicy {
    fun isPodio(id: String?): Boolean = id?.startsWith("podio:") == true
    /** A long episode tolerates ten seconds; a short trailer never starts out already finished. */
    fun isFinished(positionMs: Long, durationMs: Long): Boolean = durationMs > 0 && positionMs > 0 &&
        positionMs >= durationMs - minOf(10_000L, (durationMs / 10).coerceAtLeast(1))
    fun speedOnTransition(previousId: String?, currentId: String?, musicSpeed: Float): Float? = when {
        currentId == null || currentId == previousId -> null
        isPodio(currentId) -> 1f
        isPodio(previousId) -> musicSpeed
        else -> null
    }
}
