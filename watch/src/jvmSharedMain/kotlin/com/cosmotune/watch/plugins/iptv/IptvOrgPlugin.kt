package com.cosmotune.watch.plugins.iptv

import com.cosmotune.watch.http.WatchHttp
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.decodeFromStream

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
        // The four lists are about 26 MB of JSON. Each is read as it streams in, straight into the few fields used here,
        // never held whole as text or as a tree of JSON objects (as trees they came to well over the 192 MB a TV gives an
        // app, and the app closed when Watch opened). They are read one after another for the same reason.
        val channels = list("channels", ttl, ApiChannel.serializer())
        val streams = list("streams", 6 * WatchHttp.HOUR, ApiStream.serializer())
        val feeds = runCatching { list("feeds", ttl, ApiFeed.serializer()) }.getOrNull()
        val logos = runCatching { list("logos", ttl, ApiLogo.serializer()) }.getOrNull()
        val languages = HashMap<String, MutableSet<String>>()
        feeds?.forEach { f -> f.channel?.let { ch -> languages.getOrPut(ch) { mutableSetOf() } += f.languages } }
        val logoOf = HashMap<String, String>()
        logos?.forEach { l -> val ch = l.channel; val url = l.url; if (ch != null && url != null && (l.feed == null || ch !in logoOf)) logoOf[ch] = url }
        val meta = channels.mapNotNull { c -> c.id?.let { it to c } }.toMap()
        val rows = HashMap<WatchKey, MutableList<Row>>()
        val named = HashMap<WatchKey, String>()
        for (s in streams) {
            val url = s.url?.takeIf(StreamGuess::playableScheme) ?: continue
            val ch = s.channel
            val c = ch?.let { meta[it] }
            if (c != null && (c.isNsfw == true || c.closed != null)) continue
            val key = if (ch != null) WatchKey.channel("iptv", ch) else WatchKey.channel("iptv", "x-" + Integer.toHexString(url.hashCode()))
            if (ch == null) named[key] = s.title ?: continue
            val headers = buildMap { s.userAgent?.let { put("User-Agent", it) }; s.referrer?.let { put("Referer", it) } }
            rows.getOrPut(key) { mutableListOf() } += Row(url, s.title, s.quality, headers, s.labels)
        }
        streamsByKey = rows
        val list = rows.map { (key, r) ->
            val ch = key.id
            val c = meta[ch]
            Channel(key, id, c?.name ?: named[key] ?: r.first().title ?: ch, logoUrl = logoOf[ch] ?: c?.logo,
                categories = c?.categories?.map { it.replaceFirstChar(Char::titlecase) }?.ifEmpty { null } ?: listOf("General"),
                countries = listOfNotNull(c?.country?.lowercase()),
                languages = (languages[ch]?.toList() ?: c?.languages.orEmpty()).map { it.lowercase() },
                epgId = ch.takeIf { c != null }, tvgId = ch.takeIf { c != null }, labels = r.flatMap { it.labels }.distinct())
        }.sortedBy { it.name.lowercase() }
        channelList = list
        list
    }

    /** One of the API's lists ([name].json), decoded as it streams from the network or the disk cache into [item]s. */
    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun <T> list(name: String, ttlMs: Long, item: KSerializer<T>): List<T> =
        http.stream("$apiBase/$name.json", mapOf("Accept" to "application/json"), ttlMs = ttlMs) { input ->
            http.json.decodeFromStream(ListSerializer(item), input)
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

    // The fields of the API's lists that are read; everything else in them is skipped while decoding.
    @Serializable private class ApiChannel(
        val id: String? = null, val name: String? = null, val country: String? = null, val categories: List<String> = emptyList(),
        val languages: List<String> = emptyList(), @SerialName("is_nsfw") val isNsfw: Boolean? = null, val closed: String? = null,
        val logo: String? = null,
    )
    @Serializable private class ApiStream(
        val channel: String? = null, val title: String? = null, val url: String? = null, val quality: String? = null,
        val labels: List<String> = emptyList(), @SerialName("user_agent") val userAgent: String? = null, val referrer: String? = null,
    )
    @Serializable private class ApiFeed(val channel: String? = null, val languages: List<String> = emptyList())
    @Serializable private class ApiLogo(val channel: String? = null, val feed: String? = null, val url: String? = null)
}
