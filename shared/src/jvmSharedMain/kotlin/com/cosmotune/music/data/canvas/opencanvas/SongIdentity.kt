package com.opencanvas.core.stream

import kotlin.math.abs

/**
 * A song as a listener knows it: its title, its artists and its length, whichever upload or service is playing it.
 *
 * The same song reaches the player under several track ids ("Hangova (From "DC")" is B6ppp6WBCKg, "Hangova" is
 * kynWdUG1NvY, and a lossless copy from another service has no YouTube id at all), and a map measured for one of them
 * holds for the others. The title is the cleaned one ("(From "DC")" and similar packaging removed, edition markers
 * such as "(Female Version)" kept), the artists are compared as a set, case and punctuation ignored.
 */
internal data class SongIdentity(val key: String, val durationSec: Long) {
    /** Whether a recording of [otherSec] seconds is this length: the same audio, so the same map. */
    fun sameLength(otherSec: Long): Boolean = durationSec > 0L && otherSec > 0L && abs(durationSec - otherSec) <= SAME_LENGTH_SEC

    companion object {
        /** Services round a length differently (247.86 s is 247 or 248); more than this is another cut. */
        const val SAME_LENGTH_SEC = 2L

        private val ARTIST_SEPARATOR = Regex("""\s*(?:,|&|\bfeat\.?|\bft\.?|\bfeaturing\b|\bwith\b|\bx\b|\band\b)\s*""", RegexOption.IGNORE_CASE)

        fun of(title: String, artist: String, durationSec: Long): SongIdentity? {
            val name = compact(MusicVideoQuery.from(title, null).title)
            val artists = artist.split(ARTIST_SEPARATOR).map(::compact).filter(String::isNotEmpty).distinct().sorted()
            if (name.isEmpty() || artists.isEmpty()) return null
            return SongIdentity("$name|${artists.joinToString("+")}", durationSec.coerceAtLeast(0L))
        }

        private fun compact(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }
    }
}
