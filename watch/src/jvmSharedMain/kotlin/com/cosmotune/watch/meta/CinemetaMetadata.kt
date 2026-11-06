package com.cosmotune.watch.meta

import com.cosmotune.watch.HomeRow
import com.cosmotune.watch.MetadataSource
import com.cosmotune.watch.http.WatchHttp
import com.cosmotune.watch.model.Episode
import com.cosmotune.watch.model.Title
import com.cosmotune.watch.model.TitleSummary
import com.cosmotune.watch.model.TitleType
import com.cosmotune.watch.model.WatchKey
import com.cosmotune.watch.plugin.Capability
import com.cosmotune.watch.plugin.PluginKind
import com.cosmotune.watch.plugins.stremio.StremioClient
import com.cosmotune.watch.plugins.stremio.StremioPlugin
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** The no-key fallback: Stremio's Cinemeta (IMDb ids). Keys are `m:imdb:tt...` / `s:imdb:tt...`. */
class CinemetaMetadata(http: WatchHttp, manifestUrl: String = "https://v3-cinemeta.strem.io/manifest.json") : MetadataProvider {
    override val source = MetadataSource.FALLBACK
    private val addon = StremioPlugin("cinemeta", "Cinemeta", "IMDB", StremioClient(http, manifestUrl),
        setOf(Capability.CATALOG, Capability.SEARCH, Capability.META), PluginKind.CINEMETA)

    override fun owns(key: WatchKey) = key.namespace == "imdb"
    override suspend fun title(key: WatchKey): Title? = addon.title(key)
    override suspend fun episodes(series: WatchKey, season: Int): List<Episode> = addon.episodes(series, season)
    override suspend fun search(query: String): List<TitleSummary> = addon.search(query, null)

    override suspend fun rows(type: TitleType?): List<HomeRow> = coroutineScope {
        val rows = buildList {
            if (type != TitleType.SERIES) add(Triple("cinemeta:movie/top", "Popular films", TitleType.FILM))
            if (type != TitleType.FILM) add(Triple("cinemeta:series/top", "Popular series", TitleType.SERIES))
        }
        rows.map { (id, name, _) -> async { runCatching { HomeRow(id, name, rowPage(id, 0)) }.getOrNull() } }.awaitAll().filterNotNull()
    }

    override suspend fun rowPage(rowId: String, skip: Int): List<TitleSummary> {
        if (!rowId.startsWith("cinemeta:")) return emptyList()
        val catalog = rowId.removePrefix("cinemeta:")
        val type = if (catalog.startsWith("movie")) TitleType.FILM else TitleType.SERIES
        return addon.catalogPage(com.cosmotune.watch.plugin.CatalogDef("cinemeta", catalog, "", type), skip)
    }

    override suspend fun findByImdb(imdb: String, type: TitleType): TitleSummary? =
        addon.title(if (type == TitleType.FILM) WatchKey.film("imdb", imdb) else WatchKey.series("imdb", imdb))?.summary

    override suspend fun findByName(name: String, year: Int?, type: TitleType): TitleSummary? =
        addon.search(name, type).firstOrNull { NameMatch.same(it.name, name) && (year == null || it.year == null || kotlin.math.abs(it.year - year) <= 1) }
}
