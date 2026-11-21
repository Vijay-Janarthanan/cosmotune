package com.cosmotune.music.data.lyrics

import java.text.Normalizer

/**
 * A lyrics source that hands back the video's title as the first line of the lyrics.
 *
 * BetterLyrics' Portato endpoint (QQ Music's timings) answers some songs with the upload's title as the opening line, "Lantern
 * Season Live at the Harbour - Mira and the Coast ...", sung over the intro as if it were a lyric. This takes such a line off the very
 * start, and only there. The rule is narrow so that real lyrics survive, including a line that sings the title: a line goes only
 * when it is the title itself, the artist and the title together, or the title with a video header such as "(Official Video)",
 * or when it is nothing but such a header.
 *
 * Some files also open with who wrote the song, timed as if sung: the writers' names run together with slashes ("Andrew Watt/Ali
 * Tamposi", QQ Music's files) or a credit's own label ("Lyrics by: …", "作词：…"). Those go too, from the start only (an idea from
 * AETHER 3.0).
 */
object LyricTitleHeader {
    /** How many lines at the start are looked at: a header and the credit lines under it (words, music, arrangement). */
    private const val LOOK = 3

    private val header = Regex(
        """[(\[【「]?\s*(official|lyrics?|music|audio|live|hd|4k|mv|m/v)\b[^)\]】」]*(video|audio|mv|visuali[sz]er|version|clip)?\s*[)\]】」]?""",
        RegexOption.IGNORE_CASE,
    )
    private val headerOnly = Regex(
        """^\W*(official\s+)?(music\s+|lyrics?\s+)?(video|audio|mv|m/v|visuali[sz]er)\W*$""",
        RegexOption.IGNORE_CASE,
    )
    private val bracketed = Regex("""[(\[【][^)\]】]*[)\]】]""")

    /** [lines] without a title header at the start, for a track called [title] by [artist]. */
    fun strip(lines: List<LyricLine>, title: String, artist: String): List<LyricLine> {
        var drop = 0
        var seen = 0
        for (line in lines) {
            if (line.isGap || line.text.isBlank()) { drop++; continue }
            if (seen >= LOOK || !(isHeader(line.text, title, artist) || isCredit(line.text))) break
            seen++
            drop++
        }
        if (seen == 0) return lines
        // Keep any break that was in front of the header; drop only the headers themselves.
        return lines.filterIndexed { index, line -> index >= drop || line.isGap || line.text.isBlank() }
    }

    /** Whether [text] is the track's title as a header rather than a lyric. */
    fun isHeader(text: String, title: String, artist: String): Boolean {
        val raw = text.trim()
        if (headerOnly.matches(raw)) return true
        val line = normal(raw)
        val name = normal(title.replace(bracketed, " "))
        if (line.isEmpty() || name.length < 2) return false
        val singer = normal(artist.substringBefore(',').substringBefore('&').substringBefore(" feat").substringBefore(" ft."))
        if (line == name) return true
        if (singer.isNotEmpty() && (line == "$singer $name" || line == "$name $singer")) return true
        if (!line.contains(name)) return false
        if (singer.length >= 2 && line.contains(singer)) return true
        return bracketed.findAll(raw).any { header.containsMatchIn(it.value) } || Regex("""\s[-–—|]\s""").containsMatchIn(raw) && header.containsMatchIn(raw)
    }

    /**
     * Whether [text] credits who wrote the song rather than being sung: a credit's own label ("Written by: …", "作词：…"), or names
     * run together with slashes, at least one of them a whole name and every word a name's ("Mikky Ekko/Justin Parker", not
     * "Love/hate" or "Up/Down").
     */
    fun isCredit(text: String): Boolean {
        val raw = text.trim()
        if (raw.isEmpty()) return false
        if (creditLabel.matches(raw)) return true
        val names = raw.split('/').map { it.trim() }
        if (names.size < 2 || names.any { it.isEmpty() }) return false
        val words = names.map { name -> name.split(' ').filter { it.isNotEmpty() } }
        if (words.any { it.size !in 1..4 }) return false
        return words.any { it.size >= 2 } && words.all { name -> name.all(::isNameWord) }
    }

    /** A word of a name: it starts with a capital (or a letter of a script without capitals), with only a name's marks. */
    private fun isNameWord(word: String): Boolean {
        val first = word.first()
        if (!first.isLetter() || first.isLowerCase()) return false
        return word.all { it.isLetter() || it in ".'’-&" }
    }

    private val creditLabel = Regex(
        """^(?:lyrics(?:\s+by)?|lyricist|written\s+by|writers?|composed\s+by|composers?|music\s+by|produced\s+by|producers?|""" +
            """arranged\s+by|arrangers?|作词|作曲|编曲|作詞|編曲|词|曲|制作人|监制|작사|작곡|편곡)\s*[:：]\s*\S.*$""",
        RegexOption.IGNORE_CASE,
    )

    private fun normal(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase()
            .map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("")
            .split(' ').filter { it.isNotEmpty() }.joinToString(" ")
}
