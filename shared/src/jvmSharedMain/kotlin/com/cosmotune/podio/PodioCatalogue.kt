package com.cosmotune.podio

import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.model.durationMillis
import java.io.File
import java.net.InetAddress
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.w3c.dom.Element
import org.jsoup.Jsoup
import org.xml.sax.InputSource
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable data class RadioStation(val id: String, val name: String, val url: String,
    val country: String = "", val countryCode: String = "", val language: String = "", val tags: String = "",
    val artwork: String = "", val latitude: Double? = null, val longitude: Double? = null,
    val codec: String = "", val bitrate: Int = 0, val state: String = "",
    /** The directory's last check could not reach the stream ([PodioStationCheck]). */
    val offline: Boolean = false,
    /** The directory no longer lists the station. */
    val removed: Boolean = false)
@Serializable data class PodcastShow(val id: String, val title: String, val author: String,
    val feed: String, val artwork: String = "", val website: String = "", val source: String = "Publisher feed",
    /** Podcasting 2.0's "podcast:funding": where to support the show (a web page; nothing is paid in the app). */
    val funding: List<PodcastLink> = emptyList(),
    /** Podcasting 2.0's "podcast:person" for the whole show: its hosts and makers. */
    val people: List<PodcastPerson> = emptyList())
/** Someone the feed names (Podcasting 2.0 "podcast:person"): a host, a guest; [href] is their page, [image] their picture. */
@Serializable data class PodcastPerson(val name: String, val role: String = "host", val href: String = "", val image: String = "")
/** A link the feed gives with its words ("Support us on …"). */
@Serializable data class PodcastLink(val url: String, val text: String)
@Serializable data class PodcastEpisode(val id: String, val title: String, val show: String, val url: String,
    val artwork: String = "", val durationSeconds: Long = 0, val published: String = "", val description: String = "",
    val chaptersUrl: String? = null, val transcripts: List<PodcastTranscript> = emptyList(),
    /** Chapters the feed itself carries: Podlove Simple Chapters, else chapter times listed in the show notes. */
    val inlineChapters: List<PodcastChapter> = emptyList(),
    /** Its season and number in the show (podcast:season / podcast:episode, or the iTunes tags), when the feed gives them. */
    val season: Int? = null, val number: Int? = null,
    /** The hosts and guests the feed names for this episode. */
    val people: List<PodcastPerson> = emptyList(),
    /** Where the episode is talked about (Podcasting 2.0 "podcast:socialInteract"): a fediverse post whose replies are its comments. */
    val comments: String? = null)
data class PodcastFeed(val show: PodcastShow, val episodes: List<PodcastEpisode>,
    /** Publisher-provided RSS/Atom archive link, rather than a guessed page number. */
    val nextPage: String? = null, val visitedPages: List<String> = emptyList())

/** The weather where a station is, from Open-Meteo (CC BY 4.0). [utcOffsetSeconds] is the place's own clock; [sunrise] and [sunset] are local "HH:mm". */
data class PodioWeather(val temperatureC: Double, val code: Int, val isDay: Boolean, val windKmh: Double, val utcOffsetSeconds: Int, val sunrise: String, val sunset: String) {
    /** WMO weather interpretation code, in words. */
    val summary: String get() = when (code) {
        0 -> "Clear sky"; 1 -> "Mostly clear"; 2 -> "Partly cloudy"; 3 -> "Overcast"; 45, 48 -> "Fog"
        in 51..57 -> "Drizzle"; in 61..67 -> "Rain"; in 71..77 -> "Snow"; in 80..82 -> "Rain showers"; 85, 86 -> "Snow showers"; in 95..99 -> "Thunderstorm"
        else -> "Weather"
    }
}

/** A photograph taken near a station, from Wikimedia Commons: where its page is, and who took it under which licence. */
data class PodioPhoto(val title: String, val image: String, val page: String, val credit: String)

/** A well-known place near a station, from Wikipedia (text CC BY-SA 4.0): its name, the first sentences of its article, a picture and the article address. */
data class PodioHighlight(val title: String, val summary: String, val image: String, val url: String)
data class PodioItem(val id: String, val title: String, val author: String, val url: String,
    val artwork: String = "", val live: Boolean = false, val durationSeconds: Long = 0,
    val downloadedUri: String? = null, val downloadedPath: String? = null, val skipSilence: Boolean? = null, val chaptersUrl: String? = null) {
    fun song(): Song = Song(videoId = id, title = title, artist = author, thumbnailUrl = artwork.takeIf { it.isNotBlank() },
        durationText = durationSeconds.takeIf { it > 0 }?.let { "${it / 60}:${(it % 60).toString().padStart(2, '0')}" },
        localUri = downloadedUri?.takeIf { runCatching { URI(it).scheme in setOf("file", "content") }.getOrDefault(false) }
            ?: if (YouTubePodcasts.videoId(id) != null) null else requireNotNull(PodioCatalogue.httpUrl(url)),
        localPath = downloadedPath, albumName = if (live) "Podio · Live radio" else "Podio · Podcast", podcastSkipSilence = skipSilence, podcastChaptersUrl = chaptersUrl)
}
fun RadioStation.item() = PodioItem("podio:radio:$id", name, listOf(country, language).filter(String::isNotBlank).joinToString(" · "), url, artwork, live = true)
fun PodcastEpisode.item() = PodioItem("podio:episode:$id", title, show, url, artwork, durationSeconds = durationSeconds, chaptersUrl = chaptersUrl)
/** Only download records supplied by the platform host can create a local episode row. */
fun Song.offlinePodioItem(): PodioItem? {
    val uri = localUri?.takeIf { runCatching { URI(it).scheme in setOf("file", "content") }.getOrDefault(false) } ?: return null
    if (!videoId.startsWith("podio:episode:")) return null
    return PodioItem(videoId, title, artist, uri, thumbnailUrl.orEmpty(), durationSeconds = durationMillis() / 1000,
        downloadedUri = uri, downloadedPath = localPath, chaptersUrl = podcastChaptersUrl)
}
val Song.isPodio: Boolean get() = videoId.startsWith("podio:radio:") || videoId.startsWith("podio:episode:")
fun podioSourceName(id: String): String = when {
    id.startsWith("podio:radio") -> "Radio Browser"
    id.startsWith("podio:youtube") || YouTubePodcasts.videoId(id) != null -> "YouTube Music podcast"
    else -> "Podcast publisher"
}

