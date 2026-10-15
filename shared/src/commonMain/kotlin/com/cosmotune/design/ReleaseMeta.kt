package com.cosmotune.design

import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.durationMillis

/**
 * What an album, single, EP or playlist page says about itself, from what the page has: the word for what it is, the year, the
 * number of songs and how long they play together, as the faint line under the title (`type · year · N songs · duration`, the
 * board's `2026 · 10 songs · 38 min` with the type word first). Pure, so the rules can be tested without a screen; the words
 * themselves (the type names, "songs", "min") are the screen's, from its string resources.
 *
 * The rules are the old `DetailScreen.kt`'s, kept as they were: [parse] is its `headerLines` (the subtitle taken apart, with its
 * `KIND_WORDS`), [playtime] its `playtime` (sum first, then whole minutes), [looksLikeCredit] its `looksLikeCredit` and
 * `CREDIT_SEPARATOR`, [topRelease] its `topRelease` with [releaseYear] its `releaseYear`. Only the way they are written down
 * differs: the old helpers drew strings as they went (`stringResource`), these hand back the parts.
 */
object ReleaseMeta {

    /** The four things a release page can be, as the overline names them. */
    enum class Kind { ALBUM, SINGLE, EP, PLAYLIST }

    /**
     * A browse card's subtitle taken apart ("Album • Travis Scott • 2023"): [kindWord] the word that says what the page is (as
     * the card spells it), [year] the four digits, [credit] everything else joined with ", ".
     */
    data class Subtitle(val kindWord: String?, val year: String?, val credit: String)

    /** How long the songs play together: [hours] is 0 under an hour. */
    data class Playtime(val hours: Int, val minutes: Int)

    /** Everything the meta line is made of. [songCount] is the number of songs (0 while the page loads); [playtime] is null when no song says how long it is. */
    data class Parts(val kind: Kind?, val year: String?, val credit: String, val songCount: Int, val playtime: Playtime?)

    /** Subtitle words that name what a page *is* rather than who made it (`KIND_WORDS`). */
    private val KIND_WORDS = setOf("album", "single", "ep", "playlist", "artist", "podcast", "episode", "song", "video")

    /** The two dots a subtitle is separated with. */
    private val SEPARATORS = arrayOf("•", "·")

    /** `DetailPage.headerLines`: the year is the last part of four digits, the kind the first part that is a kind word, the credit what is left. */
    fun parse(subtitle: String): Subtitle {
        val parts = subtitle.split(*SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() }
        val year = parts.lastOrNull { it.length == 4 && it.all(Char::isDigit) }
        val kindWord = parts.firstOrNull { it.lowercase() in KIND_WORDS }
        val credit = parts.filter { it != year && it != kindWord }.joinToString(", ")
        return Subtitle(kindWord, year, credit)
    }

    /**
     * Which of the four words the page gets: the one its subtitle says, else the one its type says (an album page is an album, a
     * playlist page a playlist); null for any other page (a podcast, an artist), which shows no word.
     */
    fun kind(subtitle: String, type: BrowseType): Kind? {
        val word = parse(subtitle).kindWord?.lowercase()
        return when (word) {
            "album" -> Kind.ALBUM
            "single" -> Kind.SINGLE
            "ep" -> Kind.EP
            "playlist" -> Kind.PLAYLIST
            else -> when (type) {
                BrowseType.ALBUM -> Kind.ALBUM
                BrowseType.PLAYLIST -> Kind.PLAYLIST
                BrowseType.ARTIST, BrowseType.OTHER -> null
            }
        }
    }

    /**
     * `playtime`: the durations (`m:ss` or `h:mm:ss`) summed, then cut to whole minutes; a song with no duration, or one that does not
     * read, adds nothing. Under a minute (or nothing known) there is no playtime.
     */
    fun playtime(durationTexts: List<String?>): Playtime? {
        val minutes = durationTexts.sumOf { it.durationMillis() } / 60_000L
        if (minutes <= 0L) return null
        return Playtime(hours = (minutes / 60).toInt(), minutes = (minutes % 60).toInt())
    }

    /** The parts of the line for a page with [subtitle] and [type] and these songs' durations (one entry per song). */
    fun parts(subtitle: String, type: BrowseType, durationTexts: List<String?>): Parts {
        val read = parse(subtitle)
        return Parts(
            kind = kind(subtitle, type),
            year = read.year,
            credit = read.credit,
            songCount = durationTexts.size,
            playtime = playtime(durationTexts),
        )
    }

    /** The line itself: what is given, in this order, around middle dots; a part that is missing or blank is left out with its dot. */
    fun line(type: String?, year: String?, songs: String?, duration: String?): String =
        listOf(type, year, songs, duration).filter { !it.isNullOrBlank() }.joinToString(" · ")

    /** `CREDIT_SEPARATOR`: a comma, an ampersand, or "x", "and", "feat.", "ft.", "with" between two names. */
    private val CREDIT_SEPARATOR = Regex(""",\s|\s&\s|\s(?:x|and|feat\.?|ft\.?|with)\s""", RegexOption.IGNORE_CASE)

    /** `looksLikeCredit`: whether [name] reads as several artists credited together rather than one name. */
    fun looksLikeCredit(name: String): Boolean = CREDIT_SEPARATOR.containsMatchIn(name)

    private val RELEASE_YEAR = Regex("""\b(19|20)\d{2}\b""")

    /** `releaseYear`: the year in a card's subtitle, all a shelf item says about when. */
    fun releaseYear(item: ShelfItem): Int? = RELEASE_YEAR.find(item.subtitle)?.value?.toIntOrNull()

    /**
     * `topRelease`: the newest release on an artist's shelves, an album or a single or EP that came out after it. Playlists, videos
     * and related artists share these shelves and are skipped: only an `MPRE...` browse id is a release. Newest by [releaseYear];
     * ties and releases with no year keep YouTube's own order, which lists albums first.
     */
    fun topRelease(sections: List<HomeShelf>): ShelfItem? =
        sections.asSequence()
            .flatMap { it.items.asSequence() }
            .filter { it.browseId?.startsWith("MPRE") == true }
            .withIndex()
            .maxWithOrNull(
                compareBy<IndexedValue<ShelfItem>> { releaseYear(it.value) ?: 0 }
                    .thenByDescending { it.index },
            )?.value
}
