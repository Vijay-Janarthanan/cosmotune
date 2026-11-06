package com.cosmotune.watch

import com.cosmotune.music.data.recommendations.DiscoveryPreferences
import com.cosmotune.watch.model.Channel
import java.util.Locale

/**
 * Where the viewer is and the language they choose music in (Music's discovery preferences, else the device's locale): Watch
 * ranks channels and the Live TV chips of that country and language first. [country] is ISO alpha-2 lower case ("in"), empty
 * for none (Global); [language] an ISO 639 code ("ta"), empty for none.
 */
data class WatchPlace(val country: String, val language: String) {
    companion object {
        val NONE = WatchPlace("", "")

        fun current(): WatchPlace {
            val region = runCatching { DiscoveryPreferences.state.value }.getOrNull()
            val country = (region?.country ?: Locale.getDefault().country).let { if (it == "ZZ") "" else it }.lowercase().let { if (it == "uk") "gb" else it }
            val language = region?.musicLanguage?.takeIf { it.isNotBlank() }?.substringBefore('-')?.lowercase().orEmpty()
            return WatchPlace(country, language)
        }
    }

    fun isCountry(code: String): Boolean = country.isNotEmpty() && code.lowercase().let { if (it == "uk") "gb" else it } == country
    fun isLanguage(code: String): Boolean = language.isNotEmpty() && LanguageCodes.same(code, language)
}

/** How Watch orders channels: the viewer's country and language first, then the channels most sources carry well, never by name first. */
object ChannelRank {
    /** 0 for the viewer's country in their language, 1 their country, 2 their language elsewhere, 3 the rest. */
    fun local(channel: Channel, place: WatchPlace): Int {
        val country = channel.countries.any(place::isCountry)
        val language = channel.languages.any(place::isLanguage)
        return when {
            country && language -> 0
            country -> 1
            language -> 2
            else -> 3
        }
    }

    /**
     * A stand-in for popularity, higher first (no source publishes viewing figures): carried by more sources, by a curated free
     * service rather than a public list, with a logo and a guide, on all day and everywhere, and named in words rather than
     * symbols (".black", "+TV").
     */
    fun popularity(copies: List<Channel>): Int {
        val first = copies.firstOrNull() ?: return 0
        var score = copies.size * 4
        if (copies.any { it.pluginId != "iptv" }) score += 3
        if (copies.any { it.logoUrl != null }) score += 1
        if (copies.any { it.epgId != null }) score += 1
        if (copies.all { c -> c.labels.any { it.equals("Not 24/7", true) || it.equals("Geo-blocked", true) } }) score -= 4
        if (first.name.firstOrNull()?.isLetterOrDigit() != true) score -= 3
        return score
    }

    /** Favourites, then [local], then [popularity], then the channel number and the name. */
    fun order(favourites: Set<String>, place: WatchPlace, copies: (Channel) -> List<Channel>): Comparator<Channel> =
        compareBy<Channel>({ it.key.value !in favourites }, { local(it, place) }, { -popularity(copies(it)) }, { it.number ?: Int.MAX_VALUE }, { it.name.lowercase() })
}
