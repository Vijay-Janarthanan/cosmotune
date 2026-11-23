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
    /** What Watch search's [query] can ask of this provider (see [com.cosmotune.watch.plugin.WatchPlugin.queryAbility]). */
    suspend fun queryAbility(query: com.cosmotune.watch.WatchQuery, currentYear: Int): com.cosmotune.watch.QueryAbility =
        if (query.browsing || query.kind == com.cosmotune.watch.WatchKind.LIVE) com.cosmotune.watch.QueryAbility.SILENT else com.cosmotune.watch.QueryAbility(true)
    suspend fun find(query: com.cosmotune.watch.WatchQuery, currentYear: Int): List<TitleSummary> =
        if (query.browsing) emptyList() else search(query.trimmed)
    /** The metadata rows of Watch home for [type] (null = both): trending, popular, top rated, genres, regional. */
    suspend fun rows(type: TitleType?): List<HomeRow>
    suspend fun rowPage(rowId: String, skip: Int): List<TitleSummary>
    /** The canonical card for an IMDb id (TMDB `/find`), so add-on and server items join the TMDB keys. */
    suspend fun findByImdb(imdb: String, type: TitleType): TitleSummary?
    /** Best match by name and year (within one year), for plugins that only know names (Pluto, Archive, YouTube). */
    suspend fun findByName(name: String, year: Int?, type: TitleType): TitleSummary?
}
