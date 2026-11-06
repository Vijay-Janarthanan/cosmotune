package com.cosmotune.watch.plugins.free

import com.cosmotune.watch.http.WatchHttp
import com.cosmotune.watch.http.child
import com.cosmotune.watch.http.int
import com.cosmotune.watch.http.list
import com.cosmotune.watch.http.long
import com.cosmotune.watch.http.str
import com.cosmotune.watch.http.urlEncode
import com.cosmotune.watch.meta.NameMatch
import com.cosmotune.watch.model.Episode
import com.cosmotune.watch.model.PlayTarget
import com.cosmotune.watch.model.Season
import com.cosmotune.watch.model.Stream
import com.cosmotune.watch.model.StreamProtocol
import com.cosmotune.watch.model.StreamSource
import com.cosmotune.watch.model.Title
import com.cosmotune.watch.model.TitleSummary
import com.cosmotune.watch.model.TitleType
import com.cosmotune.watch.model.WatchKey
import com.cosmotune.watch.plugin.CatalogDef
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement

/**
 * Pluto TV's free on-demand films and series. A session comes from Pluto's public boot service (no account); the catalogue
 * is its VOD categories; a title plays from Pluto's HLS stitcher. Items are keyed `m:pluto:<id>` / `s:pluto:<id>` and
 * matched to TMDB titles by name.
 */
