package com.cosmotune.watch.live

import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.source.VideoSource
import com.cosmotune.watch.WatchPlace
import com.cosmotune.watch.LanguageCodes
import com.cosmotune.watch.http.WatchHttp
import com.cosmotune.watch.model.LiveEvent
import com.cosmotune.watch.model.LiveEventKind
import com.cosmotune.watch.model.LiveEventStatus
import com.cosmotune.watch.model.ExternalIds
import com.cosmotune.watch.model.PlayTarget
import com.cosmotune.watch.model.WatchKey
import com.cosmotune.watch.model.WatchOption
import com.cosmotune.watch.plugin.BuiltInPlugins
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

internal class SpotlightSources(private val http: WatchHttp, private val videoSource: () -> VideoSource?) {
    private val lock = Mutex()
    private var cached: List<LiveEvent> = emptyList()
    private var cachedPlace: WatchPlace? = null
    private var cachedAt = 0L

    suspend fun events(place: WatchPlace, now: Long): List<LiveEvent> = lock.withLock {
        if (cachedPlace == place && now - cachedAt in 0 until REFRESH_MS) return cached
        cached = coroutineScope {
            val news = async(Dispatchers.IO) { available { news(place, now) } }
            val podcasts = async { available { chart("podcasts", LiveEventKind.PODCAST, now) } }
            val videos = async { available { chart("music", LiveEventKind.VIDEO, now) } }
            news.await() + podcasts.await() + videos.await()
        }
        cachedPlace = place
        cachedAt = now
        cached
    }

    private suspend fun <T> available(block: suspend () -> List<T>): List<T> = try {
        withTimeoutOrNull(4_000) { block() }.orEmpty()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        emptyList()
    }

    private suspend fun news(place: WatchPlace, now: Long): List<LiveEvent> {
        val country = place.country.uppercase(Locale.ROOT).takeIf { it.matches(Regex("[A-Z]{2}")) } ?: "US"
        val language = LanguageCodes.normalise(place.language).takeIf { it in NEWS_LANGUAGES[country].orEmpty() } ?: "en"
        val url = "https://news.google.com/rss?hl=$language-$country&gl=$country&ceid=$country:$language"
        return parseHeadlines(http.getText(url, ttlMs = REFRESH_MS, maxBytes = 1_000_000), now)
    }

    private suspend fun chart(id: String, kind: LiveEventKind, now: Long): List<LiveEvent> {
        val source = videoSource() ?: return emptyList()
        if (source.kiosks().none { it.id == id }) return emptyList()
        return source.kiosk(id).items.asSequence()
            .filter { !it.isLive && !it.isUpcoming && !it.isShort && VIDEO_ID.matches(it.id) }
            .filter { it.uploadedAtMs?.let { date -> now - date in 0..7 * DAY } == true }
            .distinctBy(VideoItem::id).take(3).map { item ->
                val target = PlayTarget.Film(WatchKey.film(BuiltInPlugins.YOUTUBE, item.id), ExternalIds(), item.title)
                LiveEvent("spotlight:${kind.name}:${item.id}", kind, item.title, status = LiveEventStatus.FEATURED,
                    startMs = item.uploadedAtMs, imageUrl = item.thumbnailUrl, source = "YouTube",
                    watchOptions = listOf(WatchOption("YouTube", target, badge = "YT")),
                    updatedAtMs = now, link = "https://www.youtube.com/watch?v=${item.id}")
            }.toList()
    }

    companion object {
        private const val REFRESH_MS = 15 * 60_000L
        private const val DAY = 86_400_000L
        private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
        private val NEWS_LANGUAGES = mapOf("IN" to setOf("hi", "ta", "te", "ml", "bn", "mr", "gu", "kn"),
            "FR" to setOf("fr"), "DE" to setOf("de"), "ES" to setOf("es"), "BR" to setOf("pt"))

        internal fun parseHeadlines(xml: String, now: Long): List<LiveEvent> {
            if (xml.contains("<!DOCTYPE", ignoreCase = true)) return emptyList()
            val factory = DocumentBuilderFactory.newInstance().apply {
                isExpandEntityReferences = false
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            }
            val parser = factory.newDocumentBuilder().apply { setEntityResolver { _, _ -> InputSource(StringReader("")) } }
            val items = parser.parse(InputSource(StringReader(xml))).getElementsByTagName("item")
            return (0 until items.length).asSequence().mapNotNull { index ->
                val item = items.item(index) as? Element ?: return@mapNotNull null
                fun field(name: String) = item.getElementsByTagName(name).item(0)?.textContent?.trim().orEmpty()
                val url = field("link").toHttpUrlOrNull()?.takeIf { it.isHttps && it.host == "news.google.com" }
                    ?: return@mapNotNull null
                val date = runCatching { ZonedDateTime.parse(field("pubDate"), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
                    .getOrNull()?.takeIf { now - it in 0..DAY } ?: return@mapNotNull null
                val publisher = field("source")
                val title = field("title").removeSuffix(" - $publisher").trim().take(240).ifBlank { return@mapNotNull null }
                LiveEvent("spotlight:news:${url.encodedPath}", LiveEventKind.NEWS, title, status = LiveEventStatus.FEATURED,
                    startMs = date, source = publisher.ifBlank { "Google News" }, updatedAtMs = now, link = url.toString())
            }.distinctBy { it.id }.take(4).toList()
        }
    }
}
