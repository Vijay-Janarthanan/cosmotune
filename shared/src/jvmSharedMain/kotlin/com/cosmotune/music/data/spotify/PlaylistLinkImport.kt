package com.cosmotune.music.data.spotify

import com.cosmotune.music.data.Http
import com.cosmotune.music.data.YtMusicRepository
import com.cosmotune.music.data.model.SearchFilter
import com.cosmotune.music.data.model.SearchResult
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.sources.TrackMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.Request
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Public Amazon Music, Apple Music and Deezer playlist links. Apple's web page and Deezer's public API are read as in
 * Mochy Music's `LinkImporter` (GPL-3.0). Amazon's public web page exposes indexed playlist rows. The songs
 * are then matched on YouTube Music through [TrackMatcher], the exact-recording judgement, with the title, artist, album and
 * length each service gives; a song with no clear match is reported as unmatched rather than replaced by a near miss.
 * The import dialogs that take a Spotify link take these too.
 */
object PlaylistLinkImport {

    enum class Service(val label: String) { APPLE_MUSIC("Apple Music"), DEEZER("Deezer"), AMAZON_MUSIC("Amazon Music") }

    data class LinkTrack(val title: String, val artist: String, val album: String? = null, val durationMs: Long? = null)

    data class Playlist(val service: Service, val name: String, val tracks: List<LinkTrack>, val declaredCount: Int? = null)

    class ImportException(message: String) : Exception(message)

    private val APPLE = Regex("""/[a-z]{2}/(playlist|album)/(?:[^/]+/)?(pl\.[A-Za-z0-9-]+|\d+)/?""")
    private val DEEZER = Regex("""/(?:[a-z]{2}/)?(playlist|album)/(\d+)/?""")
    private val AMAZON = Regex("""/(?:user-playlists|playlists)/[A-Za-z0-9_-]{5,100}/?""")
    private val amazonHosts = listOf("com", "in", "co.uk", "de", "fr", "it", "es", "co.jp", "ca", "com.au", "com.br", "com.mx")
        .map { "music.amazon.$it" }.toSet()
    private val deezerHosts = setOf("deezer.com", "www.deezer.com", "api.deezer.com")
    private val deezerShortHosts = setOf("deezer.page.link", "link.deezer.com")

    /** Which service [link] belongs to, or null for anything else (a Spotify link is the Spotify importer's). */
    fun serviceOf(link: String): Service? = SharedPlaylistLink.urls(link).firstNotNullOfOrNull(::serviceOfUrl)

    private fun serviceOfUrl(url: HttpUrl): Service? {
        return when {
            url.host == "music.apple.com" && APPLE.matches(url.encodedPath) -> Service.APPLE_MUSIC
            url.host in deezerShortHosts || (url.host in deezerHosts && DEEZER.matches(url.encodedPath)) -> Service.DEEZER
            url.host in amazonHosts && AMAZON.matches(url.encodedPath) -> Service.AMAZON_MUSIC
            else -> null
        }
    }

    suspend fun read(link: String): Playlist = withContext(Dispatchers.IO) {
        val url = SharedPlaylistLink.urls(link).firstOrNull { serviceOfUrl(it) != null }
            ?: throw ImportException("Choose a supported public playlist link.")
        val resolved = if (url.host in deezerShortHosts) resolveRedirect(url, deezerHosts + deezerShortHosts) else url
        when (serviceOfUrl(resolved)) {
            Service.APPLE_MUSIC -> {
                val match = requireNotNull(APPLE.matchEntire(resolved.encodedPath))
                parseApplePage(get(resolved, "Apple Music"), match.groupValues[1])
            }
            Service.DEEZER -> readDeezer(resolved)
            Service.AMAZON_MUSIC -> parseAmazonPage(get(resolved, "Amazon Music", "CosmotunePlaylistImporterBot/1.0"))
            null -> throw ImportException("The share link did not open a supported public playlist.")
        }
    }

