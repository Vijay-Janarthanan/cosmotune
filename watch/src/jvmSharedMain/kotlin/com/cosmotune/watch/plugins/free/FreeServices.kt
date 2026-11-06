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
import com.cosmotune.watch.plugins.iptv.GuideCache
import com.cosmotune.watch.plugins.iptv.M3uEntry
import com.cosmotune.watch.plugins.iptv.PlaylistPlugin
import com.cosmotune.watch.plugins.servers.PlexPlugin
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * The free live services' public per-region channel lists and guides (the same lists Kodi's free-TV add-ons read). The
 * streams themselves are the services' own; a region the service does not cover falls back to the US list.
 */
class FreeLiveLists(
    val base: String = "https://i.mjh.nz",
    /** ISO country codes, lower case; the first the service has is used. */
    val regions: () -> List<String> = { listOf("us") },
) {
    fun playlists(service: String) = (regions() + "us").distinct().take(2).map { "$base/$service/$it.m3u8" }
    fun guides(service: String) = (regions() + "us").distinct().take(2).map { "$base/$service/$it.xml.gz" }
}

/** A live plugin keyed by the service's own channel ids (`c:samsung:<id>`), whatever the list's tvg-id says. */
private class ServiceLive(id: String, name: String, badge: String, kind: PluginKind, http: WatchHttp, lists: FreeLiveLists, service: String, cache: GuideCache) :
    PlaylistPlugin(id, name, badge, kind, http, { lists.playlists(service) }, { lists.guides(service) }, cache, publicStreams = true) {
    override fun keyOf(entry: M3uEntry): WatchKey = WatchKey.channel(id, (entry.tvgId ?: entry.name).replace(':', '_').take(48))
}

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
    fun samsung(http: WatchHttp, lists: FreeLiveLists, cache: GuideCache): WatchPlugin =
        ServiceLive(BuiltInPlugins.SAMSUNG, "Samsung TV Plus", "TV+", PluginKind.SAMSUNG_TV_PLUS, http, lists, "SamsungTVPlus", cache)

    fun pluto(http: WatchHttp, lists: FreeLiveLists, cache: GuideCache, clientId: String, vod: PlutoVod = PlutoVod(http, BuiltInPlugins.PLUTO, clientId)): WatchPlugin =
        LiveAndVodPlugin(BuiltInPlugins.PLUTO, "Pluto TV", "PLUTO", PluginKind.PLUTO_TV,
            ServiceLive(BuiltInPlugins.PLUTO, "Pluto TV", "PLUTO", PluginKind.PLUTO_TV, http, lists, "PlutoTV", cache),
            catalogsOf = vod::catalogs, pageOf = vod::page, searchOf = vod::search, titleOf = vod::title, episodesOf = vod::episodes, vodStreams = vod::streams)

    fun plex(http: WatchHttp, lists: FreeLiveLists, cache: GuideCache, clientId: String, vodBase: String = "https://vod.provider.plex.tv",
        plexTv: String = "https://plex.tv"): WatchPlugin {
        val vod = PlexPlugin(BuiltInPlugins.PLEX, "Plex", "PLEX", PluginKind.PLEX_FREE, http, vodBase, { PlexPlugin.anonymousToken(http, clientId, plexTv) }, clientId)
        return LiveAndVodPlugin(BuiltInPlugins.PLEX, "Plex free", "PLEX", PluginKind.PLEX_FREE,
            ServiceLive(BuiltInPlugins.PLEX, "Plex", "PLEX", PluginKind.PLEX_FREE, http, lists, "Plex", cache),
            catalogsOf = vod::catalogs, pageOf = vod::catalogPage, searchOf = { vod.search(it, null) }, titleOf = vod::title,
            vodStreams = vod::streams)
    }
}
