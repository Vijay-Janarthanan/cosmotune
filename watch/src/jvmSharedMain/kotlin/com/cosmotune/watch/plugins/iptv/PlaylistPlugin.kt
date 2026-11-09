package com.cosmotune.watch.plugins.iptv

import com.cosmotune.watch.http.WatchHttp
import com.cosmotune.watch.model.Channel
import com.cosmotune.watch.model.Container
import com.cosmotune.watch.model.EpgProgramme
import com.cosmotune.watch.model.PlayTarget
import com.cosmotune.watch.model.Stream
import com.cosmotune.watch.model.StreamProtocol
import com.cosmotune.watch.model.StreamSource
import com.cosmotune.watch.model.WatchKey
import com.cosmotune.watch.plugin.Capability
import com.cosmotune.watch.plugin.PluginKind
import com.cosmotune.watch.plugin.WatchPlugin
import com.cosmotune.watch.plugins.StreamGuess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Guides by URL, parsed once per refresh into a window from two hours ago to [aheadMs] ahead and kept in memory; the raw
 * file is on disk through [WatchHttp.stream] for [ttlMs].
 */
class GuideCache(private val http: WatchHttp, private val now: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = 6 * WatchHttp.HOUR, private val aheadMs: Long = 2 * WatchHttp.DAY) {
    private class Parsed(val atMs: Long, val byChannel: Map<String, List<EpgProgramme>>, val channels: List<XmltvChannel>)
    private val lock = Mutex()
    private val parsed = HashMap<String, Parsed>()

    suspend fun programmes(url: String, headers: Map<String, String> = emptyMap()): Map<String, List<EpgProgramme>> = load(url, headers).byChannel
    suspend fun channels(url: String, headers: Map<String, String> = emptyMap()): List<XmltvChannel> = load(url, headers).channels

    private suspend fun load(url: String, headers: Map<String, String>): Parsed = lock.withLock {
        parsed[url]?.takeIf { now() - it.atMs < ttlMs }?.let { return it }
        val t = now()
        val result = http.stream(url, headers, ttlMs) { XmltvParser.parse(it, t - 2 * WatchHttp.HOUR, t + aheadMs) }
        Parsed(t, result.programmes.groupBy { it.epgId }.mapValues { (_, v) -> v.sortedBy { it.startMs } }, result.channels).also { parsed[url] = it }
    }
}

/**
 * Live channels from M3U playlists and XMLTV guides: a user's own playlist, and the built-in free lists (Samsung TV Plus,
 * Plex and Pluto TV live through their public per-region playlists). A channel whose tvg-id is an iptv-org id is keyed
 * `c:iptv:<id>`, so the same channel from two lists (and from another member of a room) is the same key.
 */
