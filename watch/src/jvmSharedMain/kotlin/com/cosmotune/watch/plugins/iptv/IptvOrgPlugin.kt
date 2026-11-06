package com.cosmotune.watch.plugins.iptv

import com.cosmotune.watch.http.WatchHttp
import com.cosmotune.watch.http.bool
import com.cosmotune.watch.http.str
import com.cosmotune.watch.http.strings
import com.cosmotune.watch.model.Channel
import com.cosmotune.watch.model.EpgProgramme
import com.cosmotune.watch.model.PlayTarget
import com.cosmotune.watch.model.Stream
import com.cosmotune.watch.model.StreamSource
import com.cosmotune.watch.model.WatchKey
import com.cosmotune.watch.plugin.BuiltInPlugins
import com.cosmotune.watch.plugin.Capability
import com.cosmotune.watch.plugin.PluginKind
import com.cosmotune.watch.plugin.WatchPlugin
import com.cosmotune.watch.plugins.StreamGuess
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray

/**
 * iptv-org's public channel lists (Unlicense) through its JSON API: channels with country and categories, feeds with
 * languages, logos and streams. NSFW and closed channels are left out. iptv-org hosts no guide; guides come from the XMLTV
 * addresses in [guides] (settable on the Plugins page) wherever their channel ids are iptv-org ids.
 */
class IptvOrgPlugin(
    private val http: WatchHttp,
    private val apiBase: String = "https://iptv-org.github.io/api",
    private val guides: () -> List<String> = { emptyList() },
    private val guideCache: GuideCache = GuideCache(http),
) : WatchPlugin {
    override val id = BuiltInPlugins.IPTV_ORG
    override val kind = PluginKind.IPTV_ORG
    override val displayName = "iptv-org free channels"
    override val badge = "IPTV"
    override val capabilities = setOf(Capability.LIVE, Capability.EPG, Capability.SEARCH)

    private class Row(val url: String, val title: String?, val quality: String?, val headers: Map<String, String>, val labels: List<String>)
    private val lock = Mutex()
    private var channelList: List<Channel>? = null
    private var streamsByKey: Map<WatchKey, List<Row>> = emptyMap()

    private suspend fun load(): List<Channel> = lock.withLock {
        channelList?.let { return it }
        val ttl = WatchHttp.DAY
        val (channels, streams, feeds, logos) = coroutineScope {
            val c = async { http.getJson("$apiBase/channels.json", ttlMs = ttl) as JsonArray }
            val s = async { http.getJson("$apiBase/streams.json", ttlMs = 6 * WatchHttp.HOUR) as JsonArray }
            val f = async { runCatching { http.getJson("$apiBase/feeds.json", ttlMs = ttl) as JsonArray }.getOrNull() }
            val l = async { runCatching { http.getJson("$apiBase/logos.json", ttlMs = ttl) as JsonArray }.getOrNull() }
            listOf(c.await(), s.await(), f.await(), l.await())
        }
        val languages = HashMap<String, MutableSet<String>>()
        feeds?.forEach { f -> f.str("channel")?.let { ch -> languages.getOrPut(ch) { mutableSetOf() } += f.strings("languages") } }
        val logoOf = HashMap<String, String>()
        logos?.forEach { l -> val ch = l.str("channel"); val url = l.str("url"); if (ch != null && url != null && (l.str("feed") == null || ch !in logoOf)) logoOf[ch] = url }
        val meta = channels!!.mapNotNull { c -> c.str("id")?.let { it to c } }.toMap()
        val rows = HashMap<WatchKey, MutableList<Row>>()
        val named = HashMap<WatchKey, String>()
        for (s in streams!!) {
            val url = s.str("url")?.takeIf(StreamGuess::playableScheme) ?: continue
            val ch = s.str("channel")
            val c = ch?.let { meta[it] }
            if (c != null && (c.bool("is_nsfw") == true || c.str("closed") != null)) continue
            val key = if (ch != null) WatchKey.channel("iptv", ch) else WatchKey.channel("iptv", "x-" + Integer.toHexString(url.hashCode()))
            if (ch == null) named[key] = s.str("title") ?: continue
            val headers = buildMap { s.str("user_agent")?.let { put("User-Agent", it) }; s.str("referrer")?.let { put("Referer", it) } }
            rows.getOrPut(key) { mutableListOf() } += Row(url, s.str("title"), s.str("quality"), headers, listOfNotNull(s.str("label")))
        }
        streamsByKey = rows
        val list = rows.map { (key, r) ->
            val ch = key.id
            val c = meta[ch]
            Channel(key, id, c?.str("name") ?: named[key] ?: r.first().title ?: ch, logoUrl = logoOf[ch] ?: c?.str("logo"),
                categories = c?.strings("categories")?.map { it.replaceFirstChar(Char::titlecase) }?.ifEmpty { null } ?: listOf("General"),
                countries = listOfNotNull(c?.str("country")?.lowercase()),
                languages = (languages[ch]?.toList() ?: c?.strings("languages").orEmpty()).map { it.lowercase() },
                epgId = ch.takeIf { c != null }, tvgId = ch.takeIf { c != null }, labels = r.flatMap { it.labels }.distinct())
        }.sortedBy { it.name.lowercase() }
        channelList = list
        list
    }

    override suspend fun channels(): List<Channel> = load()

    override suspend fun streams(target: PlayTarget): List<Stream> {
        if (target !is PlayTarget.Live) return emptyList()
        load()
        return streamsByKey[target.channel.key].orEmpty().map { r ->
            Stream(StreamSource(id, displayName, badge), r.quality ?: "Live", r.labels.joinToString(" · ").ifBlank { null }, r.url,
                StreamGuess.protocol(r.url), StreamGuess.container(r.url), r.headers, height = r.quality?.let { StreamGuess.height(it) },
                isLive = true, isPublic = StreamGuess.isHttps(r.url) && r.headers.isEmpty())
        }
    }

    override suspend fun guide(channels: List<Channel>, fromMs: Long, toMs: Long): List<EpgProgramme> {
        val ids = channels.mapNotNull { it.epgId }.toSet()
        if (ids.isEmpty()) return emptyList()
        return guides().flatMap { url ->
            val byChannel = runCatching { guideCache.programmes(url) }.getOrNull() ?: return@flatMap emptyList()
            ids.flatMap { byChannel[it].orEmpty() }.filter { it.stopMs > fromMs && it.startMs < toMs }
        }
    }

    override suspend fun health(): String? = runCatching { load(); null }.getOrElse { it.message ?: "iptv-org is unreachable" }
}
