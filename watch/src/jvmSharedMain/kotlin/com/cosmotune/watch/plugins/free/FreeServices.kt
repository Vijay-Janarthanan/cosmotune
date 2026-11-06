package com.cosmotune.watch.plugins.free

import com.cosmotune.watch.http.WatchHttp
import com.cosmotune.watch.model.Channel
import com.cosmotune.watch.model.EpgProgramme
import com.cosmotune.watch.model.Episode
import com.cosmotune.watch.model.PlayTarget
import com.cosmotune.watch.model.Stream
import com.cosmotune.watch.model.Title
import com.cosmotune.watch.model.TitleSummary
import com.cosmotune.watch.model.TitleType
import com.cosmotune.watch.model.WatchKey
import com.cosmotune.watch.plugin.BuiltInPlugins
import com.cosmotune.watch.plugin.CatalogDef
import com.cosmotune.watch.plugin.Capability
import com.cosmotune.watch.plugin.PluginKind
import com.cosmotune.watch.plugin.WatchPlugin
import com.cosmotune.watch.http.child
import com.cosmotune.watch.http.int
import com.cosmotune.watch.http.list
import com.cosmotune.watch.http.obj
import com.cosmotune.watch.http.str
import com.cosmotune.watch.http.strings
import com.cosmotune.watch.model.StreamSource
import com.cosmotune.watch.plugins.StreamGuess
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import com.cosmotune.watch.plugins.servers.PlexPlugin
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.decodeFromStream

/**
 * The free live services' public channel lists (the ones Kodi's free-TV add-ons read): per region, each channel's name,
 * number, logo, group and its next programmes, from `<base>/<service>/.channels.json.gz`. The streams themselves are the
 * services' own. Only the regions in [regions] (the device's country unless changed in the plugin's settings) are listed: a
 * service not offered there shows no channels rather than pretending to be somewhere else.
 */
class FreeLiveLists(
    val base: String = "https://i.mjh.nz",
    val regions: () -> List<String> = { listOf("us") },
)

/** How a service's channel becomes a stream: its address and headers, from the channel id (and the list's slug). */
private typealias StreamOf = suspend (id: String, slug: String?) -> Pair<String, Map<String, String>>?

