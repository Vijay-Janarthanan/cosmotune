package com.cosmotune.providers

import com.cosmotune.music.data.recommendations.*
import java.time.LocalDate
import java.util.Locale
import kotlinx.serialization.json.*

/** Reads stated recording metadata only; neither the requested locale nor artist names establish its language. */
internal object ProviderDiscoveryMetadata {
    fun candidate(track: ProviderTrack, rank: Int): MusicCandidate {
        fun JsonObject.text(vararg keys: String) = keys.firstNotNullOfOrNull { (get(it) as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
        val raw = track.raw
        val album = raw["album"] as? JsonObject
        val language = language(raw.text("language", "language_code", "languageCode") ?: album?.text("language", "language_code"))
        val date = (raw.text("release_date", "releaseDate", "released_at") ?: album?.text("release_date", "releaseDate"))
            ?.let { value -> runCatching { LocalDate.parse(value.take(10)) }.getOrNull() }
        return MusicCandidate(track.song(), MusicCandidateOrigin.PROVIDER, rank, language, date,
            raw.text("isrc", "ISRC"), track.provider,
            "${track.provider} catalogue" + if (language != null) " · language stated by provider" else " · language not supplied")
    }
    private fun language(value: String?): String? {
        val clean = value?.trim()?.lowercase(Locale.ROOT) ?: return null
        DiscoveryPreferences.languages.firstOrNull { it.first == clean || it.second.lowercase(Locale.ROOT) == clean }?.let { return it.first.takeIf(String::isNotBlank) }
        return clean.takeIf { it.matches(Regex("[a-z]{2,3}(?:-[a-z0-9]{2,8})?")) }
    }
}
