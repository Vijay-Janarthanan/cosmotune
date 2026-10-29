package com.cosmotune.video.local

/**
 * A subtitle file found beside a video, or picked for it: its [fileName], the [language] its name gives (`und` when it gives none)
 * and the [label] the captions list shows.
 */
data class SubtitleMatch(val fileName: String, val language: String, val label: String)

/** Finding the subtitle files that belong to a video file by their names (pure; the caller lists the folder). */
object LocalSubtitles {
    /** The language of a subtitle whose name says none. */
    const val UNDETERMINED = "und"

    /**
     * The subtitle files among [directoryFiles] (names, no folders) that belong to the video called [videoFileName]: `clip.srt`,
     * `clip.en.srt`, `clip.pt-BR.vtt`; the video's name is compared without its extension and without regard to case. A name with
     * something else after the video's name (`clip.forced.srt` is read as the language `forced`, a tag of two to eight letters) is
     * still the video's. The plain one comes first, then by name; only the first file of a language is kept.
     */
    fun siblings(videoFileName: String, directoryFiles: List<String>): List<SubtitleMatch> {
        val base = LocalVideo.stem(videoFileName)
        if (base.isEmpty()) return emptyList()
        val found = ArrayList<SubtitleMatch>()
        for (name in directoryFiles.sorted()) {
            if (!LocalVideo.isSubtitleName(name)) continue
            val stem = LocalVideo.stem(name)
            if (stem.equals(base, ignoreCase = true)) {
                found.add(0, SubtitleMatch(name, UNDETERMINED, labelFor(UNDETERMINED)))
                continue
            }
            if (stem.length <= base.length + 1 || !stem.regionMatches(0, base, 0, base.length, ignoreCase = true) || stem[base.length] != '.') continue
            val tag = stem.substring(base.length + 1).substringBefore('.')
            if (!isLanguageTag(tag)) continue
            val language = normalizeTag(tag)
            found.add(SubtitleMatch(name, language, labelFor(language)))
        }
        // The plain file was put first; keep one file for each language.
        val seen = HashSet<String>()
        return found.filter { seen.add(it.language) }
    }

    /** The match for a subtitle file the person picked: its language from its name (`movie.en.srt` gives `en`), else [UNDETERMINED]. */
    fun forPickedFile(fileName: String): SubtitleMatch {
        val tag = LocalVideo.stem(fileName).substringAfterLast('.', "")
        val language = if (isLanguageTag(tag)) normalizeTag(tag) else UNDETERMINED
        return SubtitleMatch(fileName, language, labelFor(language, fallback = fileName))
    }

    /** `en` or `eng` or `pt-BR` or `zh_Hant`: letters in groups of two to eight, joined by `-` or `_`. */
    internal fun isLanguageTag(tag: String): Boolean {
        if (tag.isEmpty()) return false
        val groups = tag.split('-', '_')
        return groups.size <= 3 && groups.all { it.length in 2..8 && it.all { c -> c.isLetter() } }
    }

    /** The tag the way the player's engines compare it: language lower case, `_` as `-`. */
    internal fun normalizeTag(tag: String): String = tag.replace('_', '-').let { value ->
        val head = value.substringBefore('-').lowercase()
        val rest = value.substringAfter('-', "")
        if (rest.isEmpty()) head else "$head-$rest"
    }

    private fun labelFor(language: String, fallback: String = "Subtitles"): String = if (language == UNDETERMINED) fallback else language
}