class PlutoVod(
    private val http: WatchHttp,
    private val pluginId: String,
    private val clientId: String,
    private val bootBase: String = "https://boot.pluto.tv",
    private val vodBase: String = "https://service-vod.clusters.pluto.tv",
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Session(val token: String, val stitcher: String, val params: String, val atMs: Long)
    private val lock = Mutex()
    private var session: Session? = null
    private val source = StreamSource(pluginId, "Pluto TV", "PLUTO")

    private suspend fun session(): Session = lock.withLock {
        session?.takeIf { now() - it.atMs < 3 * WatchHttp.HOUR }?.let { return it }
        val e = http.getJson("$bootBase/v4/start?appName=web&appVersion=9.0.0&deviceVersion=120.0.0&deviceModel=web&deviceMake=chrome" +
            "&deviceType=web&clientID=${clientId.urlEncode()}&clientModelNumber=1.0.0&serverSideAds=false", ttlMs = 0)
        Session(e.str("sessionToken") ?: error("Pluto TV gave no session"), e.child("servers").str("stitcher") ?: "https://cfd-v4-service-channel-stitcher-use1-1.prd.pluto.tv",
            e.str("stitcherParams").orEmpty(), now()).also { session = it }
    }

    /** A live channel's stream on Pluto's own stitcher, with this device's session. */
    suspend fun channelStream(channelId: String): String {
        val s = session()
        return "${s.stitcher}/v2/stitch/hls/channel/${channelId.urlEncode()}/master.m3u8?${s.params}&jwt=${s.token.urlEncode()}&masterJWTPassthrough=true"
    }

    private suspend fun auth() = mapOf("Authorization" to "Bearer ${session().token}")

    private suspend fun categories(): List<JsonElement> =
        http.getJson("$vodBase/v4/vod/categories?includeItems=true&offset=1000&page=1&sort=number:asc", auth(), 6 * WatchHttp.HOUR).list("categories")

    suspend fun catalogs(): List<CatalogDef> = categories().take(12).mapNotNull { c ->
        val cid = c.str("_id") ?: c.str("id") ?: return@mapNotNull null
        CatalogDef(pluginId, cid, c.str("name") ?: "Pluto TV", null, pageSize = 40)
    }

    suspend fun page(catalog: CatalogDef, skip: Int): List<TitleSummary> =
        categories().firstOrNull { (it.str("_id") ?: it.str("id")) == catalog.id }?.list("items").orEmpty().drop(skip).take(catalog.pageSize).mapNotNull(::summaryOf)

    suspend fun search(query: String): List<TitleSummary> {
        val q = NameMatch.normalise(query)
        return categories().flatMap { it.list("items") }.filter { NameMatch.normalise(it.str("name").orEmpty()).contains(q) }
            .mapNotNull(::summaryOf).distinctBy { it.key }.take(30)
    }

    private fun cover(e: JsonElement, ratio: String): String? = e.list("covers").firstOrNull { it.str("aspectRatio") == ratio }?.str("url")
        ?: e.list("covers").firstOrNull()?.str("url")

    fun summaryOf(e: JsonElement): TitleSummary? {
        val itemId = e.str("_id") ?: e.str("id") ?: return null
        val type = when (e.str("type")) { "movie" -> TitleType.FILM; "series" -> TitleType.SERIES; else -> return null }
        return TitleSummary(if (type == TitleType.FILM) WatchKey.film(pluginId, itemId) else WatchKey.series(pluginId, itemId), type,
            e.str("name") ?: return null, null, posterUrl = cover(e, "347:500"), backdropUrl = cover(e, "16:9"),
            overview = e.str("description") ?: e.str("summary"), genres = listOfNotNull(e.str("genre")),
            runtimeMin = e.long("duration")?.let { (it / 60_000).toInt() }, origin = pluginId, badge = "FREE · PLUTO")
    }

    suspend fun title(key: WatchKey): Title? {
        val item = categories().flatMap { it.list("items") }.firstOrNull { (it.str("_id") ?: it.str("id")) == key.id } ?: return null
        val summary = summaryOf(item) ?: return null
        val seasons = if (summary.type == TitleType.SERIES) seasons(key.id).map { s ->
            Season(key, s.int("number") ?: 1, "Season ${s.int("number") ?: 1}", s.list("episodes").size)
        } else emptyList()
        return Title(summary, seasons = seasons, certification = item.str("rating"))
    }

    private suspend fun seasons(seriesId: String): List<JsonElement> =
        http.getJson("$vodBase/v4/vod/series/${seriesId.urlEncode()}/seasons?offset=1000&page=1", auth(), 6 * WatchHttp.HOUR).list("seasons")

    suspend fun episodes(series: WatchKey, season: Int): List<Episode> = seasons(series.id).firstOrNull { it.int("number") == season }?.list("episodes").orEmpty()
        .mapIndexedNotNull { i, ep ->
            val n = ep.int("number") ?: (i + 1)
            Episode(WatchKey.episode(pluginId, series.id, season, n), series, season, n, ep.str("name") ?: "Episode $n", ep.str("description"),
                cover(ep, "16:9"), null, ep.long("duration")?.let { (it / 60_000).toInt() })
        }

    suspend fun streams(target: PlayTarget): List<Stream> {
        val episodeId = when (target) {
            is PlayTarget.Film -> if (target.key.namespace == pluginId) target.key.id else findByName(target.name, TitleType.FILM)
            is PlayTarget.EpisodeOf -> {
                val series = if (target.key.namespace == pluginId) target.key.id else findByName(target.seriesName, TitleType.SERIES)
                series?.let { sid -> seasons(sid).firstOrNull { it.int("number") == target.season }?.list("episodes")
                    ?.firstOrNull { (it.int("number") ?: -1) == target.episode }?.let { it.str("_id") ?: it.str("id") } }
            }
            else -> null
        } ?: return emptyList()
        val s = session()
        val url = "${s.stitcher}/v2/stitch/hls/episode/${episodeId.urlEncode()}/master.m3u8?${s.params}&jwt=${s.token.urlEncode()}&masterJWTPassthrough=true"
        return listOf(Stream(source, "Free with ads", "Pluto TV", url, StreamProtocol.HLS, hasAds = true))
    }

    private suspend fun findByName(name: String, type: TitleType): String? =
        search(name).firstOrNull { it.type == type && NameMatch.same(it.name, name) }?.key?.id
}
