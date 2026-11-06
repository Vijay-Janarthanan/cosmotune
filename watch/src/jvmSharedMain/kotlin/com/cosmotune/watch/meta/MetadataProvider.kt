package com.cosmotune.watch.meta

import com.cosmotune.watch.HomeRow
import com.cosmotune.watch.MetadataSource
import com.cosmotune.watch.model.Episode
import com.cosmotune.watch.model.Title
import com.cosmotune.watch.model.TitleSummary
import com.cosmotune.watch.model.TitleType
import com.cosmotune.watch.model.WatchKey

/** The metadata authority: TMDB with a key, otherwise Cinemeta (IMDb ids, no key). */
interface MetadataProvider {
    val source: MetadataSource
    /** Whether this provider answers for [key]'s namespace (`tmdb`, `imdb`). */
    fun owns(key: WatchKey): Boolean
    suspend fun title(key: WatchKey): Title?
    suspend fun episodes(series: WatchKey, season: Int): List<Episode>
    suspend fun search(query: String): List<TitleSummary>
    /** The metadata rows of Watch home for [type] (null = both): trending, popular, top rated, genres, regional. */
    suspend fun rows(type: TitleType?): List<HomeRow>
    suspend fun rowPage(rowId: String, skip: Int): List<TitleSummary>
    /** The canonical card for an IMDb id (TMDB `/find`), so add-on and server items join the TMDB keys. */
    suspend fun findByImdb(imdb: String, type: TitleType): TitleSummary?
    /** Best match by name and year (within one year), for plugins that only know names (Pluto, Archive, YouTube). */
    suspend fun findByName(name: String, year: Int?, type: TitleType): TitleSummary?
}
