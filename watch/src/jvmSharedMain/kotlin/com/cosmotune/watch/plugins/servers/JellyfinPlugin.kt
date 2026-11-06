package com.cosmotune.watch.plugins.servers

import com.cosmotune.watch.http.WatchHttp
import com.cosmotune.watch.http.bool
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
import com.cosmotune.watch.model.StreamProtocol
import com.cosmotune.watch.model.StreamSource
import com.cosmotune.watch.model.Subtitle
import com.cosmotune.watch.model.SubtitleOrigin
import com.cosmotune.watch.model.TitleSummary
import com.cosmotune.watch.model.TitleType
import com.cosmotune.watch.model.WatchKey
import com.cosmotune.watch.plugin.CatalogDef
import com.cosmotune.watch.plugin.Capability
import com.cosmotune.watch.plugin.PluginKind
import com.cosmotune.watch.plugin.WatchPlugin
import com.cosmotune.watch.plugins.StreamGuess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What a server sign-in leaves behind; sealed in the store. The password itself is never kept. */
@Serializable data class ServerSession(val token: String, val userId: String, val serverName: String? = null)

/**
 * A Jellyfin or Emby server (the two speak nearly the same API). Items are matched to TMDB and IMDb titles through their
 * `ProviderIds`, or by name and year. Direct play is offered first, the server's HLS transcode after it. The token goes
 * in a header (`X-Emby-Token`, which both servers read), never in a stream address.
 */
