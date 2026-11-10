package com.cosmotune.watch

import com.cosmotune.watch.model.Stream
import com.cosmotune.watch.model.StreamHint
import com.cosmotune.watch.model.StreamProtocol
import kotlin.math.abs

/**
 * Orders the "Play from" list, best first:
 * 1. the copy that matches [StreamHint] (the last release played, or the room's): same file name, else same size, else
 *    same binge group from the same plugin, else a length within two seconds;
 * 2. playable before "open elsewhere";
 * 3. the user's own servers and folders, then the built-in free plugins, then add-ons;
 * 4. the tallest picture up to [maxHeight] (what the device decodes), taller ones after;
 * 5. no ads before ads, then the preferred audio language.
 */
class StreamRanker(
    private val maxHeight: Int = 2160,
    private val preferredAudio: List<String> = emptyList(),
    /**
     * Plugin id to rank, lower first; built from the Plugins page order. Ranks below 100 are the user's own servers and
     * folders, which win over any free copy; among the rest the picture decides first and the rank breaks ties.
     */
    private val pluginRank: (String) -> Int = { 0 },
) {
    fun rank(streams: List<Stream>, hint: StreamHint? = null): List<Stream> =
        streams.distinctBy { it.url to it.torrentFileIndex }.sortedWith(
            compareBy<Stream>({ -hintScore(it, hint) }, { if (it.isPlayable) 0 else 1 }, { it.isTorrent }, { pluginRank(it.source.pluginId) / 100 },
                { heightKey(it.height) }, { pluginRank(it.source.pluginId) }, { if (it.hasAds) 1 else 0 }, { audioKey(it) }, { if (it.protocol == StreamProtocol.HLS) 0 else 1 }),
        )

    private fun heightKey(height: Int?): Int = when {
        height == null -> 10_000
        height <= maxHeight -> maxHeight - height
        else -> 5_000 + height
    }

    private fun audioKey(stream: Stream): Int {
        if (preferredAudio.isEmpty()) return 0
        val index = preferredAudio.indexOfFirst { lang -> stream.audioLanguages.any { LanguageCodes.same(it, lang) } }
        return if (index < 0) preferredAudio.size else index
    }

    companion object {
        /** 4 = same file name, 3 = same size, 2 = same binge group and plugin, 1 = length within 2 s, 0 = no match. */
        fun hintScore(stream: Stream, hint: StreamHint?): Int {
            if (hint == null) return 0
            if (hint.filename != null && stream.filename != null && hint.filename.equals(stream.filename, ignoreCase = true)) return 4
            if (hint.sizeBytes != null && hint.sizeBytes == stream.sizeBytes) return 3
            if (hint.bingeGroup != null && hint.bingeGroup == stream.bingeGroup && (hint.pluginId == null || hint.pluginId == stream.source.pluginId)) return 2
            if (hint.durationMs != null && stream.durationMs != null && abs(hint.durationMs - stream.durationMs) <= 2_000) return 1
            return 0
        }
    }
}

/** ISO 639-1/2/3 matching for the few languages people pick; the rest compare as written. */
object LanguageCodes {
    private val groups = listOf(
        setOf("en", "eng", "english"), setOf("hi", "hin", "hindi"), setOf("ta", "tam", "tamil"), setOf("te", "tel", "telugu"),
        setOf("ml", "mal", "malayalam"), setOf("kn", "kan", "kannada"), setOf("bn", "ben", "bengali"), setOf("mr", "mar", "marathi"),
        setOf("es", "spa", "spanish"), setOf("fr", "fra", "fre", "french"), setOf("de", "deu", "ger", "german"),
        setOf("it", "ita", "italian"), setOf("pt", "por", "portuguese", "pob", "pt-br"), setOf("ja", "jpn", "japanese"),
        setOf("ko", "kor", "korean"), setOf("zh", "zho", "chi", "chinese"), setOf("ar", "ara", "arabic"), setOf("ru", "rus", "russian"),
        setOf("nl", "nld", "dut", "dutch"), setOf("tr", "tur", "turkish"), setOf("pl", "pol", "polish"), setOf("sv", "swe", "swedish"),
        setOf("pa", "pan", "punjabi"), setOf("gu", "guj", "gujarati"), setOf("or", "ori", "ory", "odia"), setOf("as", "asm", "assamese"), setOf("ur", "urd", "urdu"),
    )

    fun normalise(code: String): String {
        val lower = code.trim().lowercase()
        return groups.firstOrNull { lower in it || lower.substringBefore('-') in it }?.first() ?: lower.substringBefore('-')
    }

    fun same(a: String, b: String) = normalise(a) == normalise(b)
}
