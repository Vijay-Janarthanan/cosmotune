package com.cosmotune.watch.plugins.servers

import com.cosmotune.watch.http.WatchHttp
import com.cosmotune.watch.http.child
import com.cosmotune.watch.http.double
import com.cosmotune.watch.http.int
import com.cosmotune.watch.http.list
import com.cosmotune.watch.http.long
import com.cosmotune.watch.http.str
import com.cosmotune.watch.http.urlEncode
import com.cosmotune.watch.meta.NameMatch
import com.cosmotune.watch.model.AudioTrack
import com.cosmotune.watch.model.ExternalIds
import com.cosmotune.watch.model.PlayTarget
import com.cosmotune.watch.model.Stream
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
import kotlinx.serialization.json.JsonElement

/**
 * A Plex library: the user's own Plex Media Server (a token from plex.tv sign-in), or Plex's free films and series
 * (`vod.provider.plex.tv`, with an anonymous token Plex gives any client). Items match TMDB titles through their `Guid`
 * entries (`tmdb://603`, `imdb://tt0133093`). The token travels in the `X-Plex-Token` header.
 */
class PlexPlugin(
    override val id: String,
    override val displayName: String,
    override val badge: String,
    override val kind: PluginKind,
    private val http: WatchHttp,
    private val base: String,
    /** The token, or a function that fetches the anonymous one on first use. */
    private val token: suspend () -> String,
    private val clientId: String,
    private val free: Boolean = kind == PluginKind.PLEX_FREE,
) : WatchPlugin {
    override val capabilities = setOf(Capability.CATALOG, Capability.SEARCH, Capability.STREAMS)
    private val source = StreamSource(id, displayName, badge)
    private val lock = Mutex()
    private var cachedToken: String? = null

    suspend fun token(): String = lock.withLock { cachedToken ?: token.invoke().also { cachedToken = it } }

    private suspend fun headers(): Map<String, String> {
        val t = token()
        return mapOf("X-Plex-Token" to t, "X-Plex-Client-Identifier" to clientId, "X-Plex-Product" to "Cosmotune", "Accept" to "application/json")
    }

    private suspend fun get(path: String, ttl: Long = 30 * 60_000L) = http.getJson("$base$path", headers(), ttl).child("MediaContainer")

    override suspend fun health(): String? = runCatching { get(if (free) "/hubs" else "/identity", 0); null }.getOrElse { it.message ?: "Plex is unreachable" }

    override suspend fun catalogs(): List<CatalogDef> = if (free) {
        get("/hubs", WatchHttp.HOUR).list("Hub").filter { it.str("type") in setOf("movie", "show") && it.str("key") != null }.take(8).map {
            CatalogDef(id, it.str("key")!!, it.str("title") ?: "Free on Plex", if (it.str("type") == "movie") TitleType.FILM else TitleType.SERIES, pageSize = 30)
        }
    } else {
        get("/library/sections", WatchHttp.HOUR).list("Directory").filter { it.str("type") in setOf("movie", "show") }.map {
            CatalogDef(id, "/library/sections/${it.str("key")}/recentlyAdded", "${it.str("title")} on $displayName", if (it.str("type") == "movie") TitleType.FILM else TitleType.SERIES, pageSize = 30)
        }
    }

    override suspend fun catalogPage(catalog: CatalogDef, skip: Int): List<TitleSummary> {
        val sep = if ('?' in catalog.id) '&' else '?'
        return get("${catalog.id}${sep}X-Plex-Container-Start=$skip&X-Plex-Container-Size=${catalog.pageSize}&includeGuids=1", WatchHttp.HOUR)
            .list("Metadata").mapNotNull(::summaryOf)
    }

    override suspend fun search(query: String, type: TitleType?): List<TitleSummary> {
        val c = if (free) get("/library/search?query=${query.urlEncode()}&searchTypes=movies,tv&limit=30&includeGuids=1", 10 * 60_000L)
            else get("/hubs/search?query=${query.urlEncode()}&limit=20&includeGuids=1", 10 * 60_000L)
        val items = c.list("Metadata") + c.list("SearchResults").flatMap { it.list("SearchResult").mapNotNull { r -> r.child("Metadata") } } +
            c.list("Hub").flatMap { it.list("Metadata") }
        return items.mapNotNull(::summaryOf).filter { type == null || it.type == type }.distinctBy { it.key }
    }

    fun summaryOf(m: JsonElement): TitleSummary? {
        val type = when (m.str("type")) { "movie" -> TitleType.FILM; "show" -> TitleType.SERIES; else -> return null }
        val rk = m.str("ratingKey") ?: return null
        val ids = ids(m)
        val key = when {
            ids.tmdb != null -> if (type == TitleType.FILM) WatchKey.tmdbFilm(ids.tmdb) else WatchKey.tmdbSeries(ids.tmdb)
            ids.imdb != null -> if (type == TitleType.FILM) WatchKey.film("imdb", ids.imdb) else WatchKey.series("imdb", ids.imdb)
            else -> if (type == TitleType.FILM) WatchKey.film(id, rk) else WatchKey.series(id, rk)
        }
        return TitleSummary(key, type, m.str("title") ?: return null, m.int("year"), posterUrl = image(m.str("thumb")), backdropUrl = image(m.str("art")),
            rating = m.double("audienceRating") ?: m.double("rating"), overview = m.str("summary"), genres = m.list("Genre").mapNotNull { it.str("tag") },
            runtimeMin = m.long("duration")?.let { (it / 60_000).toInt() }, ids = ids, origin = id, badge = if (free) "FREE · PLEX" else null)
    }

    private fun image(path: String?): String? = when {
        path == null -> null
        path.startsWith("http") -> path
        else -> "$base$path"
    }

    private fun ids(m: JsonElement): ExternalIds {
        val guids = m.list("Guid").mapNotNull { it.str("id") } + listOfNotNull(m.str("guid"))
        return ExternalIds(tmdb = guids.firstOrNull { it.startsWith("tmdb://") }?.removePrefix("tmdb://")?.toIntOrNull(),
            imdb = guids.firstOrNull { it.startsWith("imdb://") }?.removePrefix("imdb://"),
            tvdb = guids.firstOrNull { it.startsWith("tvdb://") }?.removePrefix("tvdb://")?.toIntOrNull())
    }

    private suspend fun find(type: TitleType, name: String, year: Int?, wanted: ExternalIds, key: WatchKey?): JsonElement? {
        if (key != null && key.namespace == id) return get("/library/metadata/${key.id}?includeGuids=1").list("Metadata").firstOrNull()
        val c = if (free) get("/library/search?query=${name.urlEncode()}&searchTypes=${if (type == TitleType.FILM) "movies" else "tv"}&limit=20&includeGuids=1", WatchHttp.HOUR)
            else get("/search?query=${name.urlEncode()}&type=${if (type == TitleType.FILM) 1 else 2}&includeGuids=1", WatchHttp.HOUR)
        val items = c.list("Metadata") + c.list("SearchResults").flatMap { it.list("SearchResult").mapNotNull { r -> r.child("Metadata") } }
        items.firstOrNull { val i = ids(it); (wanted.tmdb != null && i.tmdb == wanted.tmdb) || (wanted.imdb != null && i.imdb == wanted.imdb) }?.let { return it }
        return items.firstOrNull { NameMatch.same(it.str("title").orEmpty(), name) && (year == null || it.int("year") == null || kotlin.math.abs(it.int("year")!! - year) <= 1) }
    }

    override suspend fun streams(target: PlayTarget): List<Stream> = when (target) {
        is PlayTarget.Film -> find(TitleType.FILM, target.name, target.year, target.ids, target.key)?.let { detailStreams(it) }.orEmpty()
        is PlayTarget.EpisodeOf -> {
            val show = find(TitleType.SERIES, target.seriesName, target.year, target.seriesIds, target.key.seriesKey)
            val rk = show?.str("ratingKey")
            if (rk == null) emptyList() else get("/library/metadata/$rk/allLeaves", 10 * 60_000L).list("Metadata")
                .firstOrNull { it.int("parentIndex") == target.season && it.int("index") == target.episode }?.let { detailStreams(it) }.orEmpty()
        }
        else -> emptyList()
    }

    private suspend fun detailStreams(item: JsonElement): List<Stream> {
        val full = if (item.list("Media").isEmpty()) item.str("ratingKey")?.let { get("/library/metadata/$it", 10 * 60_000L).list("Metadata").firstOrNull() } ?: item else item
        val h = headers().filterKeys { it != "Accept" }
        return full.list("Media").flatMap { media ->
            media.list("Part").mapNotNull { part ->
                val partKey = part.str("key") ?: return@mapNotNull null
                val url = if (partKey.startsWith("http")) partKey else "$base$partKey"
                val streams = part.list("Stream")
                val audio = streams.filter { it.int("streamType") == 2 }.map { a ->
                    AudioTrack(a.str("id") ?: "", a.str("languageCode"), a.str("displayTitle") ?: a.str("language") ?: "Audio", a.str("codec"), a.int("channels"))
                }
                val height = media.int("height") ?: media.str("videoResolution")?.let { StreamGuess.height("${it}p") }
                Stream(source, if (free) "Free with ads" else "Direct play${media.str("videoCodec")?.let { " · ${it.uppercase()}" } ?: ""}",
                    listOfNotNull(height?.let { if (it >= 2000) "4K" else "${it}p" }, media.str("container")?.uppercase(), part.long("size")?.let { "%.1f GB".format(it / 1e9) }).joinToString(" · "),
                    url, StreamGuess.protocol(url, part.str("container")?.let { if (it == "mpd") "application/dash+xml" else null }), StreamGuess.container(url, media.str("container")),
                    h, height, videoCodec = media.str("videoCodec"), audioLanguages = audio.mapNotNull { it.language }, audioTracks = audio,
                    sizeBytes = part.long("size"), filename = part.str("file")?.substringAfterLast('/'), durationMs = part.long("duration") ?: media.long("duration"),
                    hasAds = free)
            }
        }
    }

    companion object {
        /** Plex's free catalogue for clients with no account: an anonymous token from plex.tv. */
        suspend fun anonymousToken(http: WatchHttp, clientId: String, plexTv: String = "https://plex.tv"): String =
            http.postJson("$plexTv/api/v2/users/anonymous", "", mapOf("X-Plex-Client-Identifier" to clientId, "X-Plex-Product" to "Cosmotune"))
                .str("authToken") ?: error("Plex gave no token")
    }
}

