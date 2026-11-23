package com.cosmotune.podio

/**
 * What Podio offers in the car (Android Auto) and other media browsers: "For you" (episodes started and not finished, then Up next,
 * then favourites not yet heard), the saved stations, and the stations played lately. AntennaPod's "For you" in the car.
 */
object PodioCarBrowse {
    const val MAX = 40

    fun forYou(saved: PodioSaved, queue: PodioQueueState): List<PodioItem> {
        fun started(entry: PodioQueueEntry): Boolean {
            val position = saved.progress[entry.id] ?: return false
            return position > 0 && entry.id !in saved.completedEpisodes &&
                !PodioPlaybackPolicy.isFinished(position, entry.episode.durationSeconds * 1000)
        }
        val all = listOfNotNull(queue.current) + queue.entries + saved.favouriteEpisodes
        val inProgress = all.filter(::started)
        val unheardFavourites = saved.favouriteEpisodes.filter { it.id !in saved.completedEpisodes }
        return (inProgress + queue.entries + unheardFavourites).distinctBy { it.id }.take(MAX).map { it.episode.item() }
    }

    fun stations(saved: PodioSaved): List<PodioItem> = saved.stations.filterNot { it.removed }.take(MAX).map { it.item() }

    fun recentStations(saved: PodioSaved): List<PodioItem> =
        saved.recentStations.filter { recent -> saved.stations.none { it.id == recent.id } }.take(MAX).map { it.item() }

    /** Where a podcast episode that a car (or another app) starts from the beginning was left, or 0. */
    fun resumeAt(saved: PodioSaved, id: String, durationMs: Long): Long {
        if (!id.startsWith("podio:episode:") || id in saved.completedEpisodes) return 0
        val position = saved.progress[id]?.coerceAtLeast(0) ?: return 0
        return if (PodioPlaybackPolicy.isFinished(position, durationMs)) 0 else position
    }
}
