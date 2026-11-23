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

    /**
     * Every page after [songs], fetched one after another, so a whole album or playlist can go somewhere at once
     * (Add to playlists). [onProgress] hears the count after each page. A failed page stops the walk and keeps what
     * arrived, with the token still set and an error to say so; cancelling the caller's coroutine stops it too.
     */
    suspend fun loadAll(
        songs: List<Song>,
        continuation: String?,
        seen: Set<String>,
        fetch: suspend (String) -> Result<Pair<List<Song>, String?>>,
        onProgress: (Int) -> Unit = {},
    ): Joined {
        var joined = Joined(songs, continuation, seen, null)
        while (true) {
            val token = joined.continuation ?: return joined
            val page = fetch(token)
            val failure = page.exceptionOrNull()
            if (failure != null) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                return joined.copy(error = "Could not load every song. Try again.")
            }
            val (incoming, next) = page.getOrThrow()
            joined = append(joined.songs, incoming, token, next, joined.seenTokens)
            onProgress(joined.songs.size)
            if (joined.error != null) return joined
        }
    }
}
