package com.cosmotune.watch.live

import com.cosmotune.watch.LanguageCodes
import com.cosmotune.watch.WatchPlace
import com.cosmotune.watch.http.WatchHttp
import com.cosmotune.watch.http.urlEncode
import com.cosmotune.watch.model.LiveEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * The Spotlight's news and coming events, keyless: the world's feeds ([NewsFeeds.WORLD]) grouped into stories and ranked by how many
 * outlets carry them, Wikipedia "In the news" (editors' picks, marking the major stories), Google News for the viewer's own country
 * (its own tab), and elections and summits coming up ([MajorEvents]). Each source is read at most every [newsTtlMs] (Wikidata twice
 * a day), within a few seconds, and one that fails or is slow is left out.
 */
class SpotlightSources(
    private val http: WatchHttp,
    private val feeds: List<NewsFeeds.Feed> = NewsFeeds.WORLD,
    private val wikipedia: String = "https://en.wikipedia.org/api/rest_v1/feed/featured",
    private val googleNews: String = "https://news.google.com",
    private val wikidata: String = MajorEvents.ENDPOINT,
    private val newsTtlMs: Long = 10 * 60_000L,
    private val timeoutMs: Long = 6_000,
) {
    /** World stories, then Wikipedia's, then the country's: never live. */
    suspend fun news(place: WatchPlace, nowMs: Long): List<LiveEvent> = coroutineScope {
        val world = feeds.map { f -> async { quietly { NewsFeeds.parseRss(http.getText(f.url, ttlMs = newsTtlMs, maxBytes = 2_000_000), f.outlet, nowMs) } } }
        val itn = async { quietly { NewsFeeds.parseItn(http.getJson("$wikipedia/${day(nowMs)}", headers = POLITE, ttlMs = 30 * 60_000L)) } }
        val country = async { quietly { country(place, nowMs) } }
        val stories = NewsFeeds.group(world.awaitAll().flatten())
        val editors = itn.await()
        NewsFeeds.toEvents(stories, editors, nowMs) + NewsFeeds.itnEvents(editors, nowMs) + country.await()
    }

    /** Elections and summits of the next 60 days; the query is slow (up to 20 s) the first time, then kept half a day. */
    suspend fun majorEvents(nowMs: Long): List<LiveEvent> = try {
        val q = MajorEvents.query(nowMs - DAY, nowMs + SpotlightWindows.EVENTS_AHEAD_MS + DAY).urlEncode()
        // The day in the address keeps the answer for the day: the cache key stays put between the two reads a day.
        MajorEvents.parse(http.getJson("$wikidata?format=json&query=$q", headers = POLITE + ("Accept" to "application/sparql-results+json"), ttlMs = 12 * HOUR), nowMs)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        emptyList()
    }

    private suspend fun country(place: WatchPlace, nowMs: Long): List<LiveEvent> {
        val code = place.country.uppercase(Locale.ROOT).takeIf { it.matches(Regex("[A-Z]{2}")) } ?: return emptyList()
        val language = LanguageCodes.normalise(place.language).takeIf { it in NEWS_LANGUAGES[code].orEmpty() } ?: "en"
        val url = "$googleNews/rss?hl=$language-$code&gl=$code&ceid=$code:$language"
        val name = Locale("", code).getDisplayCountry(Locale.ENGLISH).ifBlank { code }
        return NewsFeeds.parseCountry(http.getText(url, ttlMs = newsTtlMs, maxBytes = 2_000_000), name, nowMs)
    }

    private suspend fun <T> quietly(block: suspend () -> List<T>): List<T> = try {
        withTimeoutOrNull(timeoutMs) { block() }.orEmpty()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        emptyList()
    }

    companion object {
        private const val HOUR = 3_600_000L
        private const val DAY = 24 * HOUR
        /** Wikimedia asks for a User-Agent that says who calls; nothing about the person is in it. */
        private val POLITE = mapOf("User-Agent" to "Cosmotune/1.0 (open-source media app; Spotlight)", "Api-User-Agent" to "Cosmotune Spotlight")
        private val NEWS_LANGUAGES = mapOf("IN" to setOf("hi", "ta", "te", "ml", "bn", "mr", "gu", "kn"),
            "FR" to setOf("fr"), "DE" to setOf("de"), "ES" to setOf("es"), "BR" to setOf("pt"))

        private fun day(ms: Long): String = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString().replace('-', '/')
    }
}
