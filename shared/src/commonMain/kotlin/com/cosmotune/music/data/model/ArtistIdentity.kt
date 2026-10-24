package com.cosmotune.music.data.model

/** Structured credits are authoritative; punctuation in a known artist name is not a collaboration. */
object ArtistIdentity {
    val protectedNames = setOf(
        "AC/DC", "Tyler, the Creator", "Earth, Wind & Fire", "Simon & Garfunkel", "Hall & Oates",
        "Florence + the Machine", "Of Monsters and Men", "Crosby, Stills, Nash & Young",
        "Coheed and Cambria", "Nick Cave & the Bad Seeds", "Emerson, Lake & Palmer",
        "Peter, Paul and Mary", "Love and Rockets", "A Tribe Called Quest",
    )
    // Japanese ・ joins a transliterated name; Japanese 、 separates two credits.
    private val separators = Regex("""\s*[,/&;·|、，×]\s*|\s+x\s+|\s+(?:feat\.?|ft\.?|featuring)\s+""", RegexOption.IGNORE_CASE)
    private val protected = protectedNames.sortedByDescending { it.length }.map {
        Regex("(?<![\\p{L}\\p{N}])${Regex.escape(it)}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
    }

    fun names(display: String, structured: List<ArtistRef> = emptyList()): List<String> {
        val authoritative = structured.map { it.name.trim() }.filter { it.isNotEmpty() }.distinct()
        if (authoritative.isNotEmpty()) return authoritative
        val names = ArrayList<String>()
        var masked = display
        protected.forEach { pattern -> masked = pattern.replace(masked) { match ->
            val index = names.size; names += match.value
            "\uE000$index\uE001"
        } }
        return masked.split(separators).map { part ->
            Regex("\uE000([0-9]+)\uE001").replace(part) { names.getOrNull(it.groupValues[1].toIntOrNull() ?: -1) ?: it.value }.trim()
        }.filter { it.isNotEmpty() }.distinct()
    }

    fun primaryName(display: String, structured: List<ArtistRef> = emptyList()): String? = names(display, structured).firstOrNull()
}
