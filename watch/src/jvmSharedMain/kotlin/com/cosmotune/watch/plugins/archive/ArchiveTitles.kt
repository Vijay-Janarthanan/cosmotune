package com.cosmotune.watch.plugins.archive

/** The Archive's titles as people would write them, and what the built-in rows leave out. */
object ArchiveTitles {
    private val YEAR = Regex("""\b(1[89]\d\d|20\d\d)\b""")
    /** A bracketed part holding a year: "(1925)", "[1915]", "( Charles Chaplin-1915 )". */
    private val BRACKET_WITH_YEAR = Regex("""\s*[(\[][^()\[\]]*\b(?:1[89]\d\d|20\d\d)\b[^()\[\]]*[)\]]""")
    /** Uploader noise in brackets: "(HD)", "[720p]", "(Full Movie)", "(Public Domain)". */
    private val BRACKET_NOISE = Regex(
        """\s*[(\[]\s*(?:hd|sd|hq|4k|\d{3,4}p|full movie|full film|complete film|public domain|restored|remastered|dvd ?rip|vhs ?rip)\s*[)\]]""",
        RegexOption.IGNORE_CASE,
    )
    /** An opening bracket never closed, to the end: "His New Job ( Charles Chaplin-19". */
    private val OPEN_TAIL = Regex("""\s*[(\[][^)\]]*$""")
    /** A year hanging off the end: "Detour - 1945", "Detour 1945". */
    private val YEAR_TAIL = Regex("""[\s,:|\-–—]+(?:1[89]\d\d|20\d\d)$""")
    /** "Full Movie" hanging off the end: "Detour - Full Movie". */
    private val FULL_MOVIE_TAIL = Regex("""[\s,:|\-–—]+(?:full (?:movie|film)|public domain)$""", RegexOption.IGNORE_CASE)
    private val SPACES = Regex("""\s+""")

    /** The cleaned title and a year found in it (null when none). The raw title comes back when cleaning leaves nothing. */
    fun clean(raw: String): Pair<String, Int?> {
        var t = raw.replace(SPACES, " ").trim()
        var year: Int? = BRACKET_WITH_YEAR.find(t)?.let { m -> YEAR.find(m.value)?.value?.toIntOrNull() }
        t = t.replace(BRACKET_WITH_YEAR, "").replace(BRACKET_NOISE, "").replace(OPEN_TAIL, "")
        if (year == null) year = YEAR_TAIL.find(t)?.let { m -> YEAR.find(m.value)?.value?.toIntOrNull() }
        t = t.replace(YEAR_TAIL, "").replace(FULL_MOVIE_TAIL, "").trim().trimEnd('-', '–', '—', ',', ':', '|').trim()
        return t.ifBlank { raw.trim() } to year
    }

    /** Lucene for the Archive's own adult collections and subjects. */
    const val ADULT_QUERY = "(collection:(adultcdroms OR sexploitation OR erotica OR stag_films) OR " +
        "subject:(sexploitation OR erotica OR pornography OR nudity OR nudist OR \"adult film\" OR \"stag film\"))"

    private val ADULT_WORDS = Regex(
        """\b(?:sex|sexy|sexual|sexploitation|exploitation|nude|nudes|nudist|nudity|naked|erotic|erotica|porn|porno|pornography|xxx|""" +
            """stripper|striptease|strip-tease|burlesque|nympho\w*|orgy|orgies|lust|lustful|topless|stag film|adults only|for adults)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Whether an item looks adult or exploitation by its title, subjects or collections. */
    fun adult(title: String, tags: List<String>): Boolean =
        ADULT_WORDS.containsMatchIn(title) || tags.any { ADULT_WORDS.containsMatchIn(it) || it.equals("adult", true) }
}
