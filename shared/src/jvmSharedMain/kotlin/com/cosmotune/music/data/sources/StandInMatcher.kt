package com.cosmotune.music.data.sources

import com.cosmotune.music.data.model.ArtistIdentity
import com.cosmotune.music.data.model.Song
import com.opencanvas.core.matcher.VideoMatcher
import kotlin.math.abs

/**
 * Another copy of a song whose own copy is gone: an upload YouTube answers "This video is not available" for on every
 * client, removed or blocked for good.
 *
 * [TrackMatcher] is the right judge while that copy plays, because then a refusal costs nothing: YouTube still has the
 * song. Here YouTube has nothing, and the rows that hold the song are often packaged differently from the one that went:
 * a compilation credits a duet to one singer, a catalogue adds the film or the word the title was cut short of
 * ("Song" for "Song Words (From "Film")"). So this accepts a little more, and only with the length to back it:
 *
 *  - the same title, a shared singer, and a length within [TOLERANCE_SEC] ([FILM_TOLERANCE_SEC] for a film song);
 *  - the same title under other names, within [CREDIT_OVERRIDE_SEC] (the same master filed under its other singers);
 *  - a title that goes on for at most [MAX_EXTRA_WORDS] more words, with the lead singer credited and the length within
 *    the tolerance.
 *
 * A take (remix, cover, karaoke, slowed, 8D, lyrics) never stands in, a row without a length is never checked through,
 * and two different longer titles are not chosen between. When no catalogue holds it, [rankedUploads] picks another
 * YouTube upload of the same song, which must name its singer or film.
 */
object StandInMatcher {
    const val TOLERANCE_SEC = 5
    const val FILM_TOLERANCE_SEC = 8
    private const val CREDIT_OVERRIDE_SEC = 2
    private const val MAX_EXTRA_WORDS = 2
    private const val MIN_EXTENDED_WORDS = 2

    /** Catalogue rows that are [target], most confident first; empty when none is, or when the choice is a guess. */
    fun rankedCatalogue(candidates: List<Song>, target: TrackMatcher.Target): List<Song> {
        val wantedDuration = target.durationSec ?: return emptyList()
        val wanted = TrackMatcher.parseTitle(target.title, target.artist)
        val wantedWords = wanted.words.map(TrackMatcher::foldLatin)
        if (wanted.core.isBlank()) return emptyList()
        val lead = ArtistIdentity.primaryName(target.artist).orEmpty()
        val scored = candidates.mapNotNull { candidate ->
            if (candidate.videoId == target.videoId) return@mapNotNull null
            if (takeWord(candidate.title, target.title) != null) return@mapNotNull null
            val got = TrackMatcher.parseTitle(unescape(candidate.title), candidate.artist)
            val albumMarks = TrackMatcher.albumVersionMarkers(candidate.albumName).filter { it in NO_VOCAL_TAKES || it.startsWith("language:") }
            if (got.versions + albumMarks != wanted.versions) return@mapNotNull null
            if (target.isExplicit != null && candidate.isExplicit != null && target.isExplicit != candidate.isExplicit) return@mapNotNull null
            val actual = TrackMatcher.secondsOf(candidate.durationText) ?: return@mapNotNull null
            val drift = abs(wantedDuration - actual)
            val tolerance = if (isFilmSong(target.title, target.album) || isFilmSong(candidate.title, candidate.albumName)) FILM_TOLERANCE_SEC else TOLERANCE_SEC
            val credit = credited(candidate)
            val shared = TrackMatcher.sharesArtist(target.artist, credit)
            val gotWords = got.words.map(TrackMatcher::foldLatin)
            val exact = wanted.core == got.core
            val extended = !exact && wantedWords.size >= MIN_EXTENDED_WORDS && gotWords.size > wantedWords.size &&
                gotWords.size - wantedWords.size <= MAX_EXTRA_WORDS && gotWords.take(wantedWords.size) == wantedWords
            val accepted = when {
                exact && shared -> drift <= tolerance
                exact -> drift <= CREDIT_OVERRIDE_SEC
                extended -> lead.isNotBlank() && TrackMatcher.sharesArtist(lead, credit) && drift <= tolerance
                else -> false
            }
            if (!accepted) return@mapNotNull null
            val score = (if (exact) 40 else 0) + (if (shared) 30 else 0) + (TOLERANCE_SEC * 2 - drift).coerceAtLeast(0)
            Scored(candidate, score, exact, got.core)
        }
        if (scored.any { it.exact }) return scored.filter { it.exact }.sortedByDescending { it.score }.map { it.song }
        // "Song One Word" and "Song Other Word" are two songs that both begin like this one: nothing says which it was.
        if (scored.map { it.core }.distinct().size > 1) return emptyList()
        return scored.sortedByDescending { it.score }.map { it.song }
    }

    /** What to ask YouTube for when no catalogue holds the song: the song with its singer, then with its film. */
    fun uploadQueries(target: TrackMatcher.Target): List<String> {
        val title = TrackMatcher.searchableTitle(target.title, target.artist)
        if (title.isBlank()) return emptyList()
        val artist = TrackMatcher.primaryArtist(target.artist)
        val film = filmOf(target)?.lowercase()
        return listOfNotNull(
            if (artist.isBlank()) title else "$title $artist",
            if (film != null) "$title $film video song" else "$title video song",
        ).distinct()
    }

