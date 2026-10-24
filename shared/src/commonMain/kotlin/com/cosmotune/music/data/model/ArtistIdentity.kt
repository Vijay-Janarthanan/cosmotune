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
    @kotlin.concurrent.Volatile private var protected = patterns(protectedNames)

    private fun patterns(names: Collection<String>) = names.sortedByDescending { it.length }.map {
        Regex("(?<![\\p{L}\\p{N}])${Regex.escape(it)}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
    }

    /** The listener's own exceptions: names with a comma, ampersand or slash that are one artist. */
    var userExceptions: List<String> = emptyList()
        private set

    fun setUserExceptions(names: List<String>) {
        val cleaned = names.map { it.trim() }.filter { it.length in 2..120 }.distinctBy { it.lowercase() }.take(200)
        userExceptions = cleaned
        protected = patterns(protectedNames + cleaned)
    }

    private val placeholders = setOf("", "<unknown>", "unknown", "unknown artist", "various", "various artists", "va", "artist", "on this computer")
    private val genericFolders = setOf("music", "downloads", "download", "audio", "songs", "media", "storage", "emulated", "0", "sdcard", "home", "users",
        "desktop", "documents", "android", "temp", "tmp", "appdata", "local", "library", "itunes", "itunes media", "my music", "public", "shared", "whatsapp", "telegram")

    fun isPlaceholder(name: String?): Boolean = name == null || name.trim().lowercase() in placeholders

    /**
     * Artist for a local file, best evidence first: the track's artist tag, its album-artist tag,
     * an "Artist - Title" file name, then an Artist/Album/track folder layout. Null when nothing
     * credible is known, so the caller can show its own placeholder.
     */
    fun localArtist(artistTag: String?, albumArtistTag: String?, fileName: String, folders: List<String>): String? {
        artistTag?.trim()?.takeUnless(::isPlaceholder)?.let { return it }
        albumArtistTag?.trim()?.takeUnless(::isPlaceholder)?.let { return it }
        fileName.substringBeforeLast('.').split(" - ", limit = 2).takeIf { it.size == 2 && it[1].isNotBlank() }
            ?.get(0)?.trim()?.takeUnless { isPlaceholder(it) || it.all(Char::isDigit) }?.let { return it }
        // Music/Artist/Album/01 Track.flac: a numbered track inside an album folder names its artist by the grandparent.
        if (folders.size < 2 || !Regex("""^\d{1,3}([ ._-]|$)""").containsMatchIn(fileName)) return null
        val album = folders.last().trim(); val artist = folders[folders.size - 2].trim()
        if (album.equals(artist, ignoreCase = true) || album.lowercase() in genericFolders) return null
        return artist.takeUnless { isPlaceholder(it) || it.lowercase() in genericFolders }
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
