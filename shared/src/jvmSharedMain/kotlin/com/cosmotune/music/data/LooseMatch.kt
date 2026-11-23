package com.cosmotune.music.data

import com.cosmotune.music.data.model.MusicSongList

/**
 * The find-in-list rule of the library, the playlists and "In my library": every word typed has to turn up in the title, artist or
 * album, but forgivingly. Case and Latin accents are ignored ("beyonce" finds Beyoncé; Indic vowel signs are kept), and a word of five letters or more may have one
 * letter wrong, missing or extra ("beatels", "rhianna"), nine or more two; a short word must match as typed.
 */
object LooseMatch {

    /** Whether every word of [query] is in [fields]; a blank query matches everything. */
    fun matches(query: String, vararg fields: String?): Boolean {
        val words = wordsOf(query)
        if (words.isEmpty()) return true
        val text = fold(fields.filterNotNull().joinToString(" "))
        val tokens = text.split(' ').filter(String::isNotEmpty)
        return words.all { word -> word in text || tokens.any { close(word, it) } }
    }

    /** [text] in lower case, Latin accents taken off, words split by single spaces: the playlists' own find key. */
    fun fold(text: String): String = MusicSongList.key(text)

    private fun wordsOf(query: String): List<String> = fold(query).split(' ').filter(String::isNotEmpty)

    /** [word] against [token], or against the start of a longer token so a half-typed word still finds it. */
    private fun close(word: String, token: String): Boolean {
        val allowed = when {
            word.length >= 9 -> 2
            word.length >= 5 -> 1
            else -> 0
        }
        if (allowed == 0) return false
        if (distance(word, token, allowed) <= allowed) return true
        return token.length > word.length && distance(word, token.take(word.length), allowed) <= allowed
    }

    /** Edit distance with swapped neighbours counting once ("beatels"), given up as soon as it is past [limit]. */
    private fun distance(a: String, b: String, limit: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1
        var before = IntArray(b.length + 1)
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            var best = current[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var value = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) value = minOf(value, before[j - 2] + 1)
                current[j] = value
                if (value < best) best = value
            }
            if (best > limit) return limit + 1
            val spare = before
            before = previous
            previous = current
            current = spare
        }
        return previous[b.length]
    }
}
