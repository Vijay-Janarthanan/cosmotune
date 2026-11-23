package com.cosmotune.podio

/** A song a station played, as it is scrobbled: who, what, when it began (epoch seconds) and how long it was on. */
data class PodioRadioListen(val artist: String, val title: String, val startedAtSeconds: Long, val seconds: Int)

/**
 * Scrobbling the songs a station names (RadioDroid's Last.fm scrobbling of radio tracks), off unless chosen in Settings › Podio.
 * A song counts once the station has moved on to the next one, if it was on for at least [MIN_SECONDS]; a title that is not
 * "Artist - Song" (a show's name, an advert) is never sent.
 */
class PodioRadioScrobble {
    private var current: Pair<String, Long>? = null
    private var station: String? = null

    /** [title] came on [stationId] at [nowMs]: the song before it, when it is one to scrobble. */
    fun heard(stationId: String, title: String?, nowMs: Long): PodioRadioListen? {
        val previous = current?.takeIf { station == stationId }
        if (previous?.first == title) return null
        current = title?.let { it to nowMs }
        station = stationId
        return previous?.let { (name, startedMs) -> listen(name, startedMs, nowMs) }
    }

    /** The station stopped or changed at [nowMs] ([stillOn] is the station playing now): the song that was on, if one to scrobble. */
    fun stopped(nowMs: Long, stillOn: String? = null): PodioRadioListen? {
        if (stillOn != null && stillOn == station) return null
        val previous = current
        current = null; station = null
        return previous?.let { (name, startedMs) -> listen(name, startedMs, nowMs) }
    }

    private fun listen(name: String, startedMs: Long, endedMs: Long): PodioRadioListen? {
        val seconds = ((endedMs - startedMs) / 1000).toInt()
        if (seconds < MIN_SECONDS) return null
        val (artist, song) = split(name) ?: return null
        return PodioRadioListen(artist, song, startedMs / 1000, seconds.coerceAtMost(MAX_SECONDS))
    }

    companion object {
        const val MIN_SECONDS = 30
        private const val MAX_SECONDS = 20 * 60

        /** "Artist - Song" (or with an en dash) into its two halves; null for anything else. */
        fun split(title: String): Pair<String, String>? {
            val parts = title.split(" - ", " – ", " — ", limit = 2).map { it.trim() }
            if (parts.size != 2 || parts.any { it.isEmpty() || it.length > 200 }) return null
            if (listOf("advert", "commercial", "jingle", "news", "station id").any { parts[1].equals(it, true) || parts[0].equals(it, true) }) return null
            return parts[0] to parts[1]
        }
    }
}
