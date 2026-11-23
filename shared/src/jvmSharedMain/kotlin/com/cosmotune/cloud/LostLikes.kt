package com.cosmotune.cloud

import kotlinx.serialization.json.JsonObject

/**
 * Likes YouTube lost (the idea from Mochy, see NOTICE.md): Cosmotune keeps each like made in it (and, with sync on, on the
 * listener's other devices) in its own likes file, so the songs liked here that YouTube's Liked music no longer lists can be
 * found and liked again.
 */
object LostLikes {
    data class Lost(val videoId: String, val title: String, val artist: String)

    /** The kept likes YouTube's [onYouTube] list lacks: only YouTube's own songs, never a file's or a source's like. */
    fun missing(kept: Map<String, JsonObject>, onYouTube: Set<String>): List<Lost> =
        kept.filter { (id, _) -> SyncLikes.travels(id) && YOUTUBE_ID.matches(id) && id !in onYouTube }
            .map { (id, data) -> Lost(id, SyncLikes.title(data).orEmpty().ifBlank { "A liked song" }, SyncLikes.artist(data).orEmpty()) }

    /** Likes each song again, one after another, and answers how many YouTube accepted; [onProgress] gets the count done so far. */
    suspend fun restore(lost: List<Lost>, like: suspend (Lost) -> Boolean, onProgress: (Int) -> Unit = {}): Int {
        var accepted = 0
        lost.forEachIndexed { index, song ->
            if (runCatching { like(song) }.getOrDefault(false)) accepted++
            onProgress(index + 1)
        }
        return accepted
    }

    /** "3 songs you liked are missing from YouTube's Liked music", or the all-clear. */
    fun summary(missing: Int, checked: Int): String = when {
        missing == 0 -> "Every like you made in Cosmotune is on YouTube ($checked checked)"
        missing == 1 -> "1 song you liked is missing from YouTube's Liked music"
        else -> "$missing songs you liked are missing from YouTube's Liked music"
    }

    private val YOUTUBE_ID = Regex("[A-Za-z0-9_-]{11}")
}
