package com.cosmotune.podio

import kotlinx.serialization.Serializable

/** A song a station named while it played (its ICY title), where and when. */
@Serializable data class PodioHeardSong(val title: String, val station: String, val stationId: String, val atMs: Long)

/** What was listened to on the radio (RadioDroid's history): the stations played lately and the songs they named. */
object PodioHistory {
    const val STATIONS = 30
    const val SONGS = 200

    /** [station] at the front of the recent stations. */
    fun played(saved: PodioSaved, station: RadioStation): PodioSaved =
        saved.copy(recentStations = (listOf(station) + saved.recentStations.filterNot { it.id == station.id }).take(STATIONS))

    /** [title] at the front of the songs heard, unless it is the song this station named last. */
    fun heard(saved: PodioSaved, title: String, station: RadioStation, nowMs: Long): PodioSaved {
        val clean = title.trim().take(200).takeIf { it.length >= 2 && !it.equals(station.name, ignoreCase = true) } ?: return saved
        if (saved.heardSongs.firstOrNull { it.stationId == station.id }?.title == clean) return saved
        return saved.copy(heardSongs = (listOf(PodioHeardSong(clean, station.name, station.id, nowMs)) + saved.heardSongs).take(SONGS))
    }

    fun clear(saved: PodioSaved): PodioSaved = saved.copy(heardSongs = emptyList(), recentStations = emptyList())

    /** The songs heard as plain lines ("Artist - Song · Station"), for copying out. */
    fun songsText(songs: List<PodioHeardSong>): String = songs.joinToString("\n") { "${it.title} · ${it.station}" }
}
