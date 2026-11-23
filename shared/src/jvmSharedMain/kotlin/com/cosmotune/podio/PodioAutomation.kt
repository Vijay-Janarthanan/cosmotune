package com.cosmotune.podio

/**
 * What an automation app (Tasker, MacroDroid, Shortcuts) can ask Podio for, as Pocket Casts' automation does: play a saved station
 * by its name (or a station address), go on with the first of "For you", or pause. [pick] chooses what plays; the host plays it.
 */
object PodioAutomation {
    const val PLAY_STATION = "com.cosmotune.podio.PLAY_STATION"
    const val PLAY_FOR_YOU = "com.cosmotune.podio.PLAY_FOR_YOU"
    const val PAUSE = "com.cosmotune.podio.PAUSE"
    const val EXTRA_NAME = "name"
    const val EXTRA_URL = "url"

    /** What to play and from where: a station named [name] (saved first, then played lately; exact, then the start of the name). */
    fun station(saved: PodioSaved, name: String?, url: String?): RadioStation? {
        val wanted = name?.trim()?.takeIf { it.isNotEmpty() }
        val known = (saved.stations + saved.recentStations).distinctBy { it.id }
        if (wanted != null) {
            known.firstOrNull { it.name.equals(wanted, ignoreCase = true) }?.let { return it }
            known.firstOrNull { it.name.startsWith(wanted, ignoreCase = true) }?.let { return it }
            known.firstOrNull { it.name.contains(wanted, ignoreCase = true) }?.let { return it }
        }
        val address = url?.trim()?.takeIf { SyncPodio.portable(it) } ?: return null
        return known.firstOrNull { PodioLiveTitle.same(it.url, address) }
            ?: PodioStationLinks.station(wanted.orEmpty(), address)
    }

    /** The first of "For you" ([PodioCarBrowse]) and where it was left. */
    fun forYou(saved: PodioSaved, queue: PodioQueueState): Pair<PodioItem, Long>? =
        PodioCarBrowse.forYou(saved, queue).firstOrNull()?.let { it to PodioCarBrowse.resumeAt(saved, it.id, it.durationSeconds * 1000) }
}
