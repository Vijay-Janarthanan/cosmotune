package com.cosmotune.watch.plugins.stremio

import com.cosmotune.watch.http.child
import com.cosmotune.watch.http.double
import com.cosmotune.watch.http.int
import com.cosmotune.watch.http.list
import com.cosmotune.watch.http.long
import com.cosmotune.watch.http.str
import com.cosmotune.watch.http.strings
import com.cosmotune.watch.http.yearOf
import com.cosmotune.watch.model.Channel
import com.cosmotune.watch.model.Episode
import com.cosmotune.watch.model.ExternalIds
import com.cosmotune.watch.model.PlayTarget
import com.cosmotune.watch.model.Season
import com.cosmotune.watch.model.Stream
import com.cosmotune.watch.model.StreamProtocol
import com.cosmotune.watch.model.StreamSource
import com.cosmotune.watch.model.Subtitle
import com.cosmotune.watch.model.SubtitleOrigin
import com.cosmotune.watch.model.Title
import com.cosmotune.watch.model.TitleSummary
import com.cosmotune.watch.model.TitleType
import com.cosmotune.watch.model.WatchKey
import com.cosmotune.watch.model.WatchProvider
import com.cosmotune.watch.plugin.CatalogDef
import com.cosmotune.watch.plugin.Capability
import com.cosmotune.watch.plugin.PluginKind
import com.cosmotune.watch.plugin.WatchPlugin
import com.cosmotune.watch.plugins.StreamGuess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement

/**
 * A Stremio-protocol add-on as a Watch plugin: the built-in Public Domain Movies, OpenSubtitles and WatchHub, Cinemeta, and
 * every add-on the user installs by its manifest URL. Torrent offers are resolved by the optional local playback bridge.
 */