    /**
     * YouTube uploads (title, channel, length) that carry [target], most trustworthy first: the artist's or a label's
     * channel before anyone else's. The upload has to name the song, and its singer or film beside it, and run within
     * the tolerance; lyric, 8D, slowed, remix, karaoke and cover uploads never qualify.
     */
    fun rankedUploads(candidates: List<Song>, target: TrackMatcher.Target): List<Song> {
        val wantedDuration = target.durationSec ?: return emptyList()
        val wanted = TrackMatcher.parseTitle(target.title, target.artist).words.map(TrackMatcher::foldLatin)
        if (wanted.isEmpty()) return emptyList()
        val film = filmOf(target)
        val context = film ?: target.album?.takeIf { it.isNotBlank() }
        val names = TrackMatcher.artistNames(target.artist)
        return candidates.mapNotNull { candidate ->
            if (candidate.videoId == target.videoId) return@mapNotNull null
            if (takeWord(candidate.title, target.title) != null || takeWord(candidate.artist, target.artist) != null) return@mapNotNull null
            val words = words(candidate.title)
            if (!containsRun(words, wanted)) return@mapNotNull null
            val channel = words(candidate.artist)
            val artistNamed = names.any { name -> name.joinToString("").length >= 4 && (containsRun(words, name) || containsRun(channel, name)) }
            val filmNamed = context != null && VideoMatcher.hasFilmContext(candidate.title, context)
            if (!artistNamed && !filmNamed) return@mapNotNull null
            val actual = TrackMatcher.secondsOf(candidate.durationText) ?: return@mapNotNull null
            val drift = abs(wantedDuration - actual)
            val tolerance = if (film != null || FILM_UPLOAD.containsMatchIn(candidate.title)) FILM_TOLERANCE_SEC else TOLERANCE_SEC
            if (drift > tolerance) return@mapNotNull null
            val compactChannel = channel.joinToString("")
            val own = names.any { name -> name.joinToString("").let { it.length >= 4 && (compactChannel == it || compactChannel == it + "topic" || compactChannel == it + "vevo" || compactChannel == it + "official") } }
            val label = PUBLISHER_WORDS.any { it in channel }
            candidate to (if (own) 40 else 0) + (if (label) 30 else 0) + (if (filmNamed) 20 else 0) + (if (artistNamed) 20 else 0) +
                (FILM_TOLERANCE_SEC - drift).coerceAtLeast(0)
        }.sortedByDescending { it.second }.map { it.first }
    }

    /** The film a song is from, when its title or album says so: `(From "Film")`, `Film (Original Motion Picture Soundtrack)`. */
    internal fun filmOf(target: TrackMatcher.Target): String? =
        FROM.find(unescape(target.title))?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
            ?: target.album?.let(::unescape)?.takeIf { SOUNDTRACK.containsMatchIn(it) }?.let(VideoMatcher::cleanFilmName)?.takeIf { it.isNotBlank() }

    private fun isFilmSong(title: String, album: String?): Boolean =
        FROM.containsMatchIn(unescape(title)) || album?.let { SOUNDTRACK.containsMatchIn(unescape(it)) } == true

    /** The first word of [text] that marks a take or a lyric/8D upload, unless the song's own title has it too. */
    private fun takeWord(text: String, own: String): String? {
        val ownWords = words(own).toSet()
        val got = words(text)
        TAKE_PHRASES.firstOrNull { phrase -> phrase.all { it !in ownWords } && containsRun(got, phrase) }?.let { return it.joinToString(" ") }
        return got.firstOrNull { it in TAKE_WORDS && it !in ownWords }
    }

    private fun words(text: String): List<String> =
        TrackMatcher.foldLatin(TrackMatcher.normalize(unescape(text))).split(SPLIT).filter { it.isNotEmpty() }

    private fun containsRun(outer: List<String>, inner: List<String>): Boolean =
        inner.isNotEmpty() && inner.size <= outer.size && (0..outer.size - inner.size).any { outer.subList(it, it + inner.size) == inner }

    private fun credited(song: Song): String =
        (ArtistIdentity.names(song.artist) + song.artists.map { it.name }).distinct().joinToString(", ")

    private fun unescape(value: String) = value.replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")

    private class Scored(val song: Song, val score: Int, val exact: Boolean, val core: String)

    private val SPLIT = Regex("""[^\p{L}\p{M}\p{N}]+""")
    private val FROM = Regex("""[(\[]\s*from\s+["'“]?(.*?)["'”]?\s*[)\]]""", RegexOption.IGNORE_CASE)
    private val SOUNDTRACK = Regex("""(?i)soundtrack|motion picture|\bost\b|from the film""")
    private val FILM_UPLOAD = Regex("""(?i)\bvideo\s*song\b|\bmovie\b|\bfilm\b""")
    private val NO_VOCAL_TAKES = setOf("instrumental", "karaoke", "acapella", "acappella", "backing", "stems", "stem")

    private val TAKE_WORDS = setOf(
        "lyric", "lyrics", "lyrical", "8d", "16d", "slowed", "reverb", "remix", "remixed", "rmx", "karaoke", "cover",
        "nightcore", "lofi", "instrumental", "mashup", "jukebox", "reaction", "dj", "status", "shorts", "reels", "bgm",
    )
    private val TAKE_PHRASES = listOf(listOf("sped", "up"), listOf("lo", "fi"), listOf("bass", "boosted"), listOf("8", "d"))

    /** Channel-name words of labels, studios and TV channels that put up film songs. */
    private val PUBLISHER_WORDS = setOf("music", "records", "labels", "films", "movies", "vevo", "entertainment",
        "tv", "series", "pictures", "studios", "cinemas", "audio", "topic")
}
