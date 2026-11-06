package com.cosmotune.watch.plugins.iptv

import com.cosmotune.watch.http.WatchHttp
import com.cosmotune.watch.http.child
import com.cosmotune.watch.http.double
import com.cosmotune.watch.http.int
import com.cosmotune.watch.http.list
import com.cosmotune.watch.http.long
import com.cosmotune.watch.http.obj
import com.cosmotune.watch.http.str
import com.cosmotune.watch.http.strings
import com.cosmotune.watch.http.urlEncode
import com.cosmotune.watch.http.yearOf
import com.cosmotune.watch.meta.NameMatch
import com.cosmotune.watch.model.Catchup
import com.cosmotune.watch.model.Channel
import com.cosmotune.watch.model.EpgProgramme
import com.cosmotune.watch.model.ExternalIds
import com.cosmotune.watch.model.PlayTarget
import com.cosmotune.watch.model.Stream
import com.cosmotune.watch.model.StreamProtocol
import com.cosmotune.watch.model.StreamSource
import com.cosmotune.watch.model.TitleSummary
import com.cosmotune.watch.model.TitleType
import com.cosmotune.watch.model.WatchKey
import com.cosmotune.watch.plugin.CatalogDef
import com.cosmotune.watch.plugin.Capability
import com.cosmotune.watch.plugin.PluginKind
import com.cosmotune.watch.plugin.WatchPlugin
import com.cosmotune.watch.plugins.StreamGuess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * An Xtream Codes account (`player_api.php`): live channels with categories and the `xmltv.php` guide, films and series
 * matched to TMDB by their `tmdb` ids (or by name and year). The user name and password sit inside every stream address,
 * so no Xtream stream is ever public: none travels in a room.
 */
