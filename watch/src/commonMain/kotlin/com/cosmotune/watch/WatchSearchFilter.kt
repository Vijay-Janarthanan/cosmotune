package com.cosmotune.watch

import com.cosmotune.watch.model.TitleType

data class WatchSearchFilter(val type: TitleType? = null, val genre: String? = null, val year: Int? = null) {
    fun apply(results: SearchResults): SearchResults {
        fun matches(item: com.cosmotune.watch.model.TitleSummary) =
            (type == null || item.type == type) && (year == null || item.year == year) &&
                (genre == null || item.genres.any { it.equals(genre, true) })
        return results.copy(films = results.films.filter(::matches), series = results.series.filter(::matches),
            channels = if (type == null && genre == null && year == null) results.channels else emptyList())
    }
}