/** Account-free directories and publisher RSS audio. Spotify links supply metadata, never protected streams. */
class PodioCatalogue(private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).cookieJar(AnonymousSpotifyCookies()).build(),
    private val radioMirrors: List<String>? = null,
    private val appleBase: String = "https://itunes.apple.com", private val fyydBase: String = "https://api.fyyd.de",
    private val spotifyMetadata: (suspend (String) -> PodcastFeed)? = null,
    private val weatherBase: String = "https://api.open-meteo.com", private val wikipediaBase: String = "https://en.wikipedia.org",
    private val commonsBase: String = "https://commons.wikimedia.org",
    private val youtubePodcasts: YouTubePodcasts? = null,
    /**
     * gPodder's public directory and Podverse's, searched beside Apple Podcasts and fyyd (no account, no key). A catalogue pointed at
     * other directories (a test's server) leaves them out unless it names them too.
     */
    private val gpodderBase: String? = "https://gpodder.net".takeIf { fyydBase == "https://api.fyyd.de" },
    private val podverseBase: String? = "https://api.podverse.fm".takeIf { fyydBase == "https://api.fyyd.de" }) {
    private val json = Json { ignoreUnknownKeys = true }
    private val spotify by lazy { SpotifyPublicPodcasts(::readRequest) }
    private var mirrors: List<String>? = null
    private suspend fun radio(path: String, params: Map<String, String>): JsonElement {
        val hosts = radioMirrors ?: mirrors ?: withContext(Dispatchers.IO) {
            runCatching { InetAddress.getAllByName("all.api.radio-browser.info").map { it.canonicalHostName }
                .filter { it.endsWith(".api.radio-browser.info") }.distinct().shuffled().map { "https://$it" } }
                .getOrDefault(emptyList()).ifEmpty { listOf("https://de1.api.radio-browser.info", "https://de2.api.radio-browser.info") }
        }.also { mirrors = it }
        for (host in hosts.take(3)) {
            try { return json.parseToJsonElement(read(address(host, path, params))) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* A directory mirror can fail independently. */ }
        }
        error("The radio directory is unavailable. Please retry.")
    }
    suspend fun stations(query: String = "", countryCode: String = "", language: String = "", globe: Boolean = false,
        limit: Int? = null, tag: String = ""): List<RadioStation> = withContext(Dispatchers.Default) {
        val count = limit?.coerceIn(1, 3000) ?: if (globe) 1200 else 3000
        val params = linkedMapOf("hidebroken" to "true", "order" to "clickcount", "reverse" to "true", "limit" to count.toString())
        if (query.isNotBlank()) params["name"] = query.trim().take(200)
        if (countryCode.isNotBlank()) params["countrycode"] = countryCode.uppercase(Locale.ROOT).take(2)
        if (language.isNotBlank()) params["language"] = language.trim().lowercase(Locale.ROOT).take(60)
        if (globe) params["has_geo_info"] = "true"
        if (tag.isNotBlank()) params["tag"] = tag.trim().lowercase(Locale.ROOT).take(60)
        val directory = stationList(radio("/json/stations/search", params))
        val merged = mergeExtras(directory, extras, query, countryCode, language).let { list -> if (tag.isBlank()) list else list.filter { it.tags.contains(tag, true) } }
        if (limit != null) merged.take(count) else merged
    }

    /** The directory's current entries for saved stations ([ids]), with whether its last check reached each one. */
    suspend fun stationsById(ids: List<String>): List<RadioStation> = withContext(Dispatchers.Default) {
        val wanted = ids.filter { it.matches(Regex("[a-zA-Z0-9-]{1,64}")) && !it.startsWith(PodioStationLinks.CUSTOM_PREFIX) }.distinct().take(200)
        if (wanted.isEmpty()) emptyList() else stationList(radio("/json/stations/byuuid", mapOf("uuids" to wanted.joinToString(","))))
    }

    /** A vote for the station in the open directory (one a station every ten minutes); true when it was counted. */
    suspend fun vote(id: String): Boolean {
        if (!id.matches(Regex("[a-zA-Z0-9-]{1,64}")) || id.startsWith(PodioStationLinks.CUSTOM_PREFIX)) return false
        return attempt { ((radio("/json/vote/$id", emptyMap()) as? JsonObject)?.get("ok") as? JsonPrimitive)?.booleanOrNull } == true
    }

    private fun stationList(answer: JsonElement): List<RadioStation> {
        val directory = (answer as? JsonArray).orEmpty().mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val url = httpUrl(o.text("url_resolved").ifBlank { o.text("url") }) ?: return@mapNotNull null
            val id = o.text("stationuuid").takeIf { it.matches(Regex("[a-zA-Z0-9-]{1,64}")) } ?: return@mapNotNull null
            RadioStation(id, o.text("name").trim().take(300), url, o.text("country"), o.text("countrycode"),
                o.text("language"), o.text("tags"), httpUrl(o.text("favicon")).orEmpty(),
                o.number("geo_lat")?.takeIf { it in -90.0..90.0 }, o.number("geo_long")?.takeIf { it in -180.0..180.0 },
                o.text("codec"), o.text("bitrate").toIntOrNull() ?: 0, o.text("state").trim().take(80), offline = o.text("lastcheckok") == "0")
        }.distinctBy { it.id }
        return directory
    }
    // Stations whose own web page publishes the stream but that the open directory does not list yet. They belong to the real
    // directory only: a test that points the catalogue at its own server gets exactly what that server returns.
    /** Satellite tiles for the map; off for a catalogue that points at a test server. */
    val tiles: PodioTiles by lazy { PodioTiles(enabled = radioMirrors == null) }
    private val extras: List<RadioStation> by lazy {
        if (radioMirrors != null) emptyList() else runCatching {
            PodioCatalogue::class.java.getResourceAsStream("/podio/extra-stations.json")?.bufferedReader()?.use { json.decodeFromString<List<RadioStation>>(it.readText()) }
        }.getOrNull().orEmpty()
    }
    /** The stream a station address plays: the address itself, or the first stream a playlist (M3U, PLS) at it names. */
    suspend fun stationStream(url: String): String {
        val address = requireNotNull(httpUrl(url)) { "Enter an HTTP or HTTPS address." }
        if (!PodioStationLinks.isPlaylist(address)) return address
        return PodioStationLinks.streams(read(address), address).firstOrNull() ?: error("This playlist names no stream.")
    }
    suspend fun stationPlayed(id: String) {
        if (!id.matches(Regex("[a-zA-Z0-9-]{1,64}")) || id.startsWith(PodioStationLinks.CUSTOM_PREFIX)) return
        try { radio("/json/url/$id", emptyMap()) } catch(e: CancellationException) { throw e } catch (_: Exception) { }
    }
    /**
     * The song the station is playing now, from the stream's own ICY metadata ("StreamTitle"), or null when the station sends none.
     * It opens the stream, reads up to three metadata blocks (about one second of audio each) and closes it again.
     */
    suspend fun nowPlaying(streamUrl: String): String? = withContext(Dispatchers.IO) {
        val address = httpUrl(streamUrl) ?: return@withContext null
        val call = client.newBuilder().callTimeout(12, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build().newCall(
            Request.Builder().url(address).header("Icy-MetaData", "1").header("User-Agent", "Cosmotune-Podio/1 (+https://github.com/Vijay-Janarthanan/cosmotune)").build())
        val cancel = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { reply ->
                val interval = reply.header("icy-metaint")?.trim()?.toIntOrNull()?.takeIf { it in 1..65_536 } ?: return@use null
                val input = reply.body?.byteStream() ?: return@use null
                repeat(3) {
                    var skipped = 0
                    while (skipped < interval) { val count = input.skip((interval - skipped).toLong()).toInt(); if (count <= 0) { if (input.read() < 0) return@use null else skipped++ } else skipped += count }
                    val blocks = input.read()
                    if (blocks < 0) return@use null
                    if (blocks == 0) return@repeat
                    val bytes = ByteArray(blocks * 16)
                    var read = 0
                    while (read < bytes.size) { val count = input.read(bytes, read, bytes.size - read); if (count < 0) return@use null; read += count }
                    streamTitle(bytes.toString(Charsets.UTF_8))?.let { return@use it }
                }
                null
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null } finally { cancel?.dispose() }
    }
    /** The weather at a point, or null when the service cannot be reached. */
    suspend fun weather(latitude: Double, longitude: Double): PodioWeather? = attempt {
        val root = json.parseToJsonElement(read(address(weatherBase, "/v1/forecast", mapOf("latitude" to "%.3f".format(Locale.ROOT, latitude), "longitude" to "%.3f".format(Locale.ROOT, longitude),
            "current" to "temperature_2m,weather_code,is_day,wind_speed_10m", "daily" to "sunrise,sunset", "timezone" to "auto", "forecast_days" to "1")))).jsonObject
        val current = root["current"] as? JsonObject ?: return@attempt null
        fun daily(name: String) = (((root["daily"] as? JsonObject)?.get(name) as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.contentOrNull.orEmpty().substringAfter('T')
        PodioWeather(current.number("temperature_2m") ?: return@attempt null, current.number("weather_code")?.toInt() ?: 0, current.number("is_day") == 1.0,
            current.number("wind_speed_10m") ?: 0.0, root.number("utc_offset_seconds")?.toInt() ?: 0, daily("sunrise"), daily("sunset"))
    }

    /** A small picture (an article thumbnail) with the app's own client name, which Wikimedia requires; null when it cannot be had. */
    suspend fun picture(url: String): ByteArray? = withContext(Dispatchers.IO) {
        val address = httpUrl(url) ?: return@withContext null
        val call = client.newCall(Request.Builder().url(address).header("User-Agent", "Cosmotune-Podio/1 (+https://github.com/Vijay-Janarthanan/cosmotune)").build())
        val cancel = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
        try { call.execute().use { reply ->
            if (!reply.isSuccessful || (reply.body?.contentLength() ?: -1) > 2_000_000) return@use null
            reply.body?.byteStream()?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) { val count = input.read(buffer); if (count < 0) break
                    require(out.size() <= 2_000_000 - count); out.write(buffer, 0, count) }
                out.toByteArray().takeIf { it.isNotEmpty() }
            }
        } }
        catch (e: CancellationException) { throw e } catch (_: Exception) { null } finally { cancel?.dispose() }
    }

    /** Geotagged photographs within 10 km of a point, nearest first, each with its author and licence (Wikimedia Commons; the licences need the credit shown). */
    suspend fun photos(latitude: Double, longitude: Double, limit: Int = 12): List<PodioPhoto> = attempt {
        val root = json.parseToJsonElement(read(address(commonsBase, "/w/api.php", mapOf("action" to "query", "format" to "json", "formatversion" to "2", "generator" to "geosearch",
            "ggscoord" to "%.5f|%.5f".format(Locale.ROOT, latitude, longitude), "ggsradius" to "10000", "ggsnamespace" to "6", "ggslimit" to "40", "prop" to "imageinfo",
            "iiprop" to "url|extmetadata", "iiurlwidth" to "640", "iiextmetadatafilter" to "Artist|LicenseShortName")))).jsonObject
        ((root["query"] as? JsonObject)?.get("pages") as? JsonArray).orEmpty().mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val file = o.text("title").removePrefix("File:")
            if (!Regex("(?i)\\.(jpe?g|png|webp)$").containsMatchIn(file)) return@mapNotNull null
            val info = (o["imageinfo"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return@mapNotNull null
            val thumb = httpUrl(info.text("thumburl")) ?: return@mapNotNull null
            fun meta(key: String) = (((info["extmetadata"] as? JsonObject)?.get(key) as? JsonObject)?.text("value")).orEmpty()
            val credit = listOf(Jsoup.parse(meta("Artist")).text().trim().take(40), meta("LicenseShortName").trim()).filter(String::isNotBlank).joinToString(" · ")
            (o.number("index") ?: 0.0) to PodioPhoto(file.substringBeforeLast('.').replace('_', ' '), thumb, httpUrl(info.text("descriptionurl")).orEmpty(), credit)
        }.sortedBy { it.first }.map { it.second }.distinctBy { it.image }.take(limit.coerceIn(1, 30))
    }.orEmpty()

    /** Wikipedia's articles about places within 10 km of a point, nearest first: what the neighbourhood is known for. */
    suspend fun highlights(latitude: Double, longitude: Double, limit: Int = 8): List<PodioHighlight> = attempt {
        val root = json.parseToJsonElement(read(address(wikipediaBase, "/w/api.php", mapOf("action" to "query", "format" to "json", "formatversion" to "2", "generator" to "geosearch",
            "ggscoord" to "%.5f|%.5f".format(Locale.ROOT, latitude, longitude), "ggsradius" to "10000", "ggslimit" to limit.coerceIn(1, 20).toString(),
            "prop" to "extracts|pageimages", "exintro" to "1", "explaintext" to "1", "exsentences" to "2", "exlimit" to "max", "piprop" to "thumbnail", "pithumbsize" to "480")))).jsonObject
        ((root["query"] as? JsonObject)?.get("pages") as? JsonArray).orEmpty().mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val title = o.text("title").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val summary = o.text("extract").trim().takeIf { it.length >= 20 } ?: return@mapNotNull null
            (o.number("index") ?: 0.0) to PodioHighlight(title, summary.take(400), httpUrl((o["thumbnail"] as? JsonObject)?.text("source").orEmpty()).orEmpty(),
                wikipediaBase.trimEnd('/') + "/wiki/" + java.net.URLEncoder.encode(title.replace(' ', '_'), "UTF-8").replace("+", "%20"))
        }.sortedBy { it.first }.map { it.second }
    }.orEmpty()

    /** Chart position order, with the feed addresses Apple's lookup supplies: the shows people are listening to in [country]. */
    suspend fun topPodcasts(country: String = "US", genre: String? = null, limit: Int = 30): List<PodcastShow> = supervisorScope {
        if (youtubePodcasts == null || genre != null) return@supervisorScope publisherPodcastCharts(country, genre, limit)
        val apple = async { attempt { publisherPodcastCharts(country, genre, limit) } }
        val youtube = async { attempt { youtubePodcasts.home() } }
        val chart = apple.await(); val publicShows = youtube.await()
        if (chart == null && publicShows == null) error("Podcast discovery is unavailable. Please retry.")
        (chart.orEmpty() + publicShows.orEmpty()).distinctBy { it.feed.trimEnd('/') }
    }
    suspend fun publisherPodcastCharts(country: String, genre: String? = null, limit: Int = 30): List<PodcastShow> {
        val code = country.take(2).lowercase(Locale.ROOT).ifBlank { "us" }
        val chart = json.parseToJsonElement(read(address(appleBase, "/$code/rss/toppodcasts/limit=${limit.coerceIn(1, 100)}/" + (genre?.let { "genre=$it/" }.orEmpty()) + "json", emptyMap())))
        val ids = ((chart.jsonObject["feed"] as? JsonObject)?.get("entry") as? JsonArray).orEmpty().mapNotNull { entry ->
            (((entry as? JsonObject)?.get("id") as? JsonObject)?.get("attributes") as? JsonObject)?.text("im:id")?.takeIf { it.all(Char::isDigit) }
        }
        if (ids.isEmpty()) return emptyList()
        val lookup = json.parseToJsonElement(read(address(appleBase, "/lookup", mapOf("id" to ids.joinToString(","), "entity" to "podcast", "country" to code))))
        val byId = (lookup.jsonObject["results"] as? JsonArray).orEmpty().mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val feed = httpUrl(o.text("feedUrl")) ?: return@mapNotNull null
            o.text("collectionId") to PodcastShow(identity(feed), o.text("collectionName"), o.text("artistName"), feed,
                httpUrl(o.text("artworkUrl600").ifBlank { o.text("artworkUrl100") }).orEmpty(), httpUrl(o.text("collectionViewUrl")).orEmpty(), "Apple Podcasts chart")
        }.toMap()
        return ids.mapNotNull { byId[it] }.distinctBy { it.feed.trimEnd('/') }
    }
    suspend fun podcasts(query: String, country: String = "IN"): List<PodcastShow> = supervisorScope {
        if (SpotifyPublicPodcasts.link(query.trim()) != null) return@supervisorScope listOf(feed(query.trim()).show)
        if (YouTubePodcasts.showId(query.trim()) != null) return@supervisorScope listOf(feed(query.trim()).show)
        val publisher = async { attempt { searchPublisherPodcasts(query, country) } }
        val youtube = async { youtubePodcasts?.let { attempt { it.search(podcastSearchTerm(query)) } } }
        val publicShows = publisher.await()
        val youtubeShows = youtube.await()
        if (publicShows == null && youtubeShows == null) error("Podcast search is unavailable. Please retry.")
        (publicShows.orEmpty() + youtubeShows.orEmpty()).distinctBy { it.feed.trimEnd('/') }
    }
    /** Discovery for clients that play publisher enclosures directly. */
    suspend fun searchPublisherPodcasts(query: String, country: String): List<PodcastShow> = supervisorScope {
        val term = podcastSearchTerm(query)
        val apple = async { attempt { appleSearch(term, country) } }
        val fyyd = async { attempt { fyydSearch(term) } }
        val gpodder = gpodderBase?.let { base -> async { attempt { gpodderShows(read(address(base, "/search.json", mapOf("q" to term)))) } } }
        val podverse = podverseBase?.let { base -> async { attempt { podverseShows(read(address(base, "/api/v1/podcast", mapOf("searchTitle" to term)))) } } }
        val first = listOf(apple.await(), fyyd.await())
        if (first.all { it == null } && gpodder == null && podverse == null) error("Podcast search is unavailable. Please retry.")
        val found = first + listOf(gpodder?.await(), podverse?.await())
        if (found.all { it == null }) error("Podcast search is unavailable. Please retry.")
        found.flatMap { it.orEmpty() }.distinctBy { it.feed.trimEnd('/').removePrefix("https://").removePrefix("http://") }
    }
    private fun podcastSearchTerm(query: String) = query.trim().take(200).ifBlank { "podcast" }
    private suspend fun appleSearch(query: String, country: String): List<PodcastShow> {
        val root = json.parseToJsonElement(read(address(appleBase, "/search", mapOf("term" to query, "media" to "podcast", "entity" to "podcast", "limit" to "40", "country" to country.take(2)))))
        return (root.jsonObject["results"] as? JsonArray).orEmpty().mapNotNull { element ->
            val o = element.jsonObject
            val feed = httpUrl(o.text("feedUrl")) ?: return@mapNotNull null
            PodcastShow(identity(feed), o.text("collectionName"), o.text("artistName"), feed,
                httpUrl(o.text("artworkUrl600").ifBlank { o.text("artworkUrl100") }).orEmpty(),
                httpUrl(o.text("collectionViewUrl")).orEmpty(), "Apple Podcasts")
        }
    }
    /**
     * Episodes whose title or notes match [query], from Apple's episode index in [country]'s storefront, each with its show (its
     * feed, so the show's page opens from it). The id is the one the show's own feed gives the episode (its feed and guid), so
     * progress and the queue are shared with the feed's rows. A blank query asks nothing.
     */
    suspend fun searchEpisodes(query: String, country: String): List<Pair<PodcastEpisode, PodcastShow>> {
        val term = query.trim().take(200)
        if (term.isEmpty()) return emptyList()
        val root = json.parseToJsonElement(read(address(appleBase, "/search",
            mapOf("term" to term, "media" to "podcast", "entity" to "podcastEpisode", "limit" to "40", "country" to country.take(2).lowercase(Locale.ROOT)))))
        return (root.jsonObject["results"] as? JsonArray).orEmpty().mapNotNull { element ->
            val o = element.jsonObject
            val feed = httpUrl(o.text("feedUrl")) ?: return@mapNotNull null
            val audio = httpUrl(o.text("episodeUrl")) ?: return@mapNotNull null
            val art = httpUrl(o.text("artworkUrl600").ifBlank { o.text("artworkUrl160") }).orEmpty()
            val show = PodcastShow(identity(feed), o.text("collectionName"), o.text("artistName"), feed, art, httpUrl(o.text("collectionViewUrl")).orEmpty(), "Apple Podcasts")
            val guid = o.text("episodeGuid").ifBlank { audio }
            val seconds = (o.text("trackTimeMillis").toLongOrNull() ?: 0L) / 1000
            PodcastEpisode(identity("$feed|$guid"), o.text("trackName").ifBlank { return@mapNotNull null }, show.title, audio, art, seconds,
                o.text("releaseDate"), Jsoup.parse(o.text("description")).text().take(4000)) to show
        }.distinctBy { it.first.id }
    }
    private suspend fun fyydSearch(query: String): List<PodcastShow> {
        val root = json.parseToJsonElement(read(address(fyydBase, "/0.2/search/podcast", mapOf("title" to query, "count" to "30"))))
        return (root.jsonObject["data"] as? JsonArray).orEmpty().mapNotNull { element ->
            val o = element.jsonObject
            val feed = httpUrl(o.text("xmlURL")) ?: return@mapNotNull null
            PodcastShow(identity(feed), o.text("title"), o.text("author"), feed,
                httpUrl(o.text("imgURL")).orEmpty(), httpUrl(o.text("htmlURL")).orEmpty(), "fyyd")
        }
    }
    suspend fun feed(url: String): PodcastFeed {
        val address = requireNotNull(httpUrl(url)) { "Enter an HTTP or HTTPS podcast feed URL." }
        if (SpotifyPublicPodcasts.link(address) != null) return spotifyPublisherFeed(address)
        if (YouTubePodcasts.showId(address) != null) return (youtubePodcasts ?: YouTubePodcasts()).feed(address)
        return publisherFeed(address)
    }
    /**
     * The episode's chapters from the best place that has them: the publisher's chapters file, then the ones written into a
     * downloaded copy ([localPath]), then those in the feed itself (Podlove, or times in the show notes). A file that cannot be
     * loaded only counts as a failure when nothing else has chapters.
     */
    suspend fun chapters(episode: PodcastEpisode, localPath: String? = null): List<PodcastChapter> {
        var failure: Exception? = null
        val remote = episode.chaptersUrl?.let {
            try { withContext(Dispatchers.Default) { PodcastExtras.chapters(read(requireNotNull(httpUrl(it)))) } }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { failure = error; emptyList() }
        }.orEmpty()
        if (remote.isNotEmpty()) return remote
        val local = localPath?.let { path -> withContext(Dispatchers.IO) { Id3Chapters.readFile(java.io.File(path)) } }.orEmpty()
        if (local.isNotEmpty()) return local
        if (episode.inlineChapters.isNotEmpty()) return episode.inlineChapters
        failure?.let { throw it }
        return emptyList()
    }
    suspend fun transcript(source: PodcastTranscript): List<PodcastCue> = withContext(Dispatchers.Default) {
        PodcastExtras.transcript(read(requireNotNull(httpUrl(source.url))), source.type)
    }
    suspend fun publisherFeed(url: String): PodcastFeed {
        val address = requireNotNull(httpUrl(url)) { "Enter an HTTP or HTTPS podcast feed URL." }
        return withContext(Dispatchers.Default) { parseFeed(read(address), address) }
    }
    /** Append one publisher archive page, retaining root-feed episode identities and resume data. */
    suspend fun moreEpisodes(feed: PodcastFeed): PodcastFeed {
        val next = feed.nextPage ?: return feed
        require(feed.visitedPages.size < 100) { "This archive reached the 100-page limit." }
        require(next !in feed.visitedPages) { "This archive repeats an earlier page." }
        val page = if (YouTubePodcasts.showId(feed.show.feed) != null) (youtubePodcasts ?: YouTubePodcasts()).feed(next, feed.show)
            else withContext(Dispatchers.Default) { parseFeed(read(next), next, feed.show.feed) }
        require(titleKey(page.show.title) == titleKey(feed.show.title)) { "The archive link opened a different podcast." }
        val visited = (feed.visitedPages + next).distinct()
        return feed.copy(episodes = (feed.episodes + page.episodes).distinctBy { it.id },
            nextPage = page.nextPage?.takeUnless { it in visited }, visitedPages = visited)
    }
    private suspend fun spotifyPublisherFeed(address: String): PodcastFeed {
        val metadata = spotifyMetadata?.invoke(address) ?: spotify.feed(address)
        val expected = metadata.episodes.singleOrNull().takeIf { SpotifyPublicPodcasts.link(address)?.first == "episode" }
        val candidates = podcasts(metadata.show.title).filter {
            YouTubePodcasts.showId(it.feed) == null && titleKey(it.title) == titleKey(metadata.show.title)
        }.take(3)
        for (candidate in candidates) {
            val publisher = attempt { feed(candidate.feed) } ?: continue
            if (titleKey(publisher.show.title) != titleKey(metadata.show.title)) continue
            fun sameEpisode(left: PodcastEpisode, right: PodcastEpisode) = titleKey(left.title) == titleKey(right.title) &&
                (left.durationSeconds <= 0 || right.durationSeconds <= 0 ||
                    kotlin.math.abs(left.durationSeconds - right.durationSeconds) <= maxOf(90L, right.durationSeconds / 20))
            if (metadata.episodes.none { known -> publisher.episodes.any { sameEpisode(it, known) } }) continue
            val episodes = if (expected == null) publisher.episodes else publisher.episodes.filter {
                sameEpisode(it, expected)
            }
            if (episodes.isNotEmpty()) return publisher.copy(show = publisher.show.copy(source = "Publisher RSS · matched from Spotify link"), episodes = episodes)
        }
        error("No matching public RSS audio was found for this Spotify link. Add the publisher's RSS feed if available.")
    }
    private suspend fun <T> attempt(block: suspend () -> T): T? = try { block() } catch(e: CancellationException) { throw e } catch (_: Exception) { null }
    private fun address(base: String, path: String, params: Map<String, String>): String = (base.trimEnd('/') + path).toHttpUrl().newBuilder()
        .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build().toString()
    private suspend fun read(url: String): String = readRequest(Request.Builder().url(url).header("User-Agent", "Cosmotune-Podio/1 (+https://github.com/Vijay-Janarthanan/cosmotune)")
        // A private feed's own server gets its login; no other server ever sees it.
        .apply { PodioFeedLogins.forUrl(url)?.let { header("Authorization", PodioFeedLogins.header(it)) } }.build())
    private suspend fun readRequest(request: Request): String = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val text = response.use { reply ->
                        val bytes = requireNotNull(reply.body).byteStream().use { input ->
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (output.size() <= MAX_BYTES) {
                                val count = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - output.size()))
                                if (count < 0) break
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        }
                        require(bytes.size <= MAX_BYTES) { "The feed is too large" }
                        if (!reply.isSuccessful) {
                            val reason = if (reply.request.url.host == "clienttoken.spotify.com") runCatching {
                                val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
                                val error = root["error"]
                                ((error as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull
                                    ?: (error as? JsonPrimitive)?.contentOrNull
                            }.getOrNull()?.take(160) else null
                            error("Source request failed (HTTP ${reply.code})${reason?.let { ": $it" }.orEmpty()}")
                        }
                        bytes.toString(Charsets.UTF_8)
                    }
                    if (continuation.isActive) continuation.resume(text)
                } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
            }
        })
    }
    companion object {
        private const val MAX_BYTES = 8 * 1024 * 1024
        /** The directory's answer plus the bundled stations that fit the same query, country and language (one entry per stream address). */
        fun mergeExtras(directory: List<RadioStation>, extras: List<RadioStation>, query: String, countryCode: String, language: String): List<RadioStation> {
            val fitting = extras.filter { s ->
                (query.isBlank() || s.name.contains(query.trim(), ignoreCase = true)) &&
                    (countryCode.isBlank() || s.countryCode.equals(countryCode.trim(), ignoreCase = true)) &&
                    (language.isBlank() || s.language.contains(language.trim(), ignoreCase = true))
            }
            return (directory + fitting).distinctBy { it.id }.distinctBy { it.url.trimEnd('/') }
        }
        /** "StreamTitle='Artist - Song';" out of an ICY metadata block. */
        fun streamTitle(block: String): String? = Regex("StreamTitle='(.*?)';", RegexOption.DOT_MATCHES_ALL).find(block)?.groupValues?.get(1)
            ?.trim()?.trim(' ')?.takeIf { it.length in 2..200 }
        fun isSpotifyLink(value: String): Boolean = SpotifyPublicPodcasts.link(value) != null
        private fun titleKey(value: String): String = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        /** Shows from gPodder's directory search ([gpodderBase]/search.json): a list of {url, title, author, logo_url, website}. */
        fun gpodderShows(text: String): List<PodcastShow> = (Json.parseToJsonElement(text) as? JsonArray).orEmpty().take(30).mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val feed = httpUrl(o.text("url")) ?: return@mapNotNull null
            val title = o.text("title").trim().takeIf(String::isNotEmpty) ?: return@mapNotNull null
            PodcastShow(identity(feed), title, o.text("author"), feed, httpUrl(o.text("scaled_logo_url").ifBlank { o.text("logo_url") }).orEmpty(),
                httpUrl(o.text("website")).orEmpty(), "gPodder")
        }
        /** Shows from Podverse's search ([podverseBase]/api/v1/podcast): [[{title, imageUrl, linkUrl, feedUrls: [{url}]}], count]. */
        fun podverseShows(text: String): List<PodcastShow> {
            val root = Json.parseToJsonElement(text)
            val list = ((root as? JsonArray)?.firstOrNull() as? JsonArray) ?: (root as? JsonArray) ?: return emptyList()
            return list.take(30).mapNotNull { element ->
                val o = element as? JsonObject ?: return@mapNotNull null
                if ((o["credentialsRequired"] as? JsonPrimitive)?.contentOrNull == "true") return@mapNotNull null
                val feed = (o["feedUrls"] as? JsonArray).orEmpty().firstNotNullOfOrNull { (it as? JsonObject)?.text("url")?.let(::httpUrl) } ?: return@mapNotNull null
                val title = o.text("title").trim().takeIf(String::isNotEmpty) ?: return@mapNotNull null
                PodcastShow(identity(feed), title, (o["authors"] as? JsonArray).orEmpty().firstNotNullOfOrNull { (it as? JsonObject)?.text("name") }.orEmpty(),
                    feed, httpUrl(o.text("imageUrl")).orEmpty(), httpUrl(o.text("linkUrl")).orEmpty(), "Podverse")
            }
        }
        fun httpUrl(raw: String): String? = runCatching {
            val value = raw.trim(); val uri = URI(value)
            value.takeIf { uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null }
        }.getOrNull()
        fun identity(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
        fun duration(value: String): Long = value.trim().split(':').takeIf { it.size in 1..3 }
            ?.map { it.toLongOrNull()?.takeIf { it >= 0 && it <= 604800 } ?: -1 }
            ?.takeIf { values -> values.all { it >= 0 } }
            ?.fold(0L) { total, part -> total * 60 + part }?.coerceAtMost(604800) ?: 0
        fun parseFeed(xml: String, url: String, identityUrl: String = url): PodcastFeed {
            require(xml.toByteArray().size <= MAX_BYTES)
            require(!xml.contains("<!DOCTYPE", ignoreCase = true)) { "External XML declarations are not supported" }
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
                // Android and desktop expose different parser feature sets. DTDs are also
                // rejected above and the entity resolver below never opens a remote resource.
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            }
            val builder = factory.newDocumentBuilder().apply { setEntityResolver { _, _ -> InputSource(java.io.StringReader("")) } }
            val document = builder.parse(InputSource(java.io.StringReader(xml.removePrefix("\uFEFF"))))
            val root = document.documentElement
            val channel = root.children().firstOrNull { it.local() == "channel" } ?: root.takeIf { it.local() == "feed" }
                ?: error("This address is not a podcast RSS or Atom feed")
            fun Element.field(name: String): String = children().firstOrNull { it.local() == name }?.textContent.orEmpty().trim()
            fun resolve(raw: String): String = httpUrl(runCatching { URI(url).resolve(raw.trim()).toString() }.getOrDefault("")).orEmpty()
            val title = channel.field("title").takeIf { it.isNotBlank() } ?: error("The feed has no title")
            val image = channel.children().firstOrNull { it.local() == "image" }
            val artwork = image?.getAttribute("href")?.takeIf(String::isNotBlank) ?: image?.field("url").orEmpty()
            val show = PodcastShow(identity(identityUrl), title, channel.field("author"), identityUrl, resolve(artwork), resolve(channel.field("link")),
                funding = channel.children().filter { it.local() == "funding" }.take(4).mapNotNull { link ->
                    resolve(link.getAttribute("url")).takeIf(String::isNotBlank)?.let { PodcastLink(it, link.textContent.trim().take(120).ifBlank { "Support the show" }) }
                },
                people = peopleOf(channel, ::resolve))
            val episodes = channel.children().asSequence().filter { it.local() in setOf("item", "entry") }.take(1000).mapNotNull { entry ->
                val enclosure = entry.children().firstOrNull { it.local() == "enclosure" || it.local() == "link" && it.getAttribute("rel") == "enclosure" }
                    ?: return@mapNotNull null
                val type = enclosure.getAttribute("type")
                if (type.isNotBlank() && !type.startsWith("audio/", true) && type !in setOf("application/octet-stream", "application/ogg")) return@mapNotNull null
                val audio = resolve(enclosure.getAttribute("url").ifBlank { enclosure.getAttribute("href") }).takeIf(String::isNotBlank) ?: return@mapNotNull null
                val name = entry.field("title").takeIf(String::isNotBlank) ?: return@mapNotNull null
                val guid = entry.field("guid").ifBlank { entry.field("id") }.ifBlank { audio }
                val art = entry.children().firstOrNull { it.local() == "image" }?.getAttribute("href")?.let(::resolve).orEmpty()
                PodcastEpisode(identity(identityUrl + "|" + guid), name, title, audio, art.ifBlank { show.artwork }, duration(entry.field("duration")),
                    entry.field("pubDate").ifBlank { entry.field("published") }, Jsoup.parse(entry.field("description").ifBlank { entry.field("summary") }).text().take(4000),
                    chaptersUrl = entry.children().firstOrNull { it.local() == "chapters" && it.getAttribute("url").isNotBlank() }
                        ?.getAttribute("url")?.let(::resolve)?.takeIf(String::isNotBlank),
                    transcripts = entry.children().filter { it.local() == "transcript" }.take(12).mapNotNull { item ->
                        val type = item.getAttribute("type").lowercase().substringBefore(';').trim()
                        if (type !in TRANSCRIPT_TYPES) return@mapNotNull null
                        resolve(item.getAttribute("url")).takeIf(String::isNotBlank)?.let { PodcastTranscript(it, type, item.getAttribute("language").take(40)) }
                    }.sortedBy { transcriptRank(it.type) },
                    inlineChapters = podloveChapters(entry, ::resolve).ifEmpty {
                        PodcastExtras.notesChaptersOf(listOf("description", "encoded", "summary").map { entry.field(it) })
                    },
                    season = entry.field("season").toIntOrNull()?.takeIf { it in 1..10_000 },
                    number = entry.field("episode").toIntOrNull()?.takeIf { it in 1..100_000 },
                    people = peopleOf(entry, ::resolve),
                    comments = entry.children().filter { it.local() == "socialInteract" && it.getAttribute("protocol").equals("activitypub", true) }
                        .firstNotNullOfOrNull { resolve(it.getAttribute("uri")).takeIf(String::isNotBlank) })
            }.distinctBy { it.id }.toList()
            val next = channel.children().firstOrNull { it.local() == "link" && it.getAttribute("rel").equals("next", true) }
                ?.getAttribute("href")?.takeIf(String::isNotBlank)?.let(::resolve)?.takeIf { it.isNotBlank() && it != url }
            return PodcastFeed(show, episodes, next, listOf(url))
        }
        /** Timed formats first, then HTML, then plain text: the first one is what the Transcript tab opens. */
        private val TRANSCRIPT_TYPES = setOf("application/x-subrip", "application/srt", "text/srt", "text/vtt", "application/json", "text/html", "text/plain")
        private fun transcriptRank(type: String) = when (type) { "text/html" -> 1; "text/plain" -> 2; else -> 0 }
        private fun podloveChapters(entry: Element, resolve: (String) -> String): List<PodcastChapter> =
            entry.children().firstOrNull { it.local() == "chapters" && it.getAttribute("url").isBlank() && it.children().any { child -> child.local() == "chapter" } }
                ?.children()?.filter { it.local() == "chapter" }?.take(500)?.mapNotNull { chapter ->
                    val start = PodcastExtras.npt(chapter.getAttribute("start")) ?: return@mapNotNull null
                    val title = chapter.getAttribute("title").trim().take(300).takeIf(String::isNotEmpty) ?: return@mapNotNull null
                    PodcastChapter(start, title, chapter.getAttribute("image").takeIf(String::isNotBlank)?.let(resolve)?.takeIf(String::isNotBlank))
                }?.distinctBy { it.startMs }?.sortedBy { it.startMs }.orEmpty()
        /** Podcasting 2.0's "podcast:person" entries under [parent] (at most 12), each with its role, page and picture. */
        private fun peopleOf(parent: Element, resolve: (String) -> String): List<PodcastPerson> =
            parent.children().filter { it.local() == "person" }.take(12).mapNotNull { person ->
                val name = person.textContent.trim().take(120).takeIf(String::isNotEmpty) ?: return@mapNotNull null
                PodcastPerson(name, person.getAttribute("role").trim().lowercase().take(40).ifBlank { "host" },
                    person.getAttribute("href").takeIf(String::isNotBlank)?.let(resolve).orEmpty(), person.getAttribute("img").takeIf(String::isNotBlank)?.let(resolve).orEmpty())
            }.distinctBy { it.name.lowercase() to it.role }
        private fun Element.local(): String = localName ?: tagName.substringAfter(':')
        private fun Element.children(): List<Element> = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
        private fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
        private fun JsonObject.number(key: String): Double? = text(key).toDoubleOrNull()?.takeIf(Double::isFinite)
    }
}