class XtreamPlugin(
    override val id: String,
    override val displayName: String,
    private val http: WatchHttp,
    server: String,
    private val user: String,
    private val password: String,
    private val guideCache: GuideCache = GuideCache(http),
) : WatchPlugin {
    override val kind = PluginKind.XTREAM
    override val badge = "XT"
    override val capabilities = setOf(Capability.LIVE, Capability.EPG, Capability.STREAMS, Capability.CATALOG, Capability.SEARCH)
    private val server = server.trim().trimEnd('/').let { if (it.contains("://")) it else "http://$it" }
    private val api = "${this.server}/player_api.php?username=${user.urlEncode()}&password=${password.urlEncode()}"
    private val source = StreamSource(id, displayName, badge)
    private val lock = Mutex()
    private var liveExt = "ts"
    private var live: List<Channel>? = null
    private var vod: List<JsonElement>? = null
    private var series: List<JsonElement>? = null

    suspend fun account(): JsonElement {
        val info = http.getJson(api, ttlMs = 0)
        val userInfo = info.child("user_info")
        require(userInfo != null && userInfo.int("auth") != 0) { "The server refused this user name and password" }
        require(userInfo.str("status")?.equals("Active", true) != false) { "This account is ${userInfo.str("status")}" }
        if ("m3u8" in userInfo.strings("allowed_output_formats")) liveExt = "m3u8"
        return info
    }

    private suspend fun call(action: String, extra: String = "", ttl: Long = 6 * WatchHttp.HOUR): JsonElement =
        http.getJson("$api&action=$action$extra", ttlMs = ttl)

    override suspend fun health(): String? = runCatching { account(); null }.getOrElse { it.message ?: "Server unreachable" }

    override suspend fun channels(): List<Channel> = lock.withLock {
        live?.let { return it }
        runCatching { account() }
        val categories = (call("get_live_categories") as? JsonArray).orEmpty().associate { (it.str("category_id") ?: "") to (it.str("category_name") ?: "") }
        val list = (call("get_live_streams") as? JsonArray).orEmpty().mapNotNull { s ->
            val streamId = s.long("stream_id") ?: return@mapNotNull null
            val epg = s.str("epg_channel_id")
            Channel(if (M3uParser.isIptvOrgId(epg)) WatchKey.channel("iptv", epg!!.substringBefore('@')) else WatchKey.channel(id, streamId.toString()),
                id, s.str("name") ?: "Channel $streamId", s.int("num"), s.str("stream_icon"),
                listOfNotNull(categories[s.str("category_id")]?.ifBlank { null }).ifEmpty { listOf("General") },
                epgId = epg ?: streamId.toString(), tvgId = streamId.toString(),
                catchup = if (s.int("tv_archive") == 1) Catchup("xc", s.int("tv_archive_duration") ?: 1) else null)
        }
        live = list
        list
    }

    override suspend fun guide(channels: List<Channel>, fromMs: Long, toMs: Long): List<EpgProgramme> {
        val ids = channels.mapNotNull { it.epgId }.toSet()
        val byChannel = runCatching { guideCache.programmes("${server}/xmltv.php?username=${user.urlEncode()}&password=${password.urlEncode()}") }.getOrNull() ?: return emptyList()
        return ids.flatMap { byChannel[it].orEmpty() }.filter { it.stopMs > fromMs && it.startMs < toMs }
    }

    override suspend fun catalogs(): List<CatalogDef> = listOf(
        CatalogDef(id, "vod", "Films on $displayName", TitleType.FILM, pageSize = 40),
        CatalogDef(id, "series", "Series on $displayName", TitleType.SERIES, pageSize = 40),
    )

    private suspend fun vodList() = lock.withLock { vod ?: (call("get_vod_streams") as? JsonArray).orEmpty().toList().also { vod = it } }
    private suspend fun seriesList() = lock.withLock { series ?: (call("get_series") as? JsonArray).orEmpty().toList().also { series = it } }

    private fun vodSummary(v: JsonElement, type: TitleType): TitleSummary? {
        val sid = (if (type == TitleType.FILM) v.long("stream_id") else v.long("series_id")) ?: return null
        val tmdb = v.str("tmdb")?.toIntOrNull() ?: v.str("tmdb_id")?.toIntOrNull()
        val name = v.str("name") ?: return null
        val key = when {
            tmdb != null && type == TitleType.FILM -> WatchKey.tmdbFilm(tmdb)
            tmdb != null -> WatchKey.tmdbSeries(tmdb)
            type == TitleType.FILM -> WatchKey.film(id, sid.toString())
            else -> WatchKey.series(id, sid.toString())
        }
        return TitleSummary(key, type, cleanName(name), yearOf(v.str("year") ?: v.str("releaseDate") ?: v.str("release_date")) ?: Regex("\\((\\d{4})\\)").find(name)?.groupValues?.get(1)?.toIntOrNull(),
            posterUrl = v.str("stream_icon") ?: v.str("cover"), rating = v.double("rating")?.takeIf { it > 0 }, ids = ExternalIds(tmdb = tmdb), origin = id)
    }

    override suspend fun catalogPage(catalog: CatalogDef, skip: Int): List<TitleSummary> = when (catalog.id) {
        "vod" -> vodList().sortedByDescending { it.long("added") ?: 0 }.drop(skip).take(catalog.pageSize).mapNotNull { vodSummary(it, TitleType.FILM) }
        "series" -> seriesList().sortedByDescending { it.long("last_modified") ?: 0 }.drop(skip).take(catalog.pageSize).mapNotNull { vodSummary(it, TitleType.SERIES) }
        else -> emptyList()
    }

    override suspend fun search(query: String, type: TitleType?): List<TitleSummary> {
        val q = NameMatch.normalise(query)
        val films = if (type != TitleType.SERIES) vodList().filter { NameMatch.normalise(it.str("name").orEmpty()).contains(q) }.take(20).mapNotNull { vodSummary(it, TitleType.FILM) } else emptyList()
        val shows = if (type != TitleType.FILM) seriesList().filter { NameMatch.normalise(it.str("name").orEmpty()).contains(q) }.take(20).mapNotNull { vodSummary(it, TitleType.SERIES) } else emptyList()
        return films + shows
    }

    override suspend fun streams(target: PlayTarget): List<Stream> = when (target) {
        is PlayTarget.Live -> {
            channels()
            val streamId = target.channel.tvgId?.takeIf { target.channel.pluginId == id } ?: live.orEmpty().firstOrNull { it.key == target.channel.key }?.tvgId
            listOfNotNull(streamId?.let { sid ->
                val url = "$server/live/${user.urlEncode()}/${password.urlEncode()}/$sid.$liveExt"
                Stream(source, "Live", null, url, StreamGuess.protocol(url), StreamGuess.container(url), isLive = true)
            })
        }
        is PlayTarget.Film -> vodList().filter { matches(it, target.ids.tmdb, target.name, target.year, target.key) }.take(3).mapNotNull { v ->
            val sid = v.long("stream_id") ?: return@mapNotNull null
            val ext = v.str("container_extension") ?: "mp4"
            val url = "$server/movie/${user.urlEncode()}/${password.urlEncode()}/$sid.$ext"
            Stream(source, ext.uppercase(), v.str("name"), url, StreamProtocol.PROGRESSIVE, StreamGuess.container(url), height = StreamGuess.height(v.str("name")))
        }
        is PlayTarget.EpisodeOf -> {
            val show = seriesList().firstOrNull { matches(it, target.seriesIds.tmdb, target.seriesName, target.year, target.key.seriesKey) }
            val sid = show?.long("series_id")
            if (sid == null) emptyList() else {
                val info = call("get_series_info", "&series_id=$sid", WatchHttp.HOUR)
                val episodes = info.child("episodes").obj?.get(target.season.toString()).let { it as? JsonArray }.orEmpty()
                episodes.filter { (it.int("episode_num") ?: -1) == target.episode }.mapNotNull { ep ->
                    val eid = ep.str("id") ?: return@mapNotNull null
                    val ext = ep.str("container_extension") ?: "mp4"
                    val url = "$server/series/${user.urlEncode()}/${password.urlEncode()}/$eid.$ext"
                    Stream(source, ext.uppercase(), ep.str("title"), url, StreamProtocol.PROGRESSIVE, StreamGuess.container(url),
                        durationMs = ep.child("info").long("duration_secs")?.times(1000))
                }
            }
        }
        else -> emptyList()
    }

    private fun matches(v: JsonElement, tmdb: Int?, name: String, year: Int?, key: WatchKey?): Boolean {
        val theirs = v.str("tmdb")?.toIntOrNull() ?: v.str("tmdb_id")?.toIntOrNull()
        if (tmdb != null && theirs != null) return tmdb == theirs
        if (key != null && key.namespace == id) return (v.long("stream_id") ?: v.long("series_id"))?.toString() == key.id
        val raw = v.str("name") ?: return false
        val y = yearOf(v.str("year")) ?: Regex("\\((\\d{4})\\)").find(raw)?.groupValues?.get(1)?.toIntOrNull()
        return NameMatch.same(cleanName(raw), name) && (year == null || y == null || kotlin.math.abs(y - year) <= 1)
    }

    /** "EN - The Matrix (1999)" to "The Matrix". */
    private fun cleanName(name: String) = name.replace(Regex("^[A-Z]{2,3}\\s*[-|:]\\s*"), "").replace(Regex("\\s*\\(\\d{4}\\)\\s*$"), "").trim()
}
