package com.cosmotune.music.data.model

/** Provider playlist occurrences are not recording IDs: the same song may appear twice. */
object MusicCollectionPages {
    data class Joined(val songs: List<Song>, val continuation: String?, val seenTokens: Set<String>, val error: String?)

    fun append(current: List<Song>, incoming: List<Song>, token: String, next: String?, seen: Set<String>): Joined {
        if (token in seen || seen.size >= 128) return Joined(current, null, seen,
            "The source repeated a page or reached the page limit. Reopen this collection to refresh it.")
        val entries = current.mapNotNullTo(HashSet()) { it.setVideoId?.takeIf(String::isNotBlank) }
        val additions = incoming.filter { song -> song.setVideoId?.takeIf(String::isNotBlank)?.let(entries::add) ?: true }
        val consumed = seen + token
        val loop = next != null && next in consumed
        return Joined(current + additions, next.takeUnless { loop }, consumed,
            if (loop) "The source repeated its next-page token. Reopen this collection to refresh it." else null)
    }
}
