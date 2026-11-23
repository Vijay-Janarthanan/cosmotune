package com.cosmotune.podio

import kotlinx.serialization.Serializable

/**
 * A moment in an episode the listener marked (Pocket Casts' bookmarks): where, an optional note, and enough of the episode to play
 * it from there again without its feed.
 */
@Serializable data class PodioBookmark(val episodeId: String, val positionMs: Long, val episodeTitle: String = "", val showTitle: String = "",
    val url: String = "", val artwork: String = "", val durationSeconds: Long = 0, val note: String = "", val createdMs: Long = 0) {
    /** The episode as the player takes it. */
    fun item(): PodioItem = PodioItem(episodeId, episodeTitle, showTitle, url, artwork, durationSeconds = durationSeconds)
    val key: String get() = "$episodeId@$positionMs"
}

object PodioBookmarks {
    const val MAX = 500
    /** Two marks this close in the same episode are one. */
    private const val SAME_MS = 3_000L

    /** [saved] with [bookmark] added; one within a few seconds of another in the same episode replaces it. */
    fun add(saved: PodioSaved, bookmark: PodioBookmark): PodioSaved {
        if (!bookmark.episodeId.startsWith("podio:episode:")) return saved
        val mark = bookmark.copy(positionMs = bookmark.positionMs.coerceAtLeast(0), note = bookmark.note.trim().take(300))
        val rest = saved.bookmarks.filterNot { it.episodeId == mark.episodeId && kotlin.math.abs(it.positionMs - mark.positionMs) < SAME_MS }
        return saved.copy(bookmarks = (rest + mark).takeLast(MAX))
    }

    fun remove(saved: PodioSaved, key: String): PodioSaved = saved.copy(bookmarks = saved.bookmarks.filterNot { it.key == key })

    fun note(saved: PodioSaved, key: String, note: String): PodioSaved =
        saved.copy(bookmarks = saved.bookmarks.map { if (it.key == key) it.copy(note = note.trim().take(300)) else it })

    /** An episode's bookmarks in playing order. */
    fun of(saved: PodioSaved, episodeId: String): List<PodioBookmark> = saved.bookmarks.filter { it.episodeId == episodeId }.sortedBy { it.positionMs }

    /** Every bookmark, the newest first. */
    fun newestFirst(saved: PodioSaved): List<PodioBookmark> = saved.bookmarks.sortedByDescending { it.createdMs }

    /**
     * The episode's address opened at [positionMs] (a media fragment, "#t=754", which podcast players and browsers start from), to
     * share or copy.
     */
    fun linkAt(url: String, positionMs: Long): String = url.substringBefore('#') + "#t=" + (positionMs.coerceAtLeast(0) / 1000)
}
