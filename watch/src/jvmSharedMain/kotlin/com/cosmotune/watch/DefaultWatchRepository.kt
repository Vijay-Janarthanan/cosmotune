package com.cosmotune.watch

import com.cosmotune.video.source.VideoSource
import com.cosmotune.watch.http.WatchHttp
import com.cosmotune.watch.meta.CinemetaMetadata
import com.cosmotune.watch.meta.MetadataProvider
import com.cosmotune.watch.meta.TmdbMetadata
import com.cosmotune.watch.model.Channel
import com.cosmotune.watch.model.EpgProgramme
import com.cosmotune.watch.model.Episode
import com.cosmotune.watch.model.MyListEntry
import com.cosmotune.watch.model.PlayTarget
import com.cosmotune.watch.model.Stream
import com.cosmotune.watch.model.StreamHint
import com.cosmotune.watch.model.Subtitle
import com.cosmotune.watch.model.SubtitleOrigin
import com.cosmotune.watch.model.Title
import com.cosmotune.watch.model.TitleSummary
import com.cosmotune.watch.model.TitleType
import com.cosmotune.watch.model.WatchKey
import com.cosmotune.watch.model.WatchProgress
import com.cosmotune.watch.model.WatchProvider
import com.cosmotune.watch.plugin.AddSourceResult
import com.cosmotune.watch.plugin.BuiltInPlugins
import com.cosmotune.watch.plugin.Capability
import com.cosmotune.watch.plugin.PluginInfo
import com.cosmotune.watch.plugin.PluginKind
import com.cosmotune.watch.plugin.UserSourceSpec
import com.cosmotune.watch.plugin.WatchPlugin
import com.cosmotune.watch.plugins.archive.InternetArchivePlugin
import com.cosmotune.watch.plugins.free.FreeLiveLists
import com.cosmotune.watch.plugins.free.FreeServices
import com.cosmotune.watch.plugins.free.YouTubeFreePlugin
import com.cosmotune.watch.plugins.iptv.GuideCache
import com.cosmotune.watch.plugins.iptv.IptvOrgPlugin
import com.cosmotune.watch.plugins.iptv.PlaylistPlugin
import com.cosmotune.watch.plugins.iptv.XtreamPlugin
import com.cosmotune.watch.plugins.local.LocalFolderPlugin
import com.cosmotune.watch.plugins.servers.JellyfinPlugin
import com.cosmotune.watch.plugins.servers.PlexPlugin
import com.cosmotune.watch.plugins.stremio.StremioClient
import com.cosmotune.watch.plugins.stremio.StremioPlugin
import com.cosmotune.watch.live.CricketDataScores
import com.cosmotune.watch.live.FootballDataScores
import com.cosmotune.watch.live.LiveChannelNow
import com.cosmotune.watch.live.LiveEventsHub
import com.cosmotune.watch.live.LiveScoreKeys
import com.cosmotune.watch.live.ScoreSource
import com.cosmotune.watch.live.SportsDbScores
import com.cosmotune.watch.model.LiveEvent
import com.cosmotune.watch.plugins.youtube.OfficialLivePlugin
import com.cosmotune.watch.plugins.youtube.PrasarBharatiPlugin
import com.cosmotune.watch.plugins.youtube.YouTubeLivePlugin
import com.cosmotune.watch.store.StoredSource
import com.cosmotune.watch.store.WatchState
import com.cosmotune.watch.store.WatchStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** What the app hosts hand the repository. */
data class WatchConfig(
    val client: OkHttpClient,
    /** Where the sealed store lives (`watch.json` inside it). */
    val dataDir: File,
    /** HTTP cache: catalogues, playlists, guides. */
    val cacheDir: File?,
    /** TMDB v3 key or v4 read token; blank falls back to Cinemeta. From `TMDB_API_KEY`. */
    val tmdbKey: String? = null,
    val language: String = Locale.getDefault().toLanguageTag().ifBlank { "en-US" },
    /** ISO country, upper case; picks TMDB providers, certifications and the free live services' region. */
    val region: String = Locale.getDefault().country.ifBlank { "IN" },
    /** Video mode's source, for Free on YouTube; null leaves that plugin quiet. */
    val videoSource: () -> VideoSource? = { null },
    /** The tallest picture this device decodes, to rank copies. */
    val maxHeight: Int = 2160,
    val preferredAudio: List<String> = listOf(Locale.getDefault().language),
    val now: () -> Long = System::currentTimeMillis,
    /** Test seams: base addresses of the built-in services. */
    val endpoints: Endpoints = Endpoints(),
    /** Per-plugin answer time for sources, rows and search. */
    val timeoutMs: Long = 15_000,
    /** The viewer's country and language (Music's discovery preferences): their channels and chips come first. */
    val place: () -> WatchPlace = WatchPlace::current,
    /** The free score services' keys for the Live now strip (`CRICKETDATA_API_KEY`, `FOOTBALL_DATA_API_KEY`); blank leaves one out. */
    val scoreKeys: LiveScoreKeys = LiveScoreKeys(),
)

data class Endpoints(
    val tmdb: String = "https://api.themoviedb.org/3",
    val cinemeta: String = "https://v3-cinemeta.strem.io/manifest.json",
    val iptvOrg: String = "https://iptv-org.github.io/api",
    val freeLists: String = "https://i.mjh.nz",
    val archive: String = "https://archive.org",
    val plutoBoot: String = "https://boot.pluto.tv",
    val plutoVod: String = "https://service-vod.clusters.pluto.tv",
    val plexVod: String = "https://vod.provider.plex.tv",
    val plexTv: String = "https://plex.tv",
    val plexEpg: String = "https://epg.provider.plex.tv",
    val publicDomain: String = BuiltInPlugins.PUBLIC_DOMAIN_URL,
    val openSubtitles: String = BuiltInPlugins.OPENSUBTITLES_URL,
    val watchHub: String = BuiltInPlugins.WATCHHUB_URL,
    val cricketData: String = "https://api.cricapi.com/v1",
    val footballData: String = "https://api.football-data.org/v4",
    val theSportsDb: String = "https://www.thesportsdb.com/api/v1/json",
)