    fun parseAmazonPage(html: String): Playlist {
        val document = Jsoup.parse(html)
        val header = document.selectFirst("music-detail-header[label=playlist][headline]")
            ?: throw ImportException("Amazon Music did not expose this playlist. Check that its share link opens publicly.")
        val tracks = document.select("music-image-row[index][primary-text][duration]").mapNotNull { row ->
            val title = row.attr("primary-text").trim().takeIf(String::isNotEmpty) ?: return@mapNotNull null
            val time = row.attr("duration").split(':').mapNotNull(String::toLongOrNull)
            LinkTrack(title, row.attr("secondary-text-1").trim(),
                row.attr("secondary-text-2").trim().takeIf(String::isNotEmpty),
                time.takeIf { it.size in 2..3 && it.all { value -> value in 0..999 } }
                    ?.fold(0L) { seconds, part -> seconds * 60 + part }?.times(1000))
        }
        if (tracks.isEmpty()) throw ImportException("Amazon Music did not expose any tracks for this playlist.")
        val count = Regex("""[\d,.]+""").find(header.attr("tertiary-text"))?.value?.filter(Char::isDigit)?.toIntOrNull()
        return Playlist(Service.AMAZON_MUSIC, header.attr("headline").ifBlank { "Amazon Music playlist" }, tracks, count)
    }