private class ServiceLive(
    override val id: String, override val displayName: String, override val badge: String, override val kind: PluginKind,
    private val http: WatchHttp, private val lists: FreeLiveLists, private val service: String, private val streamOf: StreamOf,
) : WatchPlugin {
    override val capabilities = setOf(Capability.LIVE, Capability.EPG, Capability.SEARCH)
    private class Loaded(val channels: List<Channel>, val programmes: Map<String, List<EpgProgramme>>, val slug: String?, val userAgent: String?)
    private val lock = kotlinx.coroutines.sync.Mutex()
    private var loaded: Pair<Long, Loaded>? = null

    private suspend fun load(): Loaded = lock.withLock {
        loaded?.takeIf { System.currentTimeMillis() - it.first < 3 * WatchHttp.HOUR }?.let { return it.second }
        // Read as it streams in, into the few fields used: a list of several MB held as a tree of JSON objects (while other
        // plugins' lists were read beside it) took the TV past its memory.
        val root = http.stream("${lists.base}/$service/.channels.json.gz", ttlMs = 3 * WatchHttp.HOUR) { input ->
            @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
            http.json.decodeFromStream(ServiceLists.serializer(), input)
        }
        val wanted = lists.regions().map { it.lowercase() }
        val entries = LinkedHashMap<String, Pair<ServiceChannel, String>>()
        for (r in wanted) {
            root.regions[r]?.channels?.forEach { (cid, c) -> entries.putIfAbsent(cid, c to r) }
            root.channels.forEach { (cid, c) -> if (r in c.regions) entries.putIfAbsent(cid, c to r) }
        }
        val channels = mutableListOf<Channel>()
        val programmes = HashMap<String, List<EpgProgramme>>()
        for ((cid, pair) in entries) {
            val (c, region) = pair
            channels += Channel(WatchKey.channel(id, cid), id, c.name ?: continue, c.chno?.content?.toDoubleOrNull()?.toInt(), c.logo,
                listOf(c.group ?: "General"), listOf(region), epgId = cid)
            val starts = c.programs.mapNotNull { p ->
                val a = p as? JsonArray ?: return@mapNotNull null
                val start = (a.getOrNull(0) as? JsonPrimitive)?.content?.toLongOrNull() ?: return@mapNotNull null
                start * 1000 to ((a.getOrNull(1) as? JsonPrimitive)?.content ?: "Programme")
            }.sortedBy { it.first }
            programmes[cid] = starts.mapIndexed { i, (start, title) ->
                EpgProgramme(cid, start, starts.getOrNull(i + 1)?.first ?: (start + WatchHttp.HOUR), title)
            }
        }
        Loaded(channels.sortedBy { it.number ?: Int.MAX_VALUE }, programmes, root.slug, root.headers["user-agent"])
            .also { loaded = System.currentTimeMillis() to it }
    }

    override suspend fun channels(): List<Channel> = load().channels

    override suspend fun guide(channels: List<Channel>, fromMs: Long, toMs: Long): List<EpgProgramme> {
        val all = load().programmes
        return channels.filter { it.pluginId == id }.flatMap { all[it.epgId].orEmpty() }.filter { it.stopMs > fromMs && it.startMs < toMs }
    }

    override suspend fun streams(target: PlayTarget): List<Stream> {
        val channel = (target as? PlayTarget.Live)?.channel?.takeIf { it.pluginId == id } ?: return emptyList()
        val l = load()
        val cid = channel.epgId ?: return emptyList()
        val (url, headers) = streamOf(cid, l.slug?.replace("{id}", cid)) ?: return emptyList()
        val all = buildMap { l.userAgent?.let { put("User-Agent", it) }; putAll(headers) }
        return listOf(Stream(StreamSource(id, displayName, badge), "Live", channel.categories.firstOrNull(), url, StreamGuess.protocol(url),
            headers = all, isLive = true, hasAds = true, isPublic = headers.isEmpty() && StreamGuess.isHttps(url) && '?' !in url))
    }

    override suspend fun health(): String? = runCatching { if (load().channels.isEmpty()) "Not offered in your region" else null }.getOrElse { it.message }
}

/** The parts of a service's channel list that are read; the rest (descriptions, artwork) is skipped while decoding. */
@kotlinx.serialization.Serializable
private class ServiceLists(
    val slug: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val regions: Map<String, ServiceRegion> = emptyMap(),
    val channels: Map<String, ServiceChannel> = emptyMap(),
)

@kotlinx.serialization.Serializable
private class ServiceRegion(val channels: Map<String, ServiceChannel> = emptyMap())

@kotlinx.serialization.Serializable
private class ServiceChannel(
    val name: String? = null,
    val chno: JsonPrimitive? = null,
    val logo: String? = null,
    val group: String? = null,
    val regions: List<String> = emptyList(),
    val programs: List<JsonArray> = emptyList(),
)

