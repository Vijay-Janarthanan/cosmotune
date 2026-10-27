package com.cosmotune.podio

import com.cosmotune.music.data.model.Song
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
    val codec: String = "", val bitrate: Int = 0, val state: String = "")
@Serializable data class PodcastShow(val id: String, val title: String, val author: String,
    val feed: String, val artwork: String = "", val website: String = "", val source: String = "Publisher feed")
@Serializable data class PodcastEpisode(val id: String, val title: String, val show: String, val url: String,
    val artwork: String = "", val durationSeconds: Long = 0, val published: String = "", val description: String = "")
data class PodcastFeed(val show: PodcastShow, val episodes: List<PodcastEpisode>)

/** The weather where a station is, from Open-Meteo (CC BY 4.0). [utcOffsetSeconds] is the place's own clock; [sunrise] and [sunset] are local "HH:mm". */
data class PodioWeather(val temperatureC: Double, val code: Int, val isDay: Boolean, val windKmh: Double, val utcOffsetSeconds: Int, val sunrise: String, val sunset: String) {
    /** WMO weather interpretation code, in words. */
    val summary: String get() = when (code) {
        0 -> "Clear sky"; 1 -> "Mostly clear"; 2 -> "Partly cloudy"; 3 -> "Overcast"; 45, 48 -> "Fog"
        in 51..57 -> "Drizzle"; in 61..67 -> "Rain"; in 71..77 -> "Snow"; in 80..82 -> "Rain showers"; 85, 86 -> "Snow showers"; in 95..99 -> "Thunderstorm"
        else -> "Weather"
    }
}

/** A well-known place near a station, from Wikipedia (text CC BY-SA 4.0): its name, the first sentences of its article, a picture and the article address. */
data class PodioHighlight(val title: String, val summary: String, val image: String, val url: String)
data class PodioItem(val id: String, val title: String, val author: String, val url: String,
    val artwork: String = "", val live: Boolean = false, val durationSeconds: Long = 0) {
    fun song(): Song = Song(videoId = id, title = title, artist = author, thumbnailUrl = artwork.takeIf { it.isNotBlank() },
        durationText = durationSeconds.takeIf { it > 0 }?.let { "${it / 60}:${(it % 60).toString().padStart(2, '0')}" },
        localUri = requireNotNull(PodioCatalogue.httpUrl(url)), albumName = if (live) "Podio · Live radio" else "Podio · Podcast")
}
fun RadioStation.item() = PodioItem("podio:radio:$id", name, listOf(country, language).filter(String::isNotBlank).joinToString(" · "), url, artwork, live = true)
fun PodcastEpisode.item() = PodioItem("podio:episode:$id", title, show, url, artwork, durationSeconds = durationSeconds)
val Song.isPodio: Boolean get() = videoId.startsWith("podio:radio:") || videoId.startsWith("podio:episode:")
fun podioSourceName(id: String): String = when {
    id.startsWith("podio:radio") -> "Radio Browser"
    else -> "Podcast publisher"
}