class StremioPlugin(
    override val id: String,
    override val displayName: String,
    override val badge: String,
    val client: StremioClient,
    override val capabilities: Set<Capability>,
    override val kind: PluginKind = PluginKind.STREMIO_ADDON,
    /** WatchHub: streams are places a title streams elsewhere, never played. */
    private val whereToWatchOnly: Boolean = false,
    private val subtitleOrigin: SubtitleOrigin = SubtitleOrigin.ADDON,
    private val torrentsEnabled: () -> Boolean = { false },
) : WatchPlugin {
    private val lock = Mutex()
    private var cached: StremioManifest? = null
    private val source = StreamSource(id, displayName, badge)

    suspend fun manifest(): StremioManifest = lock.withLock { cached ?: client.manifest().also { cached = it } }

    override suspend fun health(): String? = runCatching { manifest(); null }.getOrElse { it.message ?: "Not responding" }

    override suspend fun catalogs(): List<CatalogDef> {
        if (Capability.CATALOG !in capabilities) return emptyList()
        return manifest().catalogs.filter { c -> c.requiredExtras.all { it == "skip" } && c.type in setOf("movie", "series") }
            .map { CatalogDef(id, "${it.type}/${it.id}", it.name, if (it.type == "movie") TitleType.FILM else TitleType.SERIES, pageSize = 100) }
    }

    override suspend fun catalogPage(catalog: CatalogDef, skip: Int): List<TitleSummary> {
        val (type, cid) = catalog.id.split('/', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } ?: return emptyList()
        return client.catalog(type, cid, if (skip > 0) mapOf("skip" to skip.toString()) else emptyMap()).mapNotNull(::summaryOf)
    }

    override suspend fun search(query: String, type: TitleType?): List<TitleSummary> {
        if (Capability.SEARCH !in capabilities && Capability.CATALOG !in capabilities) return emptyList()
        val searchable = manifest().catalogs.filter { "search" in it.extras && it.type in setOf("movie", "series") &&
            (type == null || (it.type == "movie") == (type == TitleType.FILM)) }
        return searchable.flatMap { runCatching { client.catalog(it.type, it.id, mapOf("search" to query)) }.getOrDefault(emptyList()) }
            .mapNotNull(::summaryOf).distinctBy { it.key }
    }

    override suspend fun title(key: WatchKey): Title? {
        val (type, metaId) = metaRef(key) ?: return null
        if (!manifest().supports("meta", type, metaId)) return null
        val meta = client.meta(type, metaId) ?: return null
        val summary = summaryOf(meta) ?: return null
        val videos = meta.list("videos")
        val seasons = videos.mapNotNull { it.int("season") }.distinct().sortedWith(compareBy({ it == 0 }, { it }))
            .map { n -> Season(summary.key, n, if (n == 0) "Specials" else "Season $n", videos.count { it.int("season") == n }) }
        val links = meta.list("links")
        return Title(summary, seasons = seasons,
            cast = (meta.strings("cast").ifEmpty { links.filter { it.str("category") == "Cast" }.mapNotNull { it.str("name") } })
                .map { com.cosmotune.watch.model.Person(it) },
            crew = meta.strings("director").map { com.cosmotune.watch.model.Person(it, "Director") },
            trailers = meta.list("trailers").mapNotNull { t -> t.str("source")?.let { com.cosmotune.watch.model.Trailer("Trailer", it) } },
            firstYear = summary.year)
    }

    override suspend fun episodes(series: WatchKey, season: Int): List<Episode> {
        val (type, metaId) = metaRef(series) ?: return emptyList()
        if (!manifest().supports("meta", type, metaId)) return emptyList()
        val meta = client.meta(type, metaId) ?: return emptyList()
        return meta.list("videos").filter { it.int("season") == season }.mapNotNull { v ->
            val n = v.int("episode") ?: v.int("number") ?: return@mapNotNull null
            Episode(WatchKey.episode(series.namespace, series.id, season, n), series, season, n, v.str("title") ?: v.str("name") ?: "Episode $n",
                v.str("overview") ?: v.str("description"), v.str("thumbnail"), v.str("released")?.take(10))
        }.sortedBy { it.number }
    }

    override suspend fun streams(target: PlayTarget): List<Stream> {
        if (whereToWatchOnly || Capability.STREAMS !in capabilities) return emptyList()
        val (type, streamId) = streamRef(target) ?: return emptyList()
        if (!manifest().supports("stream", type, streamId)) return emptyList()
        val raw = client.streams(type, streamId)
        val mapped = raw.mapNotNull { streamOf(it, type == "tv" || type == "channel") }
        if (!torrentsEnabled() && mapped.none { it.isPlayable } && type in setOf("movie", "series") &&
            raw.any { BundledTorrentPlayback.magnet(it.str("infoHash")) != null }) error(BundledTorrentPlayback.DISABLED)
        return mapped
    }

    override suspend fun whereToWatch(target: PlayTarget): List<WatchProvider> {
        if (!whereToWatchOnly) return emptyList()
        val (type, streamId) = streamRef(target) ?: return emptyList()
        if (!manifest().supports("stream", type, streamId)) return emptyList()
        return client.streams(type, streamId).mapNotNull { s ->
            val link = s.str("externalUrl") ?: return@mapNotNull null
            if (!link.startsWith("https://") && !link.startsWith("http://")) return@mapNotNull null
            WatchProvider(s.str("name")?.lineSequence()?.firstOrNull()?.trim() ?: "Elsewhere", type = "flatrate", link = link)
        }
    }

    override suspend fun subtitles(target: PlayTarget, stream: Stream?): List<Subtitle> {
        if (Capability.SUBTITLES !in capabilities) return emptyList()
        val (type, subId) = streamRef(target) ?: return emptyList()
        if (!manifest().supports("subtitles", type, subId)) return emptyList()
        val extra = buildMap {
            stream?.filename?.let { put("filename", it) }
            stream?.sizeBytes?.let { put("videoSize", it.toString()) }
        }
        return client.subtitles(type, subId, extra).mapNotNull { s ->
            val url = s.str("url")?.takeIf(StreamGuess::playableScheme) ?: return@mapNotNull null
            val lang = s.str("lang") ?: "und"
            Subtitle("$id:${s.str("id") ?: url.hashCode()}", url, lang, s.str("label") ?: languageName(lang),
                url.substringBefore('?').substringAfterLast('.', "").lowercase().takeIf { it in setOf("srt", "vtt", "ass", "ssa", "ttml") } ?: "srt",
                subtitleOrigin)
        }
    }

    override suspend fun channels(): List<Channel> {
        if (Capability.LIVE !in capabilities) return emptyList()
        return manifest().catalogs.filter { it.type in setOf("tv", "channel") && it.requiredExtras.isEmpty() }.flatMap { c ->
            runCatching { client.catalog(c.type, c.id) }.getOrDefault(emptyList()).mapNotNull { m ->
                val mid = m.str("id") ?: return@mapNotNull null
                Channel(WatchKey.channel(id, mid), id, m.str("name") ?: mid, logoUrl = m.str("logo") ?: m.str("poster"),
                    categories = m.strings("genres").ifEmpty { listOf(c.name) })
            }
        }
    }

    // ── Mapping ──────────────────────────────────────────────────────────

    fun summaryOf(meta: JsonElement): TitleSummary? {
        val metaId = meta.str("id") ?: meta.str("imdb_id") ?: return null
        val type = when (meta.str("type")) { "movie" -> TitleType.FILM; "series" -> TitleType.SERIES; else -> return null }
        val imdb = metaId.takeIf { it.startsWith("tt") } ?: meta.str("imdb_id")
        val namespace = if (metaId.startsWith("tt")) "imdb" else id
        val key = if (type == TitleType.FILM) WatchKey.film(namespace, metaId) else WatchKey.series(namespace, metaId)
        return TitleSummary(key, type, meta.str("name") ?: return null, yearOf(meta.str("releaseInfo") ?: meta.str("year") ?: meta.str("released")),
            posterUrl = meta.str("poster"), backdropUrl = meta.str("background"), logoUrl = meta.str("logo"),
            rating = meta.double("imdbRating"), overview = meta.str("description"),
            genres = meta.strings("genres").ifEmpty { meta.strings("genre") },
            runtimeMin = meta.str("runtime")?.let { Regex("(\\d+)").find(it)?.value?.toIntOrNull() },
            ids = ExternalIds(imdb = imdb, tmdb = meta.int("moviedb_id")), origin = id)
    }

    fun streamOf(s: JsonElement, live: Boolean = false): Stream? {
        val name = s.str("name")
        val description = s.str("description") ?: s.str("title")
        val hints = s.child("behaviorHints")
        val headers = hints.child("proxyHeaders").child("request")?.let { h -> (h as? kotlinx.serialization.json.JsonObject)?.mapNotNull { (k, v) -> v.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }?.let { k to it } }?.toMap() }.orEmpty()
        val label = name?.lineSequence()?.joinToString(" ")?.trim()?.ifBlank { null } ?: displayName
        val url = s.str("url")
        val ytId = s.str("ytId")
        val external = s.str("externalUrl")
        val magnet = if (!live && torrentsEnabled()) BundledTorrentPlayback.magnet(s.str("infoHash")) else null
        val index = s.int("fileIdx")?.takeIf { it in 0 until Int.MAX_VALUE }
        val (address, protocol) = when {
            url != null && StreamGuess.playableScheme(url) -> url to StreamGuess.protocol(url)
            ytId != null -> "https://www.youtube.com/watch?v=$ytId" to StreamProtocol.YOUTUBE
            magnet != null -> magnet to StreamProtocol.PROGRESSIVE
            external != null && StreamGuess.playableScheme(external) -> external to StreamProtocol.EXTERNAL
            else -> return null
        }
        val text = listOfNotNull(name, description, hints.str("filename"))
        val torrent = address == magnet
        return Stream(source, if (torrent) "$label · P2P" else label, description, address, protocol,
            StreamGuess.container(if (torrent) hints.str("filename").orEmpty() else address),
            if (torrent) emptyMap() else headers,
            height = StreamGuess.height(*text.toTypedArray()), hdr = StreamGuess.hdr(*text.toTypedArray()),
            videoCodec = StreamGuess.codec(*text.toTypedArray()),
            sizeBytes = hints.long("videoSize") ?: StreamGuess.size(description), filename = hints.str("filename"),
            bingeGroup = hints.str("bingeGroup"), isLive = live,
            subtitles = s.list("subtitles").mapNotNull { sub ->
                val su = sub.str("url")?.takeIf(StreamGuess::playableScheme) ?: return@mapNotNull null
                Subtitle("$id:${sub.str("id") ?: su.hashCode()}", su, sub.str("lang") ?: "und", sub.str("lang")?.let(::languageName) ?: "Subtitles", origin = SubtitleOrigin.STREAM)
            }, torrentFileIndex = index.takeIf { torrent },
            torrentTrackers = if (torrent) s.strings("sources").asSequence()
                .filter { it.startsWith("tracker:udp://") && it.length <= 256 }
                .map { it.removePrefix("tracker:") }.distinct().take(8).toList() else emptyList())
    }

    private fun metaRef(key: WatchKey): Pair<String, String>? {
        val type = when (key.kind) { WatchKey.Kind.FILM -> "movie"; WatchKey.Kind.SERIES -> "series"; else -> return null }
        return when (key.namespace) { "imdb", id -> type to key.id; else -> null }
    }

    private suspend fun streamRef(target: PlayTarget): Pair<String, String>? = when (target) {
        is PlayTarget.Film -> (target.ids.imdb ?: target.key.id.takeIf { target.key.namespace == id || target.key.namespace == "imdb" })?.let { "movie" to it }
        is PlayTarget.EpisodeOf -> {
            val series = target.seriesIds.imdb ?: target.key.id.takeIf { target.key.namespace == id || target.key.namespace == "imdb" }
            if (series == null) null
            else if (series.startsWith("tt")) "series" to "$series:${target.season}:${target.episode}"
            else {
                // An add-on's own series: its videos carry their own ids.
                val meta = runCatching { client.meta("series", series) }.getOrNull()
                meta.list("videos").firstOrNull { it.int("season") == target.season && (it.int("episode") ?: it.int("number")) == target.episode }
                    ?.str("id")?.let { "series" to it }
            }
        }
        is PlayTarget.Live -> if (target.channel.pluginId == id) "tv" to target.channel.key.id else null
        is PlayTarget.Item -> if (target.pluginId == id) "movie" to target.itemId else null
    }

    companion object {
        private val names = mapOf("eng" to "English", "en" to "English", "hin" to "Hindi", "tam" to "Tamil", "tel" to "Telugu", "mal" to "Malayalam",
            "spa" to "Spanish", "fre" to "French", "fra" to "French", "ger" to "German", "deu" to "German", "ita" to "Italian", "por" to "Portuguese",
            "pob" to "Portuguese (Brazil)", "ara" to "Arabic", "jpn" to "Japanese", "kor" to "Korean", "chi" to "Chinese", "zho" to "Chinese",
            "rus" to "Russian", "tur" to "Turkish", "dut" to "Dutch", "nld" to "Dutch", "pol" to "Polish", "swe" to "Swedish", "ben" to "Bengali")
        fun languageName(code: String) = names[code.lowercase()] ?: code
    }
}
