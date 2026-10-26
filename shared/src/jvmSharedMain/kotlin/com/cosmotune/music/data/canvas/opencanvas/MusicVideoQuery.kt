package com.opencanvas.core.stream

import org.jsoup.parser.Parser

/** Keep a film's identity before removing catalogue packaging from a song title. */
internal data class MusicVideoQuery(val title: String, val album: String?) {
    companion object {
        private val from = Regex("""[\(\[]\s*from\s+([^\)\]]+)[\)\]]""", RegexOption.IGNORE_CASE)
        private val noise = Regex(
            """\((?:from|official|lyrical|video|audio)[^)]*\)|\[[^]]*]|""" +
                """\b(?:official (?:video|audio|music video)|lyrical|full song|4k video)\b""",
            RegexOption.IGNORE_CASE,
        )

        fun from(title: String, album: String?): MusicVideoQuery {
            val decoded = Parser.unescapeEntities(title, false)
            val film = from.find(decoded)?.groupValues?.get(1)
                ?.trim()?.trim('"', '\'', '“', '”', '‘', '’')?.trim()?.takeIf(String::isNotEmpty)
                ?: decoded.split('|').drop(1).firstNotNullOfOrNull { part ->
                    Regex("""^\s*(.+?)\s+(?:movie|film)\s+songs?\s*$""", RegexOption.IGNORE_CASE)
                        .matchEntire(part)?.groupValues?.get(1)?.trim()
                }
            val clean = decoded.replace(noise, " ").substringBefore(" | ")
                .replace(Regex("\\s+"), " ").trim().ifBlank { decoded }
            return MusicVideoQuery(clean, film ?: album?.trim()?.takeIf(String::isNotEmpty))
        }
    }
}