/** Account-free directories and publisher RSS audio. Spotify links supply metadata, never protected streams. */
class PodioCatalogue(private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).cookieJar(AnonymousSpotifyCookies()).build(),
    private val radioMirrors: List<String>? = null,
    private val appleBase: String = "https://itunes.apple.com", private val fyydBase: String = "https://api.fyyd.de",
    private val spotifyMetadata: (suspend (String) -> PodcastFeed)? = null,
    private val weatherBase: String = "https://api.open-meteo.com", private val wikipediaBase: String = "https://en.wikipedia.org") {
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
    suspend fun stations(query: String = "", countryCode: String = "", language: String = "", globe: Boolean = false): List<RadioStation> = withContext(Dispatchers.Default) {
        val params = linkedMapOf("hidebroken" to "true", "order" to "clickcount", "reverse" to "true", "limit" to if (globe) "1200" else "3000")
        if (query.isNotBlank()) params["name"] = query.trim().take(200)
        if (countryCode.isNotBlank()) params["countrycode"] = countryCode.uppercase(Locale.ROOT).take(2)
        if (language.isNotBlank()) params["language"] = language.trim().lowercase(Locale.ROOT).take(60)
        if (globe) params["has_geo_info"] = "true"
        val directory = (radio("/json/stations/search", params) as? JsonArray).orEmpty().mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val url = httpUrl(o.text("url_resolved").ifBlank { o.text("url") }) ?: return@mapNotNull null
            val id = o.text("stationuuid").takeIf { it.matches(Regex("[a-zA-Z0-9-]{1,64}")) } ?: return@mapNotNull null
            RadioStation(id, o.text("name").trim().take(300), url, o.text("country"), o.text("countrycode"),
                o.text("language"), o.text("tags"), httpUrl(o.text("favicon")).orEmpty(),
                o.number("geo_lat")?.takeIf { it in -90.0..90.0 }, o.number("geo_long")?.takeIf { it in -180.0..180.0 },
                o.text("codec"), o.text("bitrate").toIntOrNull() ?: 0, o.text("state").trim().take(80))
        }.distinctBy { it.id }
        mergeExtras(directory, extras, query, countryCode, language)
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
    suspend fun stationPlayed(id: String) {
        if (!id.matches(Regex("[a-zA-Z0-9-]{1,64}"))) return
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
        try { call.execute().use { reply -> reply.takeIf { it.isSuccessful }?.body?.bytes()?.takeIf { it.size in 1..2_000_000 } } }
        catch (e: CancellationException) { throw e } catch (_: Exception) { null } finally { cancel?.dispose() }
    }

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
    suspend fun topPodcasts(country: String = "US", genre: String? = null, limit: Int = 30): List<PodcastShow> {
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
        val term = query.trim().take(200).ifBlank { "podcast" }
        val apple = async { attempt { appleSearch(term, country) } }
        val fyyd = async { attempt { fyydSearch(term) } }
        val a = apple.await(); val f = fyyd.await()
        if (a == null && f == null) error("Podcast search is unavailable. Please retry.")
        (a.orEmpty() + f.orEmpty()).distinctBy { it.feed.trimEnd('/') }
    }
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
        return withContext(Dispatchers.Default) { parseFeed(read(address), address) }
    }
    private suspend fun spotifyPublisherFeed(address: String): PodcastFeed {
        val metadata = spotifyMetadata?.invoke(address) ?: spotify.feed(address)
        val expected = metadata.episodes.singleOrNull().takeIf { SpotifyPublicPodcasts.link(address)?.first == "episode" }
        val candidates = podcasts(metadata.show.title).filter { titleKey(it.title) == titleKey(metadata.show.title) }.take(3)
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
    private suspend fun read(url: String): String = readRequest(Request.Builder().url(url).header("User-Agent", "Cosmotune-Podio/1 (+https://github.com/Vijay-Janarthanan/cosmotune)").build())
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
        fun httpUrl(raw: String): String? = runCatching {
            val value = raw.trim(); val uri = URI(value)
            value.takeIf { uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null }
        }.getOrNull()
        fun identity(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
        fun duration(value: String): Long = value.trim().split(':').takeIf { it.size in 1..3 }
            ?.map { it.toLongOrNull()?.takeIf { it >= 0 && it <= 604800 } ?: -1 }
            ?.takeIf { values -> values.all { it >= 0 } }
            ?.fold(0L) { total, part -> total * 60 + part }?.coerceAtMost(604800) ?: 0
        fun parseFeed(xml: String, url: String): PodcastFeed {
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
            val document = builder.parse(InputSource(java.io.StringReader(xml)))
            val root = document.documentElement
            val channel = root.children().firstOrNull { it.local() == "channel" } ?: root.takeIf { it.local() == "feed" }
                ?: error("This address is not a podcast RSS or Atom feed")
            fun Element.field(name: String): String = children().firstOrNull { it.local() == name }?.textContent.orEmpty().trim()
            fun resolve(raw: String): String = httpUrl(runCatching { URI(url).resolve(raw.trim()).toString() }.getOrDefault("")).orEmpty()
            val title = channel.field("title").takeIf { it.isNotBlank() } ?: error("The feed has no title")
            val image = channel.children().firstOrNull { it.local() == "image" }
            val artwork = image?.getAttribute("href")?.takeIf(String::isNotBlank) ?: image?.field("url").orEmpty()
            val show = PodcastShow(identity(url), title, channel.field("author"), url, resolve(artwork), resolve(channel.field("link")))
            val episodes = channel.children().asSequence().filter { it.local() in setOf("item", "entry") }.take(1000).mapNotNull { entry ->
                val enclosure = entry.children().firstOrNull { it.local() == "enclosure" || it.local() == "link" && it.getAttribute("rel") == "enclosure" }
                    ?: return@mapNotNull null
                val type = enclosure.getAttribute("type")
                if (type.isNotBlank() && !type.startsWith("audio/", true) && type !in setOf("application/octet-stream", "application/ogg")) return@mapNotNull null
                val audio = resolve(enclosure.getAttribute("url").ifBlank { enclosure.getAttribute("href") }).takeIf(String::isNotBlank) ?: return@mapNotNull null
                val name = entry.field("title").takeIf(String::isNotBlank) ?: return@mapNotNull null
                val guid = entry.field("guid").ifBlank { entry.field("id") }.ifBlank { audio }
                val art = entry.children().firstOrNull { it.local() == "image" }?.getAttribute("href")?.let(::resolve).orEmpty()
                PodcastEpisode(identity(url + "|" + guid), name, title, audio, art.ifBlank { show.artwork }, duration(entry.field("duration")),
                    entry.field("pubDate").ifBlank { entry.field("published") }, Jsoup.parse(entry.field("description").ifBlank { entry.field("summary") }).text().take(4000))
            }.distinctBy { it.id }.toList()
            return PodcastFeed(show, episodes)
        }
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
    val progress: Map<String, Long> = emptyMap())
class PodioLibrary(private val file: File) {
    companion object {
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
    val state = kotlinx.coroutines.flow.MutableStateFlow(runCatching { json.decodeFromString<PodioSaved>(file.readText()) }.getOrDefault(PodioSaved()))
    @Synchronized fun update(change: (PodioSaved) -> PodioSaved) {
        val next = change(state.value).let { it.copy(stations = it.stations.take(500), shows = it.shows.take(500), progress = it.progress.entries.toList().takeLast(1000).associate { e -> e.toPair() }) }
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(json.encodeToString(next))
        java.nio.file.Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        state.value = next
    }
}