class JellyfinPlugin(
    override val id: String,
    override val displayName: String,
    private val http: WatchHttp,
    server: String,
    private val session: ServerSession,
    private val emby: Boolean = false,
) : WatchPlugin {
    override val kind = if (emby) PluginKind.EMBY else PluginKind.JELLYFIN
    override val badge = if (emby) "EMBY" else "JF"
    override val capabilities = setOf(Capability.CATALOG, Capability.SEARCH, Capability.STREAMS, Capability.SUBTITLES)
    private val server = normalise(server)
    private val auth = mapOf("X-Emby-Token" to session.token)
    private val source = StreamSource(id, displayName, badge)
    private val fields = "ProviderIds,Overview,ProductionYear,Genres,CommunityRating,RunTimeTicks,MediaSources,ImageTags,BackdropImageTags"

    override suspend fun health(): String? = runCatching { http.getJson("$server/Users/${session.userId}", auth, 0); null }
        .getOrElse { if ((it as? com.cosmotune.watch.http.WatchHttpException)?.code == 401) "Signed out: sign in again" else it.message ?: "Server unreachable" }

    override suspend fun catalogs(): List<CatalogDef> = http.getJson("$server/Users/${session.userId}/Views", auth, WatchHttp.HOUR).list("Items")
        .filter { it.str("CollectionType") in setOf("movies", "tvshows") }
        .map { v -> CatalogDef(id, v.str("Id")!!, "${v.str("Name")} on $displayName", if (v.str("CollectionType") == "movies") TitleType.FILM else TitleType.SERIES, pageSize = 30) }

    override suspend fun catalogPage(catalog: CatalogDef, skip: Int): List<TitleSummary> = items(
        "ParentId=${catalog.id}&IncludeItemTypes=Movie,Series&Recursive=true&SortBy=DateCreated&SortOrder=Descending&StartIndex=$skip&Limit=${catalog.pageSize}")
        .mapNotNull(::summaryOf)

    override suspend fun search(query: String, type: TitleType?): List<TitleSummary> {
        val types = when (type) { TitleType.FILM -> "Movie"; TitleType.SERIES -> "Series"; null -> "Movie,Series" }
        return items("SearchTerm=${query.urlEncode()}&IncludeItemTypes=$types&Recursive=true&Limit=30").mapNotNull(::summaryOf)
    }

    private suspend fun items(query: String, ttl: Long = 10 * 60_000L): List<JsonElement> =
        http.getJson("$server/Users/${session.userId}/Items?$query&Fields=$fields", auth, ttl).list("Items")

    fun summaryOf(item: JsonElement): TitleSummary? {
        val itemId = item.str("Id") ?: return null
        val type = when (item.str("Type")) { "Movie" -> TitleType.FILM; "Series" -> TitleType.SERIES; else -> return null }
        val ids = ids(item)
        val key = when {
            ids.tmdb != null -> if (type == TitleType.FILM) WatchKey.tmdbFilm(ids.tmdb) else WatchKey.tmdbSeries(ids.tmdb)
            ids.imdb != null -> if (type == TitleType.FILM) WatchKey.film("imdb", ids.imdb) else WatchKey.series("imdb", ids.imdb)
            else -> if (type == TitleType.FILM) WatchKey.film(id, itemId) else WatchKey.series(id, itemId)
        }
        return TitleSummary(key, type, item.str("Name") ?: return null, item.int("ProductionYear"),
            posterUrl = "$server/Items/$itemId/Images/Primary?maxHeight=600",
            backdropUrl = if (item.list("BackdropImageTags").isNotEmpty()) "$server/Items/$itemId/Images/Backdrop?maxWidth=1280" else null,
            rating = item.double("CommunityRating"), overview = item.str("Overview"), genres = item.list("Genres").mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content },
            runtimeMin = item.long("RunTimeTicks")?.let { (it / 600_000_000L).toInt() }, ids = ids, origin = id)
    }

    private fun ids(item: JsonElement): ExternalIds {
        val p = item.child("ProviderIds")
        return ExternalIds(tmdb = (p.str("Tmdb") ?: p.str("tmdb"))?.toIntOrNull(), imdb = p.str("Imdb") ?: p.str("imdb"), tvdb = (p.str("Tvdb") ?: p.str("tvdb"))?.toIntOrNull())
    }

    /** The server item for a title: same TMDB or IMDb id, else same name within a year. */
    private suspend fun find(type: TitleType, name: String, year: Int?, wanted: ExternalIds, key: WatchKey?): JsonElement? {
        if (key != null && key.namespace == id) return items("Ids=${key.id}").firstOrNull()
        val candidates = items("SearchTerm=${name.urlEncode()}&IncludeItemTypes=${if (type == TitleType.FILM) "Movie" else "Series"}&Recursive=true&Limit=20", WatchHttp.HOUR)
        candidates.firstOrNull { val i = ids(it); (wanted.tmdb != null && i.tmdb == wanted.tmdb) || (wanted.imdb != null && i.imdb == wanted.imdb) }?.let { return it }
        return candidates.firstOrNull { NameMatch.same(it.str("Name").orEmpty(), name) && (year == null || it.int("ProductionYear") == null || kotlin.math.abs(it.int("ProductionYear")!! - year) <= 1) }
    }

    override suspend fun streams(target: PlayTarget): List<Stream> = when (target) {
        is PlayTarget.Film -> find(TitleType.FILM, target.name, target.year, target.ids, target.key)?.let { streamsOf(it) }.orEmpty()
        is PlayTarget.EpisodeOf -> {
            val series = find(TitleType.SERIES, target.seriesName, target.year, target.seriesIds, target.key.seriesKey)
            val sid = series?.str("Id")
            if (sid == null) emptyList() else {
                val episodes = http.getJson("$server/Shows/$sid/Episodes?UserId=${session.userId}&Season=${target.season}&Fields=$fields", auth, 10 * 60_000L).list("Items")
                episodes.firstOrNull { it.int("IndexNumber") == target.episode && (it.int("ParentIndexNumber") ?: target.season) == target.season }?.let { streamsOf(it) }.orEmpty()
            }
        }
        is PlayTarget.Item -> if (target.pluginId == id) items("Ids=${target.itemId}").firstOrNull()?.let { streamsOf(it) }.orEmpty() else emptyList()
        is PlayTarget.Live -> emptyList()
    }

    private fun streamsOf(item: JsonElement): List<Stream> {
        val itemId = item.str("Id") ?: return emptyList()
        return item.list("MediaSources").flatMap { ms ->
            val msId = ms.str("Id") ?: itemId
            val streams = ms.list("MediaStreams")
            val video = streams.firstOrNull { it.str("Type") == "Video" }
            val audio = streams.filter { it.str("Type") == "Audio" }
            val subs = streams.filter { it.str("Type") == "Subtitle" }
            val height = video.int("Height")
            val codec = video.str("Codec")
            val hdr = video.str("VideoRangeType")?.takeIf { it != "SDR" }?.let { if (it.startsWith("DOVI")) "DV" else if (it.startsWith("HDR10")) "HDR10" else it }
            val container = ms.str("Container")
            val durationMs = ms.long("RunTimeTicks")?.div(10_000)
            val audioTracks = audio.mapIndexed { i, a -> AudioTrack("${a.int("Index") ?: i}", a.str("Language"), a.str("DisplayTitle") ?: a.str("Language") ?: "Audio ${i + 1}", a.str("Codec"), a.int("Channels"), a.bool("IsDefault") == true) }
            val external = subs.filter { it.bool("IsExternal") == true }.mapNotNull { s ->
                val index = s.int("Index") ?: return@mapNotNull null
                val format = (s.str("Codec") ?: "srt").lowercase().let { if (it == "subrip") "srt" else it }
                Subtitle("$id:$itemId:$index", "$server/Videos/$itemId/$msId/Subtitles/$index/0/Stream.$format?api_key=${session.token}",
                    s.str("Language") ?: "und", s.str("DisplayTitle") ?: s.str("Language") ?: "Subtitles", format, SubtitleOrigin.SERVER,
                    forced = s.bool("IsForced") == true)
            }
            val size = ms.long("Size")
            val line = listOfNotNull(height?.let { if (it >= 2000) "4K" else "${it}p" }, hdr, container?.uppercase(), size?.let { "%.1f GB".format(it / 1e9) }).joinToString(" · ")
            val direct = Stream(source, "Direct play${codec?.let { " · ${it.uppercase()}" } ?: ""}", line, "$server/Videos/$itemId/stream?static=true&MediaSourceId=$msId",
                StreamProtocol.PROGRESSIVE, StreamGuess.container("x.${container?.substringBefore(',')}"), auth, height, hdr, codec,
                audioLanguages = audioTracks.mapNotNull { it.language }, audioTracks = audioTracks, subtitles = external, sizeBytes = size,
                filename = ms.str("Path")?.substringAfterLast('/')?.substringAfterLast('\\') ?: ms.str("Name"), durationMs = durationMs)
            val hls = direct.copy(label = "Transcoded · HLS", description = "Converted by the server for this device",
                url = "$server/Videos/$itemId/master.m3u8?MediaSourceId=$msId&VideoCodec=h264&AudioCodec=aac&TranscodingMaxAudioChannels=2&SegmentContainer=ts&DeviceId=cosmotune",
                protocol = StreamProtocol.HLS, height = height?.coerceAtMost(1080), hdr = null, videoCodec = "avc")
            listOf(direct, hls)
        }
    }

    companion object {
        fun normalise(server: String) = server.trim().trimEnd('/').let { if (it.contains("://")) it else "http://$it" }

        /** Signs in with a user name and password; returns the session to keep (the password is not). */
        suspend fun signIn(http: WatchHttp, server: String, user: String, password: String, emby: Boolean, deviceId: String): ServerSession {
            val base = normalise(server)
            val header = "MediaBrowser Client=\"Cosmotune\", Device=\"Cosmotune\", DeviceId=\"$deviceId\", Version=\"1.0\""
            val body = buildJsonObject { put("Username", user); put("Pw", password); if (emby) put("Password", password) }.toString()
            val answer = http.postJson("$base/Users/AuthenticateByName", body, mapOf((if (emby) "X-Emby-Authorization" else "Authorization") to header, "X-Emby-Authorization" to header))
            val token = answer.str("AccessToken") ?: error("The server did not accept this sign-in")
            val userId = answer.child("User").str("Id") ?: error("The server did not say who signed in")
            val name = runCatching { http.getJson("$base/System/Info/Public", ttlMs = 0).str("ServerName") }.getOrNull()
            return ServerSession(token, userId, name)
        }
    }
}