    /** Apple Music's page: the header lockup names the playlist, each track lockup a song. */
    fun parseApplePage(html: String, type: String): Playlist {
        val data = Regex("""<script[^>]*id="serialized-server-data"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)
            ?: throw ImportException("Apple Music changed its page, so this link could not be read.")
        val root = json.parseToJsonElement(data)
        val page = (root.path("data") as? JsonArray)?.firstOrNull()?.path("data")
            ?: (root as? JsonArray)?.firstOrNull()?.path("data")
            ?: throw ImportException("That Apple Music $type is private or doesn't exist.")
        val sections = (page.path("sections") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val header = sections.firstOrNull { it.string("itemKind") == "containerDetailHeaderLockup" }
            ?.let { (it["items"] as? JsonArray)?.firstOrNull() as? JsonObject }
        val name = header?.string("title") ?: "Apple Music $type"
        val albumArtist = header?.let { ((it["subtitleLinks"] as? JsonArray)?.firstOrNull()?.path("title") as? JsonPrimitive)?.contentOrNull }
        val tracks = sections.filter { it.string("itemKind") == "trackLockup" }
            .flatMap { (it["items"] as? JsonArray).orEmpty() }
            .mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val title = item.string("title") ?: return@mapNotNull null
                val artists = (item["subtitleLinks"] as? JsonArray).orEmpty()
                    .mapNotNull { (it.path("title") as? JsonPrimitive)?.contentOrNull }
                LinkTrack(
                    title = title,
                    artist = item.string("artistName") ?: artists.joinToString(", ").ifEmpty { albumArtist.orEmpty() },
                    album = if (type == "album") name
                    else ((item["tertiaryLinks"] as? JsonArray)?.firstOrNull()?.path("title") as? JsonPrimitive)?.contentOrNull,
                    durationMs = item.long("duration"),
                )
            }
        if (tracks.isEmpty()) throw ImportException("That Apple Music $type has no songs that could be read.")
        return Playlist(Service.APPLE_MUSIC, name, tracks, header?.long("trackCount")?.toInt())
    }

    private fun readDeezer(url: HttpUrl): Playlist {
        val match = DEEZER.matchEntire(url.encodedPath) ?: throw ImportException("Paste a Deezer playlist or album link.")
        val (type, id) = match.destructured
        val info = deezerJson(get("https://api.deezer.com/$type/$id", "Deezer"))
        val name = info.string("title") ?: "Deezer $type"
        val tracks = ArrayList<LinkTrack>()
        var next: String? = "https://api.deezer.com/$type/$id/tracks?limit=100"
        var pages = 0
        while (next != null && pages < 100) {
            val page = deezerJson(get(next, "Deezer"))
            tracks += parseDeezerTracks(page, albumName = if (type == "album") name else null)
            next = page.string("next")?.takeIf { it.startsWith("https://api.deezer.com/") }
            pages++
        }
        if (tracks.isEmpty()) throw ImportException("That Deezer $type has no songs that could be read.")
        return Playlist(Service.DEEZER, name, tracks, info.long("nb_tracks")?.toInt())
    }

    /** One page of Deezer's `/tracks` answer. */
    fun parseDeezerTracks(page: JsonObject, albumName: String? = null): List<LinkTrack> =
        (page["data"] as? JsonArray).orEmpty().mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val title = item.string("title") ?: return@mapNotNull null
            LinkTrack(
                title = title,
                artist = (item["artist"]?.path("name") as? JsonPrimitive)?.contentOrNull.orEmpty(),
                album = albumName ?: (item["album"]?.path("title") as? JsonPrimitive)?.contentOrNull,
                durationMs = item.long("duration")?.times(1000),
            )
        }

    fun deezerJson(body: String): JsonObject {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: throw ImportException("Deezer sent something that could not be read.")
        root["error"]?.let { error ->
            val message = (error.path("message") as? JsonPrimitive)?.contentOrNull
            throw ImportException(
                if (message?.contains("no data", ignoreCase = true) == true) "That Deezer playlist is private or doesn't exist."
                else "Deezer: ${message ?: "unknown error"}",
            )
        }
        return root
    }

    /**
     * The YouTube Music recordings for [tracks], four searches at a time, judged by [TrackMatcher]. Unmatched songs come
     * back in the importer's own shape so the dialogs can list them as they list Spotify's.
     */
    suspend fun match(
        tracks: List<LinkTrack>,
        onProgress: (completed: Int, total: Int) -> Unit,
    ): Pair<List<Song>, List<SpotifyImportTrack>> = coroutineScope {
        require(tracks.size <= com.cosmotune.listening.ListeningStore.MAX_TRACKS) { "This playlist exceeds the local track limit." }
        val done = AtomicInteger(0)
        val gate = Semaphore(4)
        val results = tracks.map { track ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    val song = try { find(track) } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                    onProgress(done.incrementAndGet(), tracks.size)
                    track to song
                }
            }
        }.awaitAll()
        results.mapNotNull { it.second } to results.filter { it.second == null }.map { SpotifyImportTrack(it.first.title, it.first.artist) }
    }

    private suspend fun find(track: LinkTrack): Song? {
        val query = listOf(TrackMatcher.searchableTitle(track.title, track.artist), TrackMatcher.primaryArtist(track.artist))
            .filter { it.isNotBlank() }.joinToString(" ")
        val candidates = YtMusicRepository.search(query, SearchFilter.SONGS).getOrNull()
            .orEmpty().filterIsInstance<SearchResult.Track>().map { it.song }
        return TrackMatcher.best(
            candidates,
            TrackMatcher.Target(
                title = track.title,
                artist = track.artist,
                durationSec = track.durationMs?.takeIf { it > 0 }?.div(1000)?.toInt(),
                album = track.album,
            ),
        )
    }

    private fun get(url: String, what: String): String = get(requireNotNull(url.toHttpUrlOrNull()), what)

    private fun get(url: HttpUrl, what: String, agent: String = USER_AGENT): String {
        val request = Request.Builder().url(url)
            .header("User-Agent", agent)
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        return client.newCall(request).execute().use { response ->
            when (response.code) {
                in 200..299 -> response.body?.source()?.let { source ->
                    if (source.request(MAX_BODY + 1)) throw ImportException("$what sent a playlist page larger than 8 MB.")
                    source.readUtf8()
                }.orEmpty()
                404 -> throw ImportException("$what could not find that. Is it public?")
                429 -> throw ImportException("$what is limiting requests. Try again in a minute.")
                else -> throw ImportException("$what answered with an error (${response.code}).")
            }
        }
    }

    internal fun resolveRedirect(start: HttpUrl, allowedHosts: Set<String>): HttpUrl {
        var url = start
        repeat(6) {
            if (url.host !in allowedHosts || !SharedPlaylistLink.safe(url)) throw ImportException("The share link redirected outside its music service.")
            val next = client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use { response ->
                if (!response.isRedirect) return url
                response.header("Location")?.let(url::resolve)
                    ?: throw ImportException("The share link sent an invalid redirect.")
            }
            url = next
        }
        throw ImportException("The share link redirected too many times.")
    }

    private val client by lazy { Http.client.newBuilder().followRedirects(false).callTimeout(30, TimeUnit.SECONDS).build() }
    private const val MAX_BODY = 8L * 1024 * 1024
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"

    private fun JsonElement.path(vararg keys: String): JsonElement? {
        var at: JsonElement? = this
        for (key in keys) at = (at as? JsonObject)?.get(key) ?: return null
        return at
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
}