/**
 * The real [WatchRepository]: the built-in plugins, the user's own sources, TMDB (or Cinemeta) for metadata, and the
 * sealed [WatchStore] for switches, sources, progress, My list and favourites.
 */
class DefaultWatchRepository(
    private val config: WatchConfig,
    val store: WatchStore = WatchStore(File(config.dataDir, "watch.json")),
    /** Tests replace the built-in plugins. */
    builtIns: ((WatchHttp, WatchState) -> List<WatchPlugin>)? = null,
) : WatchRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val http = WatchHttp(config.client, config.cacheDir, config.now)
    private val guideCache = GuideCache(http, config.now)
    private val metadata: MetadataProvider = config.tmdbKey?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { TmdbMetadata(http, it, config.language, config.region.uppercase(), config.endpoints.tmdb) }
        ?: CinemetaMetadata(http, config.endpoints.cinemeta)
    override val metadataSource: StateFlow<MetadataSource> = MutableStateFlow(metadata.source)

    private val builtInPlugins: List<WatchPlugin> = builtIns?.invoke(http, store.state.value) ?: defaultBuiltIns()
    private val userPlugins = ConcurrentHashMap<String, WatchPlugin>()
    private val status = MutableStateFlow<Map<String, Pair<String?, Boolean>>>(emptyMap())
    private val titles = ConcurrentHashMap<WatchKey, Title>()
    private val channelLock = Mutex()
    private var channelCache: Pair<Long, Map<WatchKey, List<Channel>>>? = null

    private fun defaultBuiltIns(): List<WatchPlugin> {
        val e = config.endpoints
        val regions = { store.state.value.settings["regions"]?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() } ?: listOf(config.region.lowercase().let { if (it == "uk") "gb" else it }) }
        val lists = FreeLiveLists(e.freeLists, regions)
        val device = store.state.value.deviceId
        return listOf(
            IptvOrgPlugin(http, e.iptvOrg, { guideUrls() }, guideCache),
            FreeServices.pluto(http, lists, device, com.cosmotune.watch.plugins.free.PlutoVod(http, BuiltInPlugins.PLUTO, device, e.plutoBoot, e.plutoVod, config.now)),
            FreeServices.samsung(http, lists),
            PrasarBharatiPlugin(config.videoSource, config.now),
            YouTubeLivePlugin(config.videoSource, config.now),
            FreeServices.plex(http, lists, device, e.plexVod, e.plexTv, e.plexEpg),
            InternetArchivePlugin(http, e.archive),
            YouTubeFreePlugin(config.videoSource),
            stremio(BuiltInPlugins.PUBLIC_DOMAIN, e.publicDomain),
            stremio(BuiltInPlugins.OPENSUBTITLES, e.openSubtitles, subtitles = SubtitleOrigin.OPENSUBTITLES),
            stremio(BuiltInPlugins.WATCHHUB, e.watchHub, whereToWatch = true),
        )
    }

    private fun stremio(id: String, url: String, whereToWatch: Boolean = false, subtitles: SubtitleOrigin = SubtitleOrigin.ADDON): WatchPlugin {
        val def = BuiltInPlugins.def(id)!!
        return StremioPlugin(id, def.name, def.badge, StremioClient(http, url), def.capabilities, whereToWatchOnly = whereToWatch, subtitleOrigin = subtitles)
    }

    private fun guideUrls(): List<String> = store.state.value.settings["iptv.guides"]?.lines()?.map { it.trim() }?.filter { it.startsWith("http") }.orEmpty()

    // ── Plugins ──────────────────────────────────────────────────────────

    private val pluginsState = MutableStateFlow(infos(store.state.value, emptyMap()))
    override val plugins: StateFlow<List<PluginInfo>> = pluginsState.asStateFlow()

    private fun infos(state: WatchState, health: Map<String, Pair<String?, Boolean>>): List<PluginInfo> {
        val built = BuiltInPlugins.all.map { def ->
            BuiltInPlugins.info(def, state.builtIn[def.id] ?: def.defaultEnabled, health[def.id]?.first).copy(error = health[def.id]?.second == true)
        }
        val mine = state.sources.map { s ->
            val plugin = pluginFor(s)
            PluginInfo(s.id, plugin?.kind ?: kindOf(s.spec), s.name ?: s.spec.label.ifBlank { describe(s.spec) }, describe(s.spec), plugin?.badge ?: "ADD",
                builtIn = false, enabled = s.enabled, defaultEnabled = true, capabilities = plugin?.capabilities.orEmpty(),
                status = health[s.id]?.first, error = health[s.id]?.second == true)
        }
        return built + mine
    }

    private fun kindOf(spec: UserSourceSpec) = when (spec) {
        is UserSourceSpec.StremioAddon -> PluginKind.STREMIO_ADDON; is UserSourceSpec.Jellyfin -> PluginKind.JELLYFIN
        is UserSourceSpec.Emby -> PluginKind.EMBY; is UserSourceSpec.Plex -> PluginKind.PLEX_SERVER; is UserSourceSpec.M3u -> PluginKind.M3U
        is UserSourceSpec.Xtream -> PluginKind.XTREAM; is UserSourceSpec.LocalFolder -> PluginKind.LOCAL_FOLDER
    }

    private fun describe(spec: UserSourceSpec): String = when (spec) {
        is UserSourceSpec.StremioAddon -> "Add-on · ${host(spec.manifestUrl)}"
        is UserSourceSpec.Jellyfin -> "Jellyfin · ${host(spec.server)}"
        is UserSourceSpec.Emby -> "Emby · ${host(spec.server)}"
        is UserSourceSpec.Plex -> "Plex · ${host(spec.server)}"
        is UserSourceSpec.M3u -> "M3U playlist${if (spec.epgUrl != null) " · guide" else ""}"
        is UserSourceSpec.Xtream -> "Xtream · ${host(spec.server)}"
        is UserSourceSpec.LocalFolder -> "Folder · ${File(spec.path).name}"
    }

    private fun host(url: String) = runCatching { java.net.URI(if ("://" in url) url else "http://$url").host }.getOrNull() ?: url

    /** Builds (once) the plugin of a stored source. */
    private fun pluginFor(s: StoredSource): WatchPlugin? = userPlugins[s.id] ?: runCatching { build(s) }.getOrNull()?.also { userPlugins[s.id] = it }

    private fun build(s: StoredSource): WatchPlugin = when (val spec = s.spec) {
        is UserSourceSpec.StremioAddon -> {
            val name = s.name ?: spec.label.ifBlank { "Add-on" }
            StremioPlugin(s.id, name, badgeOf(name), StremioClient(http, spec.manifestUrl),
                setOf(Capability.CATALOG, Capability.SEARCH, Capability.META, Capability.STREAMS, Capability.SUBTITLES, Capability.LIVE))
        }
        is UserSourceSpec.Jellyfin -> JellyfinPlugin(s.id, s.name ?: spec.label.ifBlank { "Jellyfin" }, http, spec.server, requireNotNull(s.session))
        is UserSourceSpec.Emby -> JellyfinPlugin(s.id, s.name ?: spec.label.ifBlank { "Emby" }, http, spec.server, requireNotNull(s.session), emby = true)
        is UserSourceSpec.Plex -> PlexPlugin(s.id, s.name ?: spec.label.ifBlank { "Plex" }, "PLEX", PluginKind.PLEX_SERVER, http,
            JellyfinPlugin.normalise(spec.server), { spec.token }, store.state.value.deviceId, free = false)
        is UserSourceSpec.M3u -> PlaylistPlugin(s.id, s.name ?: spec.label.ifBlank { "Playlist" }, "M3U", PluginKind.M3U, http, { listOf(spec.url) },
            { listOfNotNull(spec.epgUrl) }, guideCache)
        is UserSourceSpec.Xtream -> XtreamPlugin(s.id, s.name ?: spec.label.ifBlank { "Xtream" }, http, spec.server, spec.user, spec.password, guideCache)
        is UserSourceSpec.LocalFolder -> LocalFolderPlugin(s.id, s.name ?: spec.label.ifBlank { File(spec.path).name }, File(spec.path))
    }

    private fun badgeOf(name: String) = name.filter { it.isLetterOrDigit() }.take(4).uppercase().ifBlank { "ADD" }

    /** Plugins that are switched on, in rank order: built-ins in page order, then the user's sources. */
    fun activePlugins(): List<WatchPlugin> {
        val state = store.state.value
        val built = builtInPlugins.filter { p -> state.builtIn[p.id] ?: (BuiltInPlugins.def(p.id)?.defaultEnabled ?: true) }
        val mine = state.sources.filter { it.enabled }.mapNotNull(::pluginFor)
        return mine.filter { it.kind in SERVER_KINDS } + built + mine.filter { it.kind !in SERVER_KINDS }
    }

    /** Below 100: the user's servers and folders; then everything else in page order. */
    private fun rankOf(pluginId: String): Int {
        val active = activePlugins()
        val i = active.indexOfFirst { it.id == pluginId }
        if (i < 0) return 10_000
        return if (active[i].kind in SERVER_KINDS) i else 100 + i
    }

    private fun ranker() = StreamRanker(config.maxHeight, config.preferredAudio, ::rankOf)

    private suspend fun <T> each(plugins: List<WatchPlugin>, block: suspend (WatchPlugin) -> T): List<Pair<WatchPlugin, T?>> = coroutineScope {
        plugins.map { p ->
            async {
                p to runCatching { withTimeoutOrNull(config.timeoutMs) { block(p) } }.onFailure { report(p.id, it) }.getOrNull()
            }
        }.awaitAll()
    }

    private fun report(id: String, error: Throwable) {
        if (error is kotlinx.coroutines.CancellationException) return
        status.value = status.value + (id to ((error.message ?: "Not responding") to true))
    }

    override suspend fun setPluginEnabled(id: String, enabled: Boolean) {
        update { s ->
            if (BuiltInPlugins.def(id) != null) s.copy(builtIn = s.builtIn + (id to enabled))
            else s.copy(sources = s.sources.map { if (it.id == id) it.copy(enabled = enabled, updatedAtMs = config.now()) else it })
        }
        channelCache = null
    }

    /** Sets a plugin setting: `iptv.guides` (XMLTV addresses, one per line) or `regions` ("in,us"). */
    suspend fun setSetting(key: String, value: String?) {
        update { s -> s.copy(settings = if (value.isNullOrBlank()) s.settings - key else s.settings + (key to value)) }
        channelCache = null
    }

    override suspend fun addUserSource(spec: UserSourceSpec): AddSourceResult = try {
        val now = config.now()
        val stored: StoredSource = when (spec) {
            is UserSourceSpec.StremioAddon -> {
                val url = InstallLinks.manifestUrl(spec.manifestUrl) ?: error("This is not an add-on link")
                val manifest = StremioClient(http, url).manifest(ttlMs = 0)
                problemOf(manifest)?.let { error(it) }
                StoredSource("st-" + hash(url), spec.copy(manifestUrl = url), addedAtMs = now, name = spec.label.ifBlank { manifest.name })
            }
            is UserSourceSpec.Jellyfin, is UserSourceSpec.Emby -> {
                val emby = spec is UserSourceSpec.Emby
                val (server, user, password) = if (spec is UserSourceSpec.Jellyfin) Triple(spec.server, spec.user, spec.password) else (spec as UserSourceSpec.Emby).let { Triple(it.server, it.user, it.password) }
                val session = JellyfinPlugin.signIn(http, server, user, password, emby, store.state.value.deviceId)
                val kept: UserSourceSpec = if (spec is UserSourceSpec.Jellyfin) spec.copy(server = JellyfinPlugin.normalise(server), password = "")
                    else (spec as UserSourceSpec.Emby).copy(server = JellyfinPlugin.normalise(server), password = "")
                StoredSource((if (emby) "em-" else "jf-") + hash("$server|$user"), kept, session, addedAtMs = now,
                    name = spec.label.ifBlank { session.serverName ?: if (emby) "Emby" else "Jellyfin" })
            }
            is UserSourceSpec.Plex -> {
                val probe = PlexPlugin("probe", "", "", PluginKind.PLEX_SERVER, http, JellyfinPlugin.normalise(spec.server), { spec.token }, store.state.value.deviceId, false)
                probe.health()?.let { error(it) }
                StoredSource("px-" + hash(spec.server), spec.copy(server = JellyfinPlugin.normalise(spec.server)), addedAtMs = now, name = spec.label.ifBlank { "Plex" })
            }
            is UserSourceSpec.M3u -> {
                val probe = PlaylistPlugin("probe", "", "", PluginKind.M3U, http, { listOf(spec.url) })
                require(probe.channelCount() > 0) { "No channels in this playlist" }
                StoredSource("m3u-" + hash(spec.url), spec, addedAtMs = now, name = spec.label.ifBlank { "Playlist" })
            }
            is UserSourceSpec.Xtream -> {
                XtreamPlugin("probe", "", http, spec.server, spec.user, spec.password).account()
                StoredSource("xt-" + hash("${spec.server}|${spec.user}"), spec, addedAtMs = now, name = spec.label.ifBlank { host(spec.server) })
            }
            is UserSourceSpec.LocalFolder -> {
                require(File(spec.path).isDirectory) { "That folder cannot be opened" }
                StoredSource("dir-" + hash(spec.path), spec, addedAtMs = now, name = spec.label.ifBlank { File(spec.path).name })
            }
        }
        require(store.state.value.sources.none { it.id == stored.id }) { "This is already added" }
        userPlugins.remove(stored.id)
        update { s -> s.copy(sources = s.sources + stored, removed = s.removed - stored.id) }
        channelCache = null
        AddSourceResult.Added(plugins.value.first { it.id == stored.id })
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
        AddSourceResult.Failed(e.message ?: "That could not be added")
    }

    private fun problemOf(m: com.cosmotune.watch.plugins.stremio.StremioManifest): String? = when {
        m.p2p -> "Peer-to-peer add-ons are not supported"
        m.adult -> "Adult add-ons are not supported"
        m.configurationRequired -> "This add-on must be configured on its own page first; paste the configured link"
        m.resources.none { it.name in setOf("stream", "subtitles", "catalog", "meta") } -> "This add-on offers nothing Watch can use"
        else -> null
    }

    override suspend fun removeUserSource(id: String) {
        update { s -> s.copy(sources = s.sources.filterNot { it.id == id }, removed = s.removed + (id to config.now())) }
        userPlugins.remove(id); channelCache = null
    }

    override suspend fun moveUserSource(id: String, toIndex: Int) {
        update { s ->
            val item = s.sources.firstOrNull { it.id == id } ?: return@update s
            val rest = s.sources - item
            s.copy(sources = rest.toMutableList().apply { add(toIndex.coerceIn(0, rest.size), item) })
        }
    }

    override suspend fun previewInstallLink(link: String): InstallPreview {
        val url = InstallLinks.manifestUrl(link) ?: return InstallPreview(link, "Unknown", null, problem = "This is not an add-on link")
        return try {
            val m = StremioClient(http, url).manifest(ttlMs = 0)
            InstallPreview(url, m.name, m.description, m.logo, m.version, m.offers,
                alreadyInstalled = store.state.value.sources.any { (it.spec as? UserSourceSpec.StremioAddon)?.manifestUrl == url }, problem = problemOf(m))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            InstallPreview(url, host(url), null, problem = "The add-on did not answer (${e.message})")
        }
    }

    override suspend fun installFromLink(link: String): AddSourceResult {
        val url = InstallLinks.manifestUrl(link) ?: return AddSourceResult.Failed("This is not an add-on link")
        return addUserSource(UserSourceSpec.StremioAddon(url))
    }

    override suspend fun exportPluginPack(): String = PluginPack.encode(store.state.value.sources.mapNotNull { s ->
        (s.spec as? UserSourceSpec.StremioAddon)?.let { PluginPack.Item(it.manifestUrl, s.name.orEmpty()) }
    })

    override suspend fun readPluginPack(payload: String): List<PluginPackItem> {
        val installed = store.state.value.sources.mapNotNull { (it.spec as? UserSourceSpec.StremioAddon)?.manifestUrl }.toSet()
        return PluginPack.decode(payload).map { PluginPackItem(it.u, it.n.ifBlank { host(it.u) }, it.u in installed) }
    }

    override suspend fun importPluginPack(payload: String, only: Set<String>?): ImportPackResult {
        val items = readPluginPack(payload).filter { only == null || it.manifestUrl in only }
        val added = mutableListOf<PluginInfo>(); val failed = mutableMapOf<String, String>(); var skipped = 0
        for (item in items) {
            if (item.alreadyInstalled) { skipped++; continue }
            when (val r = addUserSource(UserSourceSpec.StremioAddon(item.manifestUrl, item.name))) {
                is AddSourceResult.Added -> added += r.plugin
                is AddSourceResult.Failed -> failed[item.manifestUrl] = r.message
            }
        }
        return ImportPackResult(added, skipped, failed)
    }

    /** Checks every switched-on plugin and fills the Plugins page's status line. */
    suspend fun refreshHealth() {
        val results = each(activePlugins()) { it.health() }
        status.value = status.value + results.associate { (p, msg) -> p.id to (msg to (msg != null)) }
        refresh()
    }

    // ── Home ─────────────────────────────────────────────────────────────

    override suspend fun home(filter: HomeFilter): WatchHome = coroutineScope {
        val type = when (filter) { HomeFilter.FILMS -> TitleType.FILM; HomeFilter.SERIES -> TitleType.SERIES; else -> null }
        val live = async { if (filter == HomeFilter.FILMS || filter == HomeFilter.SERIES) emptyList() else runCatching { liveNow(if (filter == HomeFilter.LIVE) 30 else 12) }.getOrDefault(emptyList()) }
        if (filter == HomeFilter.LIVE) return@coroutineScope WatchHome(null, emptyList(), live.await(), emptyList(), empty = live.await().isEmpty())
        val pluginRows = async { pluginRows(type) }
        val metaRows = async { runCatching { withTimeoutOrNull(config.timeoutMs) { metadata.rows(type) } }.getOrNull().orEmpty() }
        // While the plugins' rows load, the free plugins are asked whether they can play the most popular films.
        val probed = async { if (type == TitleType.SERIES) emptyMap() else probeFree(popularFilms(metaRows.await())) }
        val free = pluginRows.await()
        val rows = free + metaRows.await()
        val cw = continueWatching.value.filter { type == null || it.summary.type == type }
        val featured = (featuredPopular(popularFilms(metaRows.await()), free, probed.await()) ?: bestFree(free, rows))
            ?.let { (s, badge) -> Featured(s, listOfNotNull(badge), store.state.value.progress[s.key.value]) }
        WatchHome(featured, cw, live.await(), rows, empty = rows.isEmpty() && live.await().isEmpty() && cw.isEmpty())
    }

    /** The trending and popular films of the metadata rows, in their order, with a picture for the featured card. */
    private fun popularFilms(meta: List<HomeRow>): List<TitleSummary> =
        meta.filter { it.id.startsWith("tmdb:trending:film") || it.id.startsWith("tmdb:popular:film") || it.id == "cinemeta:movie/top" }
            .flatMap { it.items }.filter { it.type == TitleType.FILM && (it.backdropUrl != null || it.posterUrl != null) }.distinctBy { it.key }

    /**
     * Which of the first few [films] a free plugin (Pluto TV, Plex, YouTube's free films, the Archive, Public Domain Movies)
     * can play, with the badge of the copy found. Bounded in time: the home never waits long for it.
     */
    private suspend fun probeFree(films: List<TitleSummary>): Map<WatchKey, String> = coroutineScope {
        val plugins = activePlugins().filter { it.id in FREE_FILM_PLUGINS && Capability.STREAMS in it.capabilities }
        if (plugins.isEmpty() || films.isEmpty()) return@coroutineScope emptyMap()
        withTimeoutOrNull(FEATURED_PROBE_MS) {
            films.take(4).map { film ->
                async {
                    val target = targetOf(film)
                    val found = plugins.map { p -> async { runCatching { p.streams(target) }.getOrNull().orEmpty().firstOrNull { it.isPlayable } } }.awaitAll().filterNotNull().firstOrNull()
                    found?.let { film.key to "FREE · " + it.source.pluginName.removePrefix("Free on ").uppercase() }
                }
            }.awaitAll().filterNotNull().toMap()
        }.orEmpty()
    }

    /** The first popular film a free plugin has: listed in a free plugin's row by name, or found by [probeFree]. */
    private fun featuredPopular(films: List<TitleSummary>, free: List<HomeRow>, probed: Map<WatchKey, String>): Pair<TitleSummary, String?>? {
        val freeItems = free.filter { it.pluginId in FREE_FILM_PLUGINS }.flatMap { it.items }
        for (film in films.take(12)) {
            probed[film.key]?.let { return film to it }
            freeItems.firstOrNull { com.cosmotune.watch.meta.NameMatch.same(it.name, film.name) && (film.year == null || it.year == null || kotlin.math.abs(it.year - film.year) <= 1) }
                ?.let { return film to it.badge }
        }
        return null
    }

    /** The best free title when no popular film is free: a free service's film with a wide picture first, then the first of the rows. */
    private fun bestFree(free: List<HomeRow>, rows: List<HomeRow>): Pair<TitleSummary, String?>? {
        val pick = free.filter { it.pluginId != BuiltInPlugins.ARCHIVE }.flatMap { it.items }.firstOrNull { it.backdropUrl != null }
            ?: free.flatMap { it.items }.firstOrNull { it.backdropUrl != null }
            ?: free.firstOrNull { it.items.isNotEmpty() }?.items?.first()
            ?: rows.firstOrNull()?.items?.firstOrNull()
        return pick?.let { it to it.badge }
    }

    private suspend fun pluginRows(type: TitleType?): List<HomeRow> {
        val catalogs = each(activePlugins().filter { Capability.CATALOG in it.capabilities }) { p -> p.catalogs().filter { it.home && (type == null || it.type == null || it.type == type) }.take(4) }
        val defs = catalogs.flatMap { (p, list) -> list.orEmpty().map { p to it } }
        return coroutineScope {
            defs.map { (p, c) ->
                async {
                    runCatching { withTimeoutOrNull(config.timeoutMs) { p.catalogPage(c, 0) } }.getOrNull()?.filter { type == null || it.type == type }
                        ?.takeIf { it.isNotEmpty() }?.let { HomeRow("plugin:${p.id}:${c.id}", c.name, it, p.displayName, p.id) }
                }
            }.awaitAll().filterNotNull()
        }
    }

    override suspend fun rowPage(rowId: String, skip: Int): List<TitleSummary> {
        if (rowId.startsWith("plugin:")) {
            val parts = rowId.split(':', limit = 3)
            val plugin = activePlugins().firstOrNull { it.id == parts.getOrNull(1) } ?: return emptyList()
            val catalog = plugin.catalogs().firstOrNull { it.id == parts.getOrNull(2) } ?: return emptyList()
            return plugin.catalogPage(catalog, skip)
        }
        return metadata.rowPage(rowId, skip)
    }

    override suspend fun liveNow(limit: Int): List<LiveNowItem> {
        val all = channelsByKey()
        val order = ChannelRank.order(store.state.value.favourites, config.place()) { all[it.key].orEmpty() }
        val picked = all.values.map(::merged).sortedWith(order).take(limit)
        val t = config.now()
        val window = guide(picked, t - 6 * WatchHttp.HOUR, t + 12 * WatchHttp.HOUR)
        return window.rows.map { row ->
            val i = row.programmes.indexOfFirst { it.isOnAt(t) }
            LiveNowItem(row.channel, row.programmes.getOrNull(i), if (i >= 0) row.programmes.getOrNull(i + 1) else row.programmes.firstOrNull { it.startMs > t })
        }
    }

    // ── Live events ──────────────────────────────────────────────────────

    /** The score services the keys allow: CricketData.org and football-data.org with a key, TheSportsDB for a sport without one. */
    private fun scoreSources(): List<ScoreSource> {
        val keys = config.scoreKeys
        val e = config.endpoints
        val fallback = (if (keys.cricket == null) SportsDbScores.CRICKET else emptyList()) + (if (keys.football == null) SportsDbScores.FOOTBALL else emptyList())
        return listOfNotNull(
            keys.cricket?.let { CricketDataScores(http, it, e.cricketData) },
            keys.football?.let { FootballDataScores(http, it, e.footballData) },
            fallback.takeIf { it.isNotEmpty() }?.let { SportsDbScores(http, keys.sportsDb, it, e.theSportsDb) },
        )
    }

    /** The DD and YouTube channels whose live videos can be a special or carry a match, of the plugins that are on. */
    private suspend fun eventChannels(): List<LiveChannelNow> = coroutineScope {
        activePlugins().filterIsInstance<OfficialLivePlugin>().flatMap { p -> p.channelList.filter { it.slug in EVENT_CHANNELS }.map { p to it } }.map { (p, def) ->
            async {
                runCatching { p.liveOf(def) }.getOrNull()?.let { LiveChannelNow(p.channelOf(def), p.badge, def.allDay, it.lives, it.upcoming) }
            }
        }.awaitAll().filterNotNull()
    }

    private val liveEventsHub = LiveEventsHub(::scoreSources, ::eventChannels, config.place, config.now, config.timeoutMs)

    override fun liveEvents(): Flow<List<LiveEvent>> = liveEventsHub.flow()

    /** The events once, without polling (tests and a pull-to-refresh). */
    suspend fun liveEventsNow(): List<LiveEvent> = liveEventsHub.current()

    // ── Pages ────────────────────────────────────────────────────────────

    override suspend fun title(key: WatchKey): Title? {
        titles[key]?.let { return it }
        val base = when {
            metadata.owns(key) -> runCatching { metadata.title(key) }.getOrNull()
            else -> activePlugins().firstOrNull { it.id == key.namespace }?.let { runCatching { it.title(key) }.getOrNull() }
                ?: builtInPlugins.firstOrNull { it.id == key.namespace }?.let { runCatching { it.title(key) }.getOrNull() }
        } ?: return null
        titles[key] = base; titles[base.key] = base
        return base
    }

    override suspend fun episodes(series: WatchKey, season: Int): List<Episode> = when {
        metadata.owns(series) -> metadata.episodes(series, season)
        else -> (activePlugins() + builtInPlugins).firstOrNull { it.id == series.namespace }?.episodes(series, season).orEmpty()
    }

    override suspend fun nextUp(series: WatchKey): NextUp? {
        val inSeries = store.state.value.progress.values.filter { it.key.seriesKey == series }.sortedByDescending { it.updatedAtMs }
        val last = inSeries.firstOrNull()
        if (last != null && !last.completed) {
            val ep = episodes(series, last.key.season ?: 1).firstOrNull { it.key == last.key }
            if (ep != null) return NextUp(ep, last, resume = true)
        }
        if (last != null) nextEpisode(last.key)?.let { return NextUp(it, store.state.value.progress[it.key.value], resume = false) }
        val title = title(series) ?: return null
        val first = title.seasons.firstOrNull { it.number > 0 }?.number ?: title.seasons.firstOrNull()?.number ?: 1
        return episodes(series, first).firstOrNull()?.let { NextUp(it, null, resume = false) }
    }

    override suspend fun nextEpisode(episode: WatchKey): Episode? {
        val series = episode.seriesKey ?: return null
        val season = episode.season ?: return null
        val number = episode.episode ?: return null
        episodes(series, season).firstOrNull { it.number > number }?.let { return it }
        val seasons = title(series)?.seasons?.map { it.number }?.filter { it > season }?.sorted().orEmpty()
        for (s in seasons) episodes(series, s).firstOrNull()?.let { return it }
        return null
    }

    override fun targetOf(summary: TitleSummary): PlayTarget = when (summary.type) {
        TitleType.FILM -> PlayTarget.Film(summary.key, summary.ids, summary.name, summary.year, summary.runtimeMin)
        TitleType.SERIES -> PlayTarget.EpisodeOf(WatchKey.episode(summary.key.namespace, summary.key.id, 1, 1), summary.ids, summary.name, 1, 1, year = summary.year)
    }

    override fun targetOf(series: Title, episode: Episode): PlayTarget =
        PlayTarget.EpisodeOf(episode.key, series.summary.ids, series.summary.name, episode.season, episode.number, episode.ids, episode.name, series.summary.year)

    /** Fills ids a plugin will need (IMDb for add-ons) from the metadata page. */
    private suspend fun enrich(target: PlayTarget): PlayTarget = when (target) {
        is PlayTarget.Film -> if (target.ids.imdb != null && target.year != null) target else title(target.key)?.summary?.let {
            target.copy(ids = target.ids.merge(it.ids), year = target.year ?: it.year, runtimeMin = target.runtimeMin ?: it.runtimeMin)
        } ?: target
        is PlayTarget.EpisodeOf -> if (target.seriesIds.imdb != null) target else target.key.seriesKey?.let { title(it) }?.summary?.let {
            target.copy(seriesIds = target.seriesIds.merge(it.ids), year = target.year ?: it.year)
        } ?: target
        else -> target
    }

    // ── Sources ──────────────────────────────────────────────────────────

    override fun sources(target: PlayTarget, hint: StreamHint?): Flow<SourcesState> = channelFlow {
        val full = enrich(target)
        val plugins = activePlugins().filter { p ->
            if (full is PlayTarget.Live) Capability.LIVE in p.capabilities else Capability.STREAMS in p.capabilities || Capability.WHERE_TO_WATCH in p.capabilities
        }
        val lock = Mutex()
        val streams = mutableListOf<Stream>()
        val pending = plugins.map { it.id }.toMutableSet()
        val errors = mutableMapOf<String, String>()
        val where = mutableListOf<WatchProvider>()
        if (full !is PlayTarget.Live) titles[full.key.seriesKey ?: full.key]?.whereToWatch?.let(where::addAll)
        val ranker = ranker()
        suspend fun emitNow() = send(SourcesState(full, ranker.rank(streams, hint), pending.toSet(), errors.toMap(), where.distinctBy { it.name }))
        lock.withLock { emitNow() }
        coroutineScope {
            for (p in plugins) launch {
                val result = runCatching {
                    withTimeoutOrNull(config.timeoutMs) {
                        if (Capability.WHERE_TO_WATCH in p.capabilities && Capability.STREAMS !in p.capabilities) Pair(emptyList(), p.whereToWatch(full))
                        else Pair(p.streams(full), emptyList<WatchProvider>())
                    } ?: error("Not responding")
                }
                lock.withLock {
                    pending -= p.id
                    result.onSuccess { (s, w) -> streams += s; where += w }.onFailure { if (it !is kotlinx.coroutines.CancellationException) errors[p.id] = it.message ?: "Not responding" }
                    emitNow()
                }
            }
        }
    }

    override suspend fun sourcesNow(target: PlayTarget, hint: StreamHint?): SourcesState = sources(target, hint).last()

    override suspend fun subtitles(target: PlayTarget, stream: Stream?, languages: List<String>): List<Subtitle> {
        val full = enrich(target)
        val found = each(activePlugins().filter { Capability.SUBTITLES in it.capabilities }) { it.subtitles(full, stream) }.flatMap { it.second.orEmpty() }
        val all = (stream?.subtitles.orEmpty() + found).distinctBy { it.url ?: it.id }
        if (languages.isEmpty()) return all
        return all.sortedBy { s -> languages.indexOfFirst { LanguageCodes.same(it, s.language) }.let { if (it < 0) languages.size else it } }
    }

    // ── Live TV ──────────────────────────────────────────────────────────

    /** Every channel of the switched-on live plugins, by key; a key listed by two plugins keeps both. */
    private suspend fun channelsByKey(): Map<WatchKey, List<Channel>> = channelLock.withLock {
        channelCache?.takeIf { config.now() - it.first < 30 * 60_000L }?.let { return it.second }
        val lists = each(activePlugins().filter { Capability.LIVE in it.capabilities }) { it.channels() }
        val byKey = LinkedHashMap<WatchKey, MutableList<Channel>>()
        for ((_, list) in lists) for (c in list.orEmpty()) byKey.getOrPut(c.key) { mutableListOf() } += c
        val result: Map<WatchKey, List<Channel>> = byKey
        channelCache = config.now() to result
        result
    }

    private fun merged(list: List<Channel>): Channel {
        val first = list.first()
        if (list.size == 1) return first
        return first.copy(logoUrl = first.logoUrl ?: list.firstNotNullOfOrNull { it.logoUrl },
            categories = list.flatMap { it.categories }.distinct().filter { it != "General" || list.all { c -> c.categories == listOf("General") } },
            countries = list.flatMap { it.countries }.distinct(), languages = list.flatMap { it.languages }.distinct(), number = first.number ?: list.firstNotNullOfOrNull { it.number })
    }

    override suspend fun channels(filter: ChannelFilter): List<Channel> {
        val favourites = store.state.value.favourites
        val q = filter.query?.trim()?.lowercase()
        val byKey = channelsByKey()
        return byKey.values.map { list -> if (filter.pluginId != null) list.firstOrNull { it.pluginId == filter.pluginId } else merged(list) }
            .filterNotNull().filter { c ->
                (filter.country == null || filter.country.lowercase() in c.countries) &&
                    (filter.language == null || c.languages.any { LanguageCodes.same(it, filter.language) }) &&
                    (filter.category == null || c.categories.any { it.equals(filter.category, true) }) &&
                    (!filter.favouritesOnly || c.key.value in favourites) && (q.isNullOrEmpty() || c.name.lowercase().contains(q))
            }.sortedWith(ChannelRank.order(favourites, config.place()) { byKey[it.key].orEmpty() })
    }

    override suspend fun channel(key: WatchKey): Channel? = channelsByKey()[key]?.let(::merged)

    override suspend fun channelFacets(): ChannelFacets {
        val all = channelsByKey().values.map(::merged)
        val place = config.place()
        // The viewer's own country and language first, then by how many channels each has.
        fun facets(values: List<String>, yours: (String) -> Boolean, name: (String) -> String) = values.groupingBy { it }.eachCount()
            .map { (id, n) -> Facet(id, name(id), n, yours(id)) }.sortedWith(compareBy({ !it.yours }, { -it.count }))
        return ChannelFacets(
            facets(all.flatMap { it.countries.distinct() }, place::isCountry) { Locale("", it.uppercase().let { c -> if (c == "UK") "GB" else c }).getDisplayCountry(Locale.ENGLISH).ifBlank { it.uppercase() } },
            facets(all.flatMap { it.languages.map(LanguageCodes::normalise).distinct() }, place::isLanguage) { Locale(it).getDisplayLanguage(Locale.ENGLISH).ifBlank { it } },
            facets(all.flatMap { it.categories }, { false }) { it }, all.size)
    }

    override suspend fun guide(channels: List<Channel>, fromMs: Long, toMs: Long): GuideWindow {
        val byKey = channelsByKey()
        val owners = channels.flatMap { c -> (byKey[c.key] ?: listOf(c)).map { it.pluginId to it } }.groupBy({ it.first }, { it.second })
        val plugins = activePlugins().associateBy { it.id }
        val programmes = each(owners.keys.mapNotNull { plugins[it] }.filter { Capability.EPG in it.capabilities }) { p -> p.guide(owners[p.id].orEmpty(), fromMs, toMs) }
        val byEpg = HashMap<Pair<String, String>, MutableList<EpgProgramme>>()
        for ((p, list) in programmes) for (prog in list.orEmpty()) byEpg.getOrPut(p.id to prog.epgId) { mutableListOf() } += prog
        return GuideWindow(fromMs, toMs, channels.map { c ->
            val list = (byKey[c.key] ?: listOf(c)).flatMap { own -> listOfNotNull(own.epgId, own.tvgId).distinct().flatMap { byEpg[own.pluginId to it].orEmpty() } }
            GuideRow(c, list.distinctBy { it.startMs }.sortedBy { it.startMs })
        })
    }

    override suspend fun nowNext(channel: Channel): LiveNowItem {
        val t = config.now()
        val list = guide(listOf(channel), t - 6 * WatchHttp.HOUR, t + 12 * WatchHttp.HOUR).rows.first().programmes
        val i = list.indexOfFirst { it.isOnAt(t) }
        return LiveNowItem(channel, list.getOrNull(i), if (i >= 0) list.getOrNull(i + 1) else list.firstOrNull { it.startMs > t })
    }

    override suspend fun setFavouriteChannel(key: WatchKey, favourite: Boolean) {
        update { s -> s.copy(favourites = if (favourite) s.favourites + key.value else s.favourites - key.value) }
    }

    // ── Search ───────────────────────────────────────────────────────────

    override suspend fun search(query: String): SearchResults = coroutineScope {
        val q = query.trim()
        if (q.length < 2) return@coroutineScope SearchResults(query, emptyList(), emptyList(), emptyList())
        val meta = async { runCatching { withTimeoutOrNull(config.timeoutMs) { metadata.search(q) } }.getOrNull().orEmpty() }
        val fromPlugins = async { each(activePlugins().filter { Capability.SEARCH in it.capabilities || Capability.CATALOG in it.capabilities }) { it.search(q, null) }.flatMap { it.second.orEmpty() } }
        val channels = async { runCatching { channels(ChannelFilter(query = q)).take(30) }.getOrDefault(emptyList()) }
        val all = (meta.await() + fromPlugins.await()).distinctBy { it.key }
        SearchResults(query, all.filter { it.type == TitleType.FILM }, all.filter { it.type == TitleType.SERIES }, channels.await())
    }

    // ── My list and progress ─────────────────────────────────────────────

    private val myListState = MutableStateFlow(store.state.value.myList)
    override val myList: StateFlow<List<MyListEntry>> = myListState.asStateFlow()
    private val favouritesState = MutableStateFlow(store.state.value.favourites.map(::WatchKey).toSet())
    override val favouriteChannels: StateFlow<Set<WatchKey>> = favouritesState.asStateFlow()
    private val continueState = MutableStateFlow(continueOf(store.state.value))
    override val continueWatching: StateFlow<List<ContinueItem>> = continueState.asStateFlow()

    /** Brings the derived flows up to date with the store at once (and again when a sync changes the store underneath). */
    private fun refresh(state: WatchState = store.state.value) {
        pluginsState.value = infos(state, status.value)
        myListState.value = state.myList
        favouritesState.value = state.favourites.map(::WatchKey).toSet()
        continueState.value = continueOf(state)
    }

    init {
        scope.launch { combine(store.state, status) { state, _ -> state }.collect { refresh(it) } }
    }

    private suspend fun update(change: (WatchState) -> WatchState) { refresh(store.update(change)) }

    private fun continueOf(state: WatchState): List<ContinueItem> {
        // One card per series: its latest episode.
        val latest = state.progress.values.sortedByDescending { it.updatedAtMs }.distinctBy { it.key.seriesKey ?: it.key }
        return latest.filter { !it.completed && it.positionMs > 30_000 && it.summary != null }.take(30).map { p ->
            val ep = p.key.seriesKey?.let { s -> Episode(p.key, s, p.key.season ?: 1, p.key.episode ?: 1, p.episodeName ?: "Episode ${p.key.episode}") }
            ContinueItem(p, p.summary!!, ep)
        }
    }

    override suspend fun setInMyList(summary: TitleSummary, inList: Boolean) {
        update { s ->
            val rest = s.myList.filterNot { it.key == summary.key }
            s.copy(myList = if (inList) listOf(MyListEntry(summary.key, config.now(), summary)) + rest else rest)
        }
    }

    override suspend fun progress(key: WatchKey): WatchProgress? = store.state.value.progress[key.value]

    override suspend fun saveProgress(progress: WatchProgress) {
        if (progress.key.kind == WatchKey.Kind.CHANNEL) return
        update { s -> s.copy(progress = s.progress + (progress.key.value to progress)) }
    }

    override suspend fun clearProgress(key: WatchKey) { update { s -> s.copy(progress = s.progress - key.value) } }

    fun close() { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }

    companion object {
        private val SERVER_KINDS = setOf(PluginKind.JELLYFIN, PluginKind.EMBY, PluginKind.PLEX_SERVER, PluginKind.LOCAL_FOLDER)
        /** The built-in plugins that play films for free, asked for the featured card. */
        private val FREE_FILM_PLUGINS = setOf(BuiltInPlugins.PLUTO, BuiltInPlugins.PLEX, BuiltInPlugins.YOUTUBE, BuiltInPlugins.ARCHIVE, BuiltInPlugins.PUBLIC_DOMAIN)
        private const val FEATURED_PROBE_MS = 6_000L
        /** The DD channels whose live videos are events or carry matches: DD National's and DD Sports' lives, DD News' and DD India's specials. */
        private val EVENT_CHANNELS = setOf("DDNational", PrasarBharatiPlugin.DD_SPORTS, "DDNews", "DDIndia")
        fun hash(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.trim().lowercase().toByteArray()).take(5).joinToString("") { "%02x".format(it) }
    }
}
