package com.cosmotune.music.data.lyrics

/**
 * A YouTube title, as a lyrics database would have indexed it.
 *
 * Every source here but [YouTubeMusicLyrics] is asked for a name, and the name
 * this app has is YouTube's, which is not the name anyone catalogued. "Dracula
 * (feat. JENNIE)" is filed by Apple, LRCLIB and everyone else as "Dracula", and
 * asked for verbatim it misses all of them — which is exactly how a track ends
 * up answered by whichever source happened to clean the title on its own
 * (only one source ever did) regardless of the order the user put
 * their sources in.
 *
 * ### What is taken off, and what is deliberately left on
 *
 * Only credits and packaging: who else is on the record, and how the upload was
 * labelled. Those are not part of the recording's name anywhere.
 *
 * Anything that names a *different recording* stays — "(Remix)", "(Acoustic)",
 * "(Live)", "(Remastered 2011)", "(Sped Up)". Those look like the same kind of
 * bracket and are the opposite: stripping them turns a search for one recording
 * into a search for another, and the lyrics that come back are confidently
 * wrong rather than merely missing. A miss is recoverable; the wrong words
 * scrolling in time with the right song is not.
 */
fun String.forLyricsSearch(): String {
    // A video upload's title carries its credits after pipes ("Rathinamo - Official Music Video | Singer | Composer | Label"): the
    // song is the first part.
    var name = this.split(" | ", "| ", " |").firstOrNull { it.isNotBlank() } ?: this
    CREDITS.forEach { pattern -> name = pattern.replace(name, " ") }
    return name.replace(WHITESPACE, " ").trim().trimEnd(',', '-', '–', '—').trim()
        // A title that was *only* packaging is no title at all; better to ask
        // with what we were given than with nothing.
        .ifBlank { trim() }
}

/**
 * The names a video upload credits after its title's pipes: "Rathinamo - Official Music Video | Saurav Srisan | Tanisha
 * Gnanavel | Vidhu, Yogalakshmi" credits Saurav Srisan, Tanisha Gnanavel and "Vidhu, Yogalakshmi". The channel that uploaded it
 * is often only one of them, while the catalogue files the song under another; matching takes any of these as the artist.
 * Empty for a title without pipes.
 */
fun String.uploadCredits(): List<String> {
    val parts = split(PIPE)
    if (parts.size < 2) return emptyList()
    return parts.drop(1).map { it.trim() }.filter { it.isNotEmpty() }
}

private val PIPE = Regex("""\s*\|\s*""")

/**
 * Trims " - Topic" off an auto-generated channel name.
 *
 * YouTube's own artist channels for licensed music are named this way, and it
 * reaches the player as the artist on anything played from one.
 */
fun String.artistForLyricsSearch(): String =
    removeSuffix(" - Topic").trim().ifBlank { trim() }

private val WHITESPACE = Regex("""\s+""")

private val CREDITS = listOf(
    // Bracketed credits: (feat. X), [ft. X], (with X).
    Regex("""\s*[(\[]\s*(feat|ft|featuring|with)\b[^)\]]*[)\]]""", RegexOption.IGNORE_CASE),
    // The same, unbracketed and running to the end of the title.
    Regex("""\s+(feat|ft|featuring)\.?\s+.*$""", RegexOption.IGNORE_CASE),
    // How the upload was labelled, not what was recorded.
    Regex(
        """\s*[(\[]\s*(official\s*)?(music\s*)?""" +
            """(video|audio|visuali[sz]er|lyrics?\s*video|lyrics?|m/?v|hd|hq|4k|full\s*song)""" +
            """\s*[)\]]""",
        RegexOption.IGNORE_CASE,
    ),
    Regex("""\s*[(\[]\s*official\s*[)\]]""", RegexOption.IGNORE_CASE),
    // The same label unbracketed after a dash, to the end: "Naatu Naatu - Official Video", "Hukum – Official Lyric Video".
    Regex(
        """\s+[-–—]\s*(official\s*)?(music\s*)?(full\s*)?""" +
            """(video(\s*song)?|audio(\s*song)?|visuali[sz]er|lyrics?\s*video|lyrical(\s*video)?|m/?v|full\s*song)\s*$""",
        RegexOption.IGNORE_CASE,
    ),
    Regex("""\s+[-–—]\s*official\s*(music|lyric)?\s*video\s*$""", RegexOption.IGNORE_CASE),
)