/** Live channels from [live] and on-demand films and series from the other parts, as one plugin with one switch. */
class LiveAndVodPlugin(
    override val id: String,
    override val displayName: String,
    override val badge: String,
    override val kind: PluginKind,
    private val live: WatchPlugin?,
    private val catalogsOf: suspend () -> List<CatalogDef> = { emptyList() },
    private val pageOf: suspend (CatalogDef, Int) -> List<TitleSummary> = { _, _ -> emptyList() },
    private val searchOf: suspend (String) -> List<TitleSummary> = { emptyList() },
    private val titleOf: suspend (WatchKey) -> Title? = { null },
    private val episodesOf: suspend (WatchKey, Int) -> List<Episode> = { _, _ -> emptyList() },
    private val vodStreams: suspend (PlayTarget) -> List<Stream> = { emptyList() },
) : WatchPlugin {
    override val capabilities: Set<Capability> = BuiltInPlugins.def(id)?.capabilities ?: setOf(Capability.LIVE)
    override suspend fun catalogs() = catalogsOf()
    override suspend fun catalogPage(catalog: CatalogDef, skip: Int) = pageOf(catalog, skip)
    override suspend fun search(query: String, type: TitleType?) = searchOf(query).filter { type == null || it.type == type }
    override suspend fun title(key: WatchKey): Title? = if (key.namespace == id) titleOf(key) else null
    override suspend fun episodes(series: WatchKey, season: Int) = if (series.namespace == id) episodesOf(series, season) else emptyList()
    override suspend fun streams(target: PlayTarget): List<Stream> = if (target is PlayTarget.Live) live?.streams(target).orEmpty() else vodStreams(target)
    override suspend fun channels(): List<Channel> = live?.channels().orEmpty()
    override suspend fun guide(channels: List<Channel>, fromMs: Long, toMs: Long): List<EpgProgramme> = live?.guide(channels, fromMs, toMs).orEmpty()
    override suspend fun health(): String? = coroutineScope {
        listOf(async { live?.health() }, async { runCatching { catalogsOf(); null }.getOrElse { it.message } }).awaitAll().firstOrNull { it != null }
    }
}

object FreeServices {
    fun samsung(http: WatchHttp, lists: FreeLiveLists): WatchPlugin =
        ServiceLive(BuiltInPlugins.SAMSUNG, "Samsung TV Plus", "TV+", PluginKind.SAMSUNG_TV_PLUS, http, lists, "SamsungTVPlus") { cid, slug ->
            // The list's slug names Samsung's own stream for the channel; the redirect resolves it to Samsung's address.
            "https://jmp2.uk/${slug ?: "stvp-$cid"}" to emptyMap()
        }

    fun pluto(http: WatchHttp, lists: FreeLiveLists, clientId: String, vod: PlutoVod = PlutoVod(http, BuiltInPlugins.PLUTO, clientId)): WatchPlugin =
        LiveAndVodPlugin(BuiltInPlugins.PLUTO, "Pluto TV", "PLUTO", PluginKind.PLUTO_TV,
            ServiceLive(BuiltInPlugins.PLUTO, "Pluto TV", "PLUTO", PluginKind.PLUTO_TV, http, lists, "PlutoTV") { cid, _ -> vod.channelStream(cid) to emptyMap() },
            catalogsOf = vod::catalogs, pageOf = vod::page, searchOf = vod::search, titleOf = vod::title, episodesOf = vod::episodes, vodStreams = vod::streams)

    fun plex(http: WatchHttp, lists: FreeLiveLists, clientId: String, vodBase: String = "https://vod.provider.plex.tv",
        plexTv: String = "https://plex.tv", epgBase: String = "https://epg.provider.plex.tv"): WatchPlugin {
        val vod = PlexPlugin(BuiltInPlugins.PLEX, "Plex", "PLEX", PluginKind.PLEX_FREE, http, vodBase, { PlexPlugin.anonymousToken(http, clientId, plexTv) }, clientId)
        return LiveAndVodPlugin(BuiltInPlugins.PLEX, "Plex free", "PLEX", PluginKind.PLEX_FREE,
            ServiceLive(BuiltInPlugins.PLEX, "Plex", "PLEX", PluginKind.PLEX_FREE, http, lists, "Plex") { cid, _ ->
                "$epgBase/library/parts/$cid.m3u8" to mapOf("X-Plex-Token" to vod.token(), "X-Plex-Client-Identifier" to clientId)
            },
            catalogsOf = vod::catalogs, pageOf = vod::catalogPage, searchOf = { vod.search(it, null) }, titleOf = vod::title,
            vodStreams = vod::streams)
    }
}
