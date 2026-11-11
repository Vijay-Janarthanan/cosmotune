package com.cosmotune.music.data.canvas

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.Normalizer
import java.util.Locale

/**
 * What the catalogues have said about a release's genre (or, for JioSaavn, its language), kept for the title art's voice (Canvas
 * repo, design/cosmotune/lyrics-titles, Voiced type). Nothing is fetched for it: the Apple lookups the animated covers already make
 * return `genreNames` on every song and album, and JioSaavn's search rows carry a `language`; those answers are noted here as they
 * pass. YouTube Music's album and song data carry no genre, so a release only YouTube knows has none.
 *
 * Keyed by the name (an album's, or a song's, which the song card heads with) and the lead artist; a name alone answers only
 * when the caller has no artist either. Held in memory, the latest 512 names, like the canvas caches it rides along with.
 */
object AlbumGenres {
    private const val ENTRIES = 512

    private val genres = object : LinkedHashMap<String, String>(0, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, String>) = size > ENTRIES
    }

    private val _version = MutableStateFlow(0)

    /** Moves on whenever an answer is noted, so a title already showing can pick its voice up. */
    val version: StateFlow<Int> = _version.asStateFlow()

    /**
     * Notes [names] (the catalogue's own, e.g. Apple's `["Soundtrack", "Music", "Tamil"]`) for [name] by [artist]. The catch-all
     * "Music" says nothing and is dropped; the rest are kept together ("Soundtrack / Tamil"), as the voice reads every part.
     */
    fun record(name: String?, artist: String?, names: List<String>) {
        val genre = genreOf(names) ?: return
        val title = key(name ?: return).ifEmpty { return }
        val changed = synchronized(genres) {
            val keys = listOf(title + "|" + lead(artist), "$title|")
            var any = false
            for (k in keys) if (genres.put(k, genre) != genre) any = true
            any
        }
        if (changed) _version.value++
    }

    /** What is known about [name] by [artist], or null. */
    fun genreFor(name: String?, artist: String?): String? {
        val title = key(name ?: return null).ifEmpty { return null }
        return synchronized(genres) {
            if (artist.isNullOrBlank()) genres["$title|"] else genres[title + "|" + lead(artist)]
        }
    }

    /** The catalogue's genre names as one, without the catch-all; null when nothing is left. */
    fun genreOf(names: List<String>): String? = names.map(String::trim)
        .filter { it.isNotEmpty() && !it.equals("Music", ignoreCase = true) }
        .distinctBy { it.lowercase(Locale.ROOT) }
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" / ")

    /** Forgets everything (tests, and a sign-out that clears the caches). */
    fun clear() {
        synchronized(genres) { genres.clear() }
        _version.value++
    }

    /**
     * A name folded for matching in any script: compatibility forms, case, punctuation and runs of space. (The canvas matcher's
     * own folding keeps a to z only, which would turn a Tamil or Hindi title into nothing.)
     */
    internal fun key(name: String): String = Normalizer.normalize(name, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace(PUNCTUATION, " ")
        .replace(SPACES, " ")
        .trim()

    private fun lead(artist: String?): String = artist?.let { splitArtistNames(it).firstOrNull() }.orEmpty()

    private fun splitArtistNames(raw: String): List<String> = raw.split(SEPARATORS).map(::key).filter(String::isNotEmpty)

    private val PUNCTUATION = Regex("[\\p{P}\\p{S}]")
    private val SPACES = Regex("\\s+")
    private val SEPARATORS = Regex(
        "(?:\\s*,\\s*|\\s*&\\s*|\\s+×\\s+|\\s+x\\s+|\\bfeat\\.?\\b|\\bft\\.?\\b|\\bfeaturing\\b|\\bwith\\b)",
        RegexOption.IGNORE_CASE,
    )
}
