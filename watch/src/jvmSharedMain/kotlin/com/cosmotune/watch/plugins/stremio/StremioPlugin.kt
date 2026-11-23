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
import com.cosmotune.watch.plugins.SourceAddress
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import com.cosmotune.watch.FilterSupport
import com.cosmotune.watch.QueryAbility
import com.cosmotune.watch.WatchKind
import com.cosmotune.watch.WatchQuery

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
        // Every list that needs nothing but paging, of any kind but live TV (which is Live TV's): "movie", "series", and the kinds an
        // add-on names itself ("other", "anime"), whose titles are films or series it tells apart only on their pages.
        return manifest().catalogs.filter { c -> c.requiredExtras.all { it == "skip" } && c.type !in LIVE_TYPES }
            .map {
                val kind = when (it.type) { "movie" -> TitleType.FILM; "series" -> TitleType.SERIES; else -> null }
                CatalogDef(id, "${it.type}/${it.id}", it.name, kind, pageSize = 100, anyKind = kind == null)
            }
    }

    override suspend fun catalogPage(catalog: CatalogDef, skip: Int): List<TitleSummary> {
        val (type, cid) = catalog.id.split('/', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } ?: return emptyList()
        val anyKind = type !in KNOWN_TYPES
        return client.catalog(type, cid, if (skip > 0) mapOf("skip" to skip.toString()) else emptyMap()).mapNotNull { summaryOf(it, type, anyKind) }
    }

    override suspend fun search(query: String, type: TitleType?): List<TitleSummary> {
        if (Capability.SEARCH !in capabilities && Capability.CATALOG !in capabilities) return emptyList()
        val searchable = manifest().catalogs.filter { "search" in it.extras && it.type !in LIVE_TYPES &&
            (type == null || it.type in KNOWN_TYPES && (it.type == "movie") == (type == TitleType.FILM)) }
        return searchable.flatMap { c -> runCatching { client.catalog(c.type, c.id, mapOf("search" to query)) }.getOrDefault(emptyList())
            .mapNotNull { summaryOf(it, c.type, c.type !in KNOWN_TYPES) } }.distinctBy { it.key }
    }

    override suspend fun queryAbility(query: WatchQuery, currentYear: Int): QueryAbility =
        if (Capability.SEARCH !in capabilities && Capability.CATALOG !in capabilities) QueryAbility.SILENT
        else runCatching { queryPlan(manifest(), query, currentYear).ability }.getOrDefault(QueryAbility.SILENT)

    override suspend fun find(query: WatchQuery, currentYear: Int): List<TitleSummary> {
        val plan = queryPlan(manifest(), query, currentYear)
        val answers = plan.asks.map { ask ->
            runCatching { client.catalog(ask.catalog.type, ask.catalog.id, ask.extra) }.getOrDefault(emptyList())
                .mapNotNull { summaryOf(it, ask.catalog.type, ask.catalog.type !in KNOWN_TYPES) }
        }
        return com.cosmotune.watch.WatchSearchRules.merge(answers)
    }

    override suspend fun title(key: WatchKey): Title? {
        val (type, metaId) = metaRef(key) ?: return null
        if (!manifest().supports("meta", type, metaId)) return null
        val meta = client.meta(type, metaId) ?: return null
        // The add-on's own titles may be of a kind it names itself ("other"); a page shows whether it is a series.
        val summary = summaryOf(meta, type, anyKind = key.namespace == id) ?: return null
        val videos = meta.list("videos")
        val seasons = videos.mapNotNull { coordinates(it)?.first }.distinct().sortedWith(compareBy({ it == 0 }, { it }))
            .map { n -> Season(summary.key, n, if (n == 0) "Specials" else "Season $n", videos.count { coordinates(it)?.first == n }) }
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
        return meta.list("videos").filter { coordinates(it)?.first == season }.mapNotNull { v ->
            val n = coordinates(v)?.second ?: return@mapNotNull null
            Episode(WatchKey.episode(series.namespace, series.id, season, n), series, season, n, v.str("title") ?: v.str("name") ?: "Episode $n",
                v.str("overview") ?: v.str("description"), v.str("thumbnail"), v.str("released")?.take(10))
        }.distinctBy { it.key }.sortedBy { it.number }
    }

    override suspend fun streams(target: PlayTarget): List<Stream> {
        if (whereToWatchOnly || Capability.STREAMS !in capabilities) return emptyList()
        val (type, streamId) = streamRef(target) ?: return emptyList()
        if (!manifest().supports("stream", type, streamId)) return emptyList()
        // An anime add-on that numbers episodes from the series' first (S1 E15, not S2 E3) is asked again that way when it has nothing.
        val absolute = (target as? PlayTarget.EpisodeOf)?.absoluteEpisode?.takeIf { type == "series" && streamId.startsWith("tt") && streamId.count { it == ':' } == 2 }
        val raw = client.streams(type, streamId).ifEmpty {
            if (absolute == null) emptyList() else runCatching { client.streams(type, "${streamId.substringBefore(':')}:1:$absolute") }.getOrDefault(emptyList())
        }
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
                subtitleOrigin, provider = displayName)
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

    /**
     * The title [meta] describes. Its kind is the add-on's "movie" or "series"; with [anyKind] (a list or page of a kind the add-on
     * names itself, such as "other"), a title of another kind is a series when it lists episodes and a film otherwise. Live TV's
     * kinds are never titles.
     */
    fun summaryOf(meta: JsonElement, expectedType: String? = null, anyKind: Boolean = false): TitleSummary? {
        val metaId = meta.str("id") ?: meta.str("imdb_id") ?: return null
        val stated = meta.str("type") ?: expectedType
        val type = if (meta.list("videos").any { coordinates(it) != null }) TitleType.SERIES
            else when {
                stated == "movie" -> TitleType.FILM
                stated == "series" -> TitleType.SERIES
                anyKind && stated !in LIVE_TYPES -> TitleType.FILM
                else -> return null
            }
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
        val normalized = url?.let { SourceAddress.parse(it, headers) }
        val ytId = s.str("ytId")
        val external = s.str("externalUrl")
        val magnet = if (!live && torrentsEnabled()) BundledTorrentPlayback.magnet(s.str("infoHash")) else null
        val index = s.int("fileIdx")?.takeIf { it in 0 until Int.MAX_VALUE }
        val (address, protocol) = when {
            normalized != null -> normalized.url to StreamGuess.protocol(normalized.url)
            ytId != null && ytId.matches(Regex("[A-Za-z0-9_-]{11}")) -> "https://www.youtube.com/watch?v=$ytId" to StreamProtocol.YOUTUBE
            magnet != null -> magnet to StreamProtocol.PROGRESSIVE
            external != null && StreamGuess.playableScheme(external) -> external to StreamProtocol.EXTERNAL
            else -> return null
        }
        val text = listOfNotNull(name, description, hints.str("filename"))
        val torrent = address == magnet
        return Stream(source, if (torrent) "$label · P2P" else label, description, address, protocol,
            StreamGuess.container(if (torrent) hints.str("filename").orEmpty() else address),
            if (torrent) emptyMap() else normalized?.headers.orEmpty(),
            height = StreamGuess.height(*text.toTypedArray()), hdr = StreamGuess.hdr(*text.toTypedArray()),
            videoCodec = StreamGuess.codec(*text.toTypedArray()),
            sizeBytes = hints.long("videoSize")?.takeIf { it > 0 } ?: StreamGuess.size(description) ?: StreamGuess.size(name),
            filename = hints.str("filename"),
            durationMs = (s.long("durationMs") ?: hints.long("durationMs"))?.takeIf { it > 0 && !live },
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
                meta.list("videos").firstOrNull { coordinates(it) == (target.season to target.episode) }
                    ?.str("id")?.let { "series" to it }
            }
        }
        is PlayTarget.Live -> if (target.channel.pluginId == id) "tv" to target.channel.key.id else null
        is PlayTarget.Item -> if (target.pluginId == id) "movie" to target.itemId else null
    }

    /** One catalogue request of a search: the catalogue and the extras sent with it (`search`, `genre`). */
    data class StremioAsk(val catalog: StremioCatalog, val extra: Map<String, String>)
    data class StremioQueryPlan(val asks: List<StremioAsk>, val ability: QueryAbility)

    companion object {
        /**
         * How an add-on's manifest answers Watch search's [query]: the catalogues of its kind that take `search` (or, with a blank box,
         * that need nothing but `skip` or `genre`), with the genre sent in the add-on's own spelling where a catalogue declares a
         * `genre` extra offering it. Anime comes from catalogues of type `anime` or a genre option "Anime". With a blank box and a
         * year chip alone, Cinemeta-style "year" catalogues (a required genre extra whose options are years) are asked year by
         * year. Language is never sent: metas do not say a title's original language.
         */
        fun queryPlan(m: StremioManifest, query: WatchQuery, currentYear: Int): StremioQueryPlan {
            if (query.kind == WatchKind.LIVE) return StremioQueryPlan(emptyList(), QueryAbility.SILENT)
            val text = query.trimmed
            val ofKind = m.catalogs.filter { c ->
                c.type !in LIVE_TYPES && when (query.kind) { WatchKind.FILMS -> c.type == "movie"; WatchKind.SERIES -> c.type == "series"; else -> true }
            }
            val reachable = ofKind.filter { c -> if (text.isNotEmpty()) "search" in c.extras else c.requiredExtras.all { it == "skip" || it == "genre" } }
            if (reachable.isEmpty()) return StremioQueryPlan(emptyList(), QueryAbility.SILENT)
            fun animeOption(c: StremioCatalog) = c.options["genre"].orEmpty().firstOrNull { it.equals("anime", true) }
            fun extras(c: StremioCatalog): Map<String, String>? {
                val out = LinkedHashMap<String, String>()
                val options = c.options["genre"].orEmpty()
                query.genre?.let { g ->
                    if ("genre" !in c.extras) return null
                    out["genre"] = if (options.isEmpty()) g.label else g.optionIn(options) ?: return null
                }
                if (query.kind == WatchKind.ANIME && c.type != "anime") {
                    if (query.genre != null) return null
                    out["genre"] = animeOption(c) ?: return null
                }
                if (text.isNotEmpty()) out["search"] = text
                if ("genre" in c.requiredExtras && "genre" !in out) return null
                return out
            }
            val animeOk = query.kind != WatchKind.ANIME || reachable.any { it.type == "anime" || animeOption(it) != null }
            var asks = reachable.mapNotNull { c -> extras(c)?.let { StremioAsk(c, it) } }
            var year = FilterSupport.CHECKED
            val years = query.years
            if (text.isEmpty() && years != null && query.genre == null && query.kind != WatchKind.ANIME) {
                val range = years.range(currentYear)
                val byYear = reachable.filter(::isYearCatalog).flatMap { c ->
                    c.options["genre"].orEmpty().filter { it.toInt() in range }.sortedDescending().take(10).map { StremioAsk(c, mapOf("genre" to it)) }
                }
                if (byYear.isNotEmpty()) { asks = byYear; year = FilterSupport.SENT }
            }
            // Browsing asks one list of each kind (its most popular), not every catalogue the add-on has.
            if (text.isEmpty() && year != FilterSupport.SENT) asks = asks.distinctBy { it.catalog.type }.take(3)
            val ability = QueryAbility(
                answers = true,
                anime = if (animeOk) FilterSupport.SENT else FilterSupport.NONE,
                genre = if (query.genre == null || asks.isNotEmpty()) FilterSupport.SENT else FilterSupport.NONE,
                year = year,
                language = FilterSupport.NONE,
            )
            return StremioQueryPlan(if (animeOk) asks else emptyList(), ability)
        }

        /** Cinemeta's "year" lists: a required genre extra whose every option is a year. */
        internal fun isYearCatalog(c: StremioCatalog): Boolean =
            "genre" in c.requiredExtras && c.options["genre"].orEmpty().let { o -> o.isNotEmpty() && o.all { it.length == 4 && it.all(Char::isDigit) } }

        private val LIVE_TYPES = setOf("tv", "channel")
        private val KNOWN_TYPES = setOf("movie", "series")

        internal fun coordinates(video: JsonElement): Pair<Int, Int>? {
            val parts = video.str("id")?.split(':').orEmpty()
            val season = video.int("season") ?: parts.getOrNull(parts.size - 2)?.toIntOrNull() ?: return null
            val episode = video.int("episode") ?: video.int("number") ?: parts.lastOrNull()?.toIntOrNull() ?: return null
            return (season to episode).takeIf { season in 0..10_000 && episode in 1..100_000 }
        }
        private val names = mapOf("eng" to "English", "en" to "English", "hin" to "Hindi", "tam" to "Tamil", "tel" to "Telugu", "mal" to "Malayalam",
            "spa" to "Spanish", "fre" to "French", "fra" to "French", "ger" to "German", "deu" to "German", "ita" to "Italian", "por" to "Portuguese",
            "pob" to "Portuguese (Brazil)", "ara" to "Arabic", "jpn" to "Japanese", "kor" to "Korean", "chi" to "Chinese", "zho" to "Chinese",
            "rus" to "Russian", "tur" to "Turkish", "dut" to "Dutch", "nld" to "Dutch", "pol" to "Polish", "swe" to "Swedish", "ben" to "Bengali")
        fun languageName(code: String) = names[code.lowercase()] ?: code
    }
}
