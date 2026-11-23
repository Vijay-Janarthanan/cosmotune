package com.cosmotune.listening

/** The name a song's file is shared under: "Cosmotune - Artist - Title.ext", with nothing a file system refuses. */
object SongFileName {
    const val PREFIX = "Cosmotune - "

    fun of(artist: String, title: String, extension: String): String {
        fun clean(text: String) = text.replace(Regex("""[\\/:*?"<>|\u0000-\u001F]+"""), " ").replace(Regex("""\s+"""), " ").trim().take(80)
        val name = listOf(clean(artist), clean(title)).filter { it.isNotEmpty() }.joinToString(" - ").ifEmpty { "Song" }
        return PREFIX + name + "." + extension.lowercase().ifEmpty { "audio" }
    }

    /** The extension of [fileName] when it has a short one, else the one [mime] names, else "audio". */
    fun extension(fileName: String?, mime: String?): String {
        fileName?.substringAfterLast('.', "")?.takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) }?.let { return it.lowercase() }
        return when (mime?.lowercase()) {
            "audio/mpeg" -> "mp3"
            "audio/flac", "audio/x-flac" -> "flac"
            "audio/mp4", "audio/m4a", "audio/x-m4a", "audio/aac" -> "m4a"
            "audio/ogg" -> "ogg"
            "audio/opus" -> "opus"
            "audio/wav", "audio/x-wav" -> "wav"
            "audio/webm" -> "webm"
            else -> "audio"
        }
    }
}