open class PlaylistPlugin(
    override val id: String,
    override val displayName: String,
    override val badge: String,
    override val kind: PluginKind,
    private val http: WatchHttp,
    /** Playlist addresses (or local file paths); the first that answers for each is used. */
    private val playlists: () -> List<String>,
    /** Guide addresses; the playlist's own `url-tvg` is added. */
    private val guides: () -> List<String> = { emptyList() },
    private val guideCache: GuideCache = GuideCache(http),
    /** True for built-in public lists: an https stream may travel in a room. A user's own playlist never does. */
    private val publicStreams: Boolean = false,
    /** A country to tag channels with when the list does not say ("us" for a US region list). */
    private val defaultCountry: String? = null,
    private val playlistTtlMs: Long = 12 * WatchHttp.HOUR,
    private val directStreamsOnly: Boolean = false,
    private val includePlaylistGuides: Boolean = true,
) : WatchPlugin {
    override val capabilities = setOf(Capability.LIVE, Capability.EPG, Capability.SEARCH)
    private val lock = Mutex()
    private var loaded: Pair<List<Channel>, List<String>>? = null
    private var entries: Map<WatchKey, List<M3uEntry>> = emptyMap()
    private var loadedAt = 0L

    protected open fun keyOf(entry: M3uEntry): WatchKey =
        if (M3uParser.isIptvOrgId(entry.tvgId)) WatchKey.channel("iptv", entry.tvgId!!.substringBefore('@'))
        else WatchKey.channel(id, (entry.tvgId ?: entry.name).replace(':', '_').take(40))

    private suspend fun load(): Pair<List<Channel>, List<String>> = lock.withLock {
        loaded?.takeIf { System.currentTimeMillis() - loadedAt < playlistTtlMs }?.let { return it }
        val all = mutableListOf<M3uEntry>()
        val guideUrls = mutableListOf<String>()
        var lastError: Exception? = null
        for (source in playlists()) {
            try {
                val playlist = if (source.startsWith("http://") || source.startsWith("https://")) {
                    http.stream(source, ttlMs = playlistTtlMs) { M3uParser.parse(it.bufferedReader().lineSequence()) }
                } else M3uParser.parse(File(source).readText())
                all += playlist.entries
                if (includePlaylistGuides) guideUrls += playlist.guideUrls
            } catch (e: Exception) { lastError = e }
        }
        if (all.isEmpty() && lastError != null) throw lastError
        val byKey = all.filter { entry ->
            val url = entry.url
            if (directStreamsOnly) StreamGuess.playableScheme(url) &&
                (StreamGuess.protocol(url) != StreamProtocol.PROGRESSIVE || StreamGuess.container(url) != Container.UNKNOWN)
            else StreamGuess.playableScheme(url) || isLocalPath(url)
        }.groupBy(::keyOf)
        entries = byKey
        val channels = byKey.map { (key, list) ->
            val e = list.first()
            Channel(key, id, e.name, e.number, e.logo, e.groups.ifEmpty { listOf("General") },
                e.countries.ifEmpty { listOfNotNull(defaultCountry ?: e.tvgId?.takeIf(M3uParser::isIptvOrgId)?.substringAfter('.')?.substringBefore('@')) },
                e.languages, epgId = e.tvgId ?: e.tvgName ?: e.name, tvgId = e.tvgId, catchup = e.catchup, isRadio = e.radio)
        }
        val result = channels to (guides() + guideUrls).distinct()
        loaded = result; loadedAt = System.currentTimeMillis()
        result
    }

    override suspend fun channels(): List<Channel> = load().first

    override suspend fun search(query: String, type: com.cosmotune.watch.model.TitleType?) = emptyList<com.cosmotune.watch.model.TitleSummary>()

    override suspend fun guide(channels: List<Channel>, fromMs: Long, toMs: Long): List<EpgProgramme> {
        val urls = load().second
        if (urls.isEmpty()) return emptyList()
        val ids = channels.mapNotNull { it.epgId }.toSet() + channels.mapNotNull { it.tvgId }
        val out = mutableListOf<EpgProgramme>()
        for (url in urls) {
            val byChannel = runCatching { guideCache.programmes(url) }.getOrNull() ?: continue
            for (idValue in ids) byChannel[idValue]?.filter { it.stopMs > fromMs && it.startMs < toMs }?.let(out::addAll)
        }
        return out.distinctBy { "${it.epgId}|${it.startMs}" }
    }

    override suspend fun streams(target: PlayTarget): List<Stream> {
        if (target !is PlayTarget.Live) return emptyList()
        load()
        return entries[target.channel.key].orEmpty().map { e ->
            val url = if (e.url.contains("://")) e.url else File(e.url).toURI().toString()
            Stream(StreamSource(id, displayName, badge), "Live", e.groups.firstOrNull(), url, StreamGuess.protocol(url), StreamGuess.container(url),
                e.headers, height = StreamGuess.height(e.name), isLive = true, isPublic = publicStreams && StreamGuess.isHttps(url))
        }
    }

    override suspend fun health(): String? = runCatching { "${load().first.size} channels".let { null } }.getOrElse { it.message ?: "Playlist unreachable" }
    private fun isLocalPath(url: String) = Regex("^[A-Za-z]:[\\/]").containsMatchIn(url) || url.startsWith("/") || url.startsWith("file:")

    suspend fun channelCount(): Int = runCatching { load().first.size }.getOrDefault(0)
}