/** Only public web-session cookies created by this client; never a browser/account cookie store. */
private class AnonymousSpotifyCookies : CookieJar {
    private val cookies = ArrayList<Cookie>()
    @Synchronized override fun saveFromResponse(url: HttpUrl, incoming: List<Cookie>) {
        if (url.host != "open.spotify.com") return
        for (cookie in incoming.filter { it.name != "sp_dc" }) {
            cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            cookies += cookie
        }
        cookies.removeAll { it.expiresAt <= System.currentTimeMillis() }
        while (cookies.size > 32) cookies.removeAt(0)
    }
    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> =
        if (url.host == "open.spotify.com") cookies.filter { it.matches(url) && it.expiresAt > System.currentTimeMillis() } else emptyList()
}

@Serializable data class PodioSaved(val stations: List<RadioStation> = emptyList(), val shows: List<PodcastShow> = emptyList(),
    val progress: Map<String, Long> = emptyMap(), val completedEpisodes: Set<String> = emptySet(), val inboxEpisodesPerShow: Int = 12,
    val skipSilenceByShow: Map<String, Boolean> = emptyMap(),
    /** Episodes (their player ids) put away from Following's New list with "Seen" or "Mark all seen". */
    val seenEpisodes: Set<String> = emptySet(),
    /** The speed chosen with "Use for this show", by show id; the others play at 1×. */
    val speedByShow: Map<String, Float> = emptyMap(),
    /** The episode ids each followed show had at the last refresh, so the next one can tell what is new ([PodioRefresh]). */
    val knownEpisodes: Map<String, List<String>> = emptyMap(),
    /** When followed shows were last refreshed (in the background or from Following), epoch milliseconds; 0 for never. */
    val lastRefreshMs: Long = 0,
    /** Refresh followed shows in the background and say when there are new episodes. */
    val refreshInBackground: Boolean = true,
    /** Download new episodes of followed shows as the background refresh finds them. Off unless chosen. */
    val autoDownloadNew: Boolean = false,
    /** Each followed show's own settings ([PodioShowSettings]), by show id; a show at every default has none. */
    val showSettings: Map<String, PodioShowSettings> = emptyMap(),
    /** Remove a downloaded episode by itself once it is played (a show can choose otherwise). */
    val deletePlayedDownloads: Boolean = false,
    /** Keep only this many of each show's newest downloads; 0 keeps all (a show can choose otherwise). */
    val keepDownloadsPerShow: Int = 0,
    /** Moments marked in episodes ([PodioBookmarks]), oldest first. */
    val bookmarks: List<PodioBookmark> = emptyList(),
    /** Lists that fill themselves from the followed shows ([PodioSmartLists]). */
    val smartLists: List<PodioSmartList> = emptyList(),
    /** A station recording starts a new file at each new song the station names. */
    val recordBySong: Boolean = true,
    /** The stations played lately, the latest first ([PodioHistory]). */
    val recentStations: List<RadioStation> = emptyList(),
    /** The songs the stations named while they played, the latest first. */
    val heardSongs: List<PodioHeardSong> = emptyList(),
    /** Each followed show's tags ([PodioTags]), by show id. */
    val showTags: Map<String, Set<String>> = emptyMap(),
    /** Episodes kept as favourites ([PodioFavourites]), the newest first. */
    val favouriteEpisodes: List<PodioQueueEntry> = emptyList(),
    /** The Next button of headphones (or the car) bookmarks the playing episode instead of skipping it. */
    val headsetBookmarks: Boolean = false,
    /** Speech plays louder and clearer (a show can choose otherwise). */
    val voiceBoost: Boolean = false,
    /** When the saved stations were last checked against the directory ([PodioStationCheck]). */
    val stationsCheckedMs: Long = 0,
    /** Listening by day ("2026-11-23") and show id ([PodioStats]). */
    val listened: Map<String, Map<String, Long>> = emptyMap(),
    /** The radio alarm ([PodioAlarms]), or none. */
    val alarm: PodioAlarm? = null,
    /** The user names and passwords of private feeds ([PodioFeedLogins]), by feed address; they stay on this device. */
    val feedLogins: Map<String, PodioFeedLogin> = emptyMap(),
    /** The PIN that following a new show asks for ([PodioFollowLock]), or none. */
    val followLock: PodioFollowLockPin? = null,
    /** Save an episode whole as it starts streaming, so it plays on with no connection. */
    val keepStreamed: Boolean = false,
    /** Scrobble the songs stations name ([PodioRadioScrobble]) to the listener's Last.fm or ListenBrainz. */
    val scrobbleRadio: Boolean = false)
