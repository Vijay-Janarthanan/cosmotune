package com.cosmotune.podio

/** Content changes, rather than opening/closing a page, own the temporary spoken-audio rate. */
object PodioPlaybackPolicy {
    fun isPodio(id: String?): Boolean = id?.startsWith("podio:") == true
    fun speedOnTransition(previousId: String?, currentId: String?, musicSpeed: Float): Float? = when {
        currentId == null || currentId == previousId -> null
        isPodio(currentId) -> 1f
        isPodio(previousId) -> musicSpeed
        else -> null
    }
}
