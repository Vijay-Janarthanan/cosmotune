package com.cosmotune.podio

/**
 * Keeps the saved stations current (RadioDroid's favourites refresh): each one the directory still lists takes its newest stream
 * address and picture and says whether the directory's last check reached it; one it no longer lists is marked gone. A station
 * added by hand, and the name the listener gave one, stay as they are.
 */
object PodioStationCheck {
    /** At most once a day. */
    const val EVERY_MS = 24 * 60 * 60 * 1000L

    fun due(saved: PodioSaved, nowMs: Long): Boolean = saved.stations.any { !PodioStationLinks.isCustom(it) } && nowMs - saved.stationsCheckedMs >= EVERY_MS

    fun merge(saved: PodioSaved, fresh: List<RadioStation>, nowMs: Long): PodioSaved {
        val byId = fresh.associateBy { it.id }
        return saved.copy(stationsCheckedMs = nowMs, stations = saved.stations.map { mine ->
            if (PodioStationLinks.isCustom(mine)) return@map mine
            val now = byId[mine.id] ?: return@map mine.copy(removed = true)
            mine.copy(url = now.url, artwork = mine.artwork.ifBlank { now.artwork }, codec = now.codec, bitrate = now.bitrate, offline = now.offline, removed = false)
        })
    }

    /** What a saved station's line says about its state, or null when it is fine. */
    fun status(station: RadioStation): String? = when {
        station.removed -> "Gone from the directory"
        station.offline -> "Not answering lately"
        else -> null
    }
}