class PodioLibrary(private val file: File) {
    companion object {
        private class Store(file: File) {
            val lock = Any()
            val state = kotlinx.coroutines.flow.MutableStateFlow(runCatching {
                Json { ignoreUnknownKeys = true }.decodeFromString<PodioSaved>(file.readText())
            }.getOrDefault(PodioSaved()).also { PodioFeedLogins.known = it.feedLogins })
        }
        private val stores = java.util.concurrent.ConcurrentHashMap<String, java.lang.ref.WeakReference<Store>>()
        private fun sharedStore(file: File): Store = synchronized(stores) {
            val key = file.canonicalPath
            stores[key]?.get() ?: Store(file).also { store ->
                stores.entries.removeIf { it.value.get() == null }
                stores[key] = java.lang.ref.WeakReference(store)
            }
        }
        fun desktopFile(): File {
            val home = System.getProperty("user.home")
            val os = System.getProperty("os.name").lowercase(Locale.ROOT)
            val base = when {
                os.contains("win") -> File(System.getenv("APPDATA") ?: home, "Cosmotune")
                os.contains("mac") -> File(home, "Library/Application Support/Cosmotune")
                else -> File(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share", "cosmotune")
            }
            return File(base, "podio/library.json")
        }
    }
    private val json = Json { ignoreUnknownKeys = true }
    private val store = sharedStore(file)
    val state = store.state

    /** Podio's queue, kept beside this library. */
    fun queue(): PodioQueue = PodioQueue.besides(file)
    fun update(change: (PodioSaved) -> PodioSaved) = synchronized(store.lock) {
        val next = change(state.value).let {
            val progress = it.progress.entries.toList().takeLast(1000).associate { e -> e.toPair() }
            val shows = it.shows.take(500)
            val followed = shows.map(PodcastShow::id).toSet()
            it.copy(stations = it.stations.take(500), shows = shows, progress = progress, completedEpisodes = it.completedEpisodes.intersect(progress.keys),
                skipSilenceByShow = it.skipSilenceByShow.filterKeys(followed::contains),
                speedByShow = it.speedByShow.filterKeys(followed::contains).filterValues { speed -> speed in 0.5f..3f },
                showSettings = it.showSettings.filterKeys(followed::contains), showTags = it.showTags.filterKeys(followed::contains),
                favouriteEpisodes = it.favouriteEpisodes.take(PodioFavourites.MAX),
                knownEpisodes = it.knownEpisodes.filterKeys(followed::contains).mapValues { (_, ids) -> ids.take(PodioRefresh.KNOWN_PER_SHOW) },
                seenEpisodes = it.seenEpisodes.toList().takeLast(2000).toSet(), bookmarks = it.bookmarks.takeLast(PodioBookmarks.MAX),
                // Logins of followed shows stay; a few more for feeds opened but not followed yet.
                feedLogins = it.feedLogins.entries.sortedByDescending { (feed, _) -> shows.any { show -> show.feed == feed } }.take(50).associate { e -> e.toPair() })
        }
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(json.encodeToString(next))
        java.nio.file.Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        state.value = next
        PodioFeedLogins.known = next.feedLogins
    }

    fun importOpml(xml: String): Int {
        val shows = PodioOpml.decode(xml)
        // An OPML folder becomes a tag of the shows inside it.
        val folders = PodioTags.foldersOf(xml)
        var added = 0
        update { old ->
            val combined = (old.shows + shows).distinctBy { it.feed.trimEnd('/') }
            require(combined.size <= 500) { "The library supports up to 500 followed podcasts." }
            added = combined.size - old.shows.size
            combined.fold(old.copy(shows = combined)) { acc, show ->
                val tags = folders[show.feed.trimEnd('/')] ?: return@fold acc
                PodioTags.set(acc, show.id, acc.showTags[show.id].orEmpty() + tags)
            }
        }
        return added
    }

    fun resumePosition(id: String, durationMs: Long): Long {
        if (id in state.value.completedEpisodes) return 0
        return state.value.progress[id]?.coerceAtLeast(0)
            ?.takeUnless { PodioPlaybackPolicy.isFinished(it, durationMs) } ?: 0
    }

    fun recordProgress(id: String, positionMs: Long, durationMs: Long = 0) {
        if (!id.startsWith("podio:episode:")) return
        update { old -> old.copy(progress = (old.progress - id) + (id to positionMs.coerceAtLeast(0)),
            completedEpisodes = when {
                durationMs > 0 -> if (PodioPlaybackPolicy.isFinished(positionMs, durationMs)) old.completedEpisodes + id else old.completedEpisodes - id
                positionMs <= 0 -> old.completedEpisodes - id
                else -> old.completedEpisodes
            }) }
    }
}
