package com.opencanvas.core.stream

import com.opencanvas.core.matcher.VideoCandidate
import com.opencanvas.core.matcher.VideoMatcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Dispatcher
import java.io.IOException

/**
 * Finds the music video of a song by searching YouTube, with no API key and no server.
 *
 * Each result is read as a structured record (channel, length, verified-artist badge) so clips,
 * Shorts, lyric videos, live takes and covers can be ranked out before anything is downloaded; the
 * caller is expected to confirm the top few against the song's audio (see `CanvasSession`).
 */
internal class MusicVideoSearch(private val http: OkHttpClient, private val log: (String) -> Unit = {}) {
    private val publishers = PublisherChannelProof(http)

    /**
     * The searches' own dispatcher over the same connections: the client's default allows five calls per host, and a song
     * that has just started has the player, radio and video lookups already queued to youtube.com ahead of a search.
     */
    private val searchHttp = http.newBuilder().dispatcher(Dispatcher().apply { maxRequestsPerHost = 8; maxRequests = 32 }).build()

    /** The most likely music videos for the song, best first (at most [limit]); empty when the search fails. */
    suspend fun find(title: String, artist: String, durationSec: Long = 0L, limit: Int = 6, album: String? = null,
        exhaustive: Boolean = false, excludedVideoIds: Set<String> = emptySet()): List<VideoCandidate> = run {
        // "Sakkarakatti (Original Motion Picture Soundtrack)" is searched, and matched against titles, as "Sakkarakatti".
        val album = album?.let(VideoMatcher::cleanFilmName)
        val lookupTitle = VideoMatcher.searchTitleVariants(title).last()
        val words = lookupTitle.trim().split(Regex("\\s+"))
        val titleKey = lookupTitle.lowercase().filter { it.isLetterOrDigit() }
        val albumKey = album.orEmpty().lowercase().filter { it.isLetterOrDigit() }
        // A title track shares its name with every result from the same film.
        // Without useful album context, another verified artist's same-title
        // video must not suppress the title-only publisher fallback.
        val ambiguousContext = albumKey.isEmpty() || albumKey == titleKey
        val queries = listOfNotNull(
            if (exhaustive && words.size >= 3) album?.let { "$it ${words.drop(1).joinToString(" ")} video song -\"5.1\" -audio -lyrics" } else null,
            if (exhaustive) "$lookupTitle ${album.orEmpty()} video song -\"5.1\" -audio -lyrics -lyrical" else null,
            if (exhaustive) album?.let { "$it movie songs $lookupTitle" } else null,
            album?.trim()?.takeIf { it.isNotEmpty() }?.let { "$lookupTitle $it video song" },
            album?.takeIf { it.isNotBlank() && words.size >= 3 }?.let { "\"${words.take(2).joinToString(" ")}\" $it video song" },
            album?.takeIf { it.isNotBlank() && words.size == 2 }?.let { "\"${words.reversed().joinToString(" ")}\" $it" },
            "\"$lookupTitle\" video song",
            "$artist $lookupTitle official music video",
            "\"$title\" $artist official video").distinct()
        val results = ArrayList<VideoCandidate>()
        val titleWords = words.map { word -> word.lowercase().filter { it.isLetterOrDigit() } }.filter { it.length >= 3 }
        val startedAt = System.nanoTime()
        for (query in queries) {
            // Searches run one after another: a slow network must not hold the song's video for half a minute.
            if (results.isNotEmpty() && (System.nanoTime() - startedAt) / 1_000_000L > SEARCH_BUDGET_MS) break
            val queryStarted = System.nanoTime()
            val answer = withTimeoutOrNull(QUERY_TIMEOUT_MS) { search(query) }
            val raw = answer.orEmpty()
            val toCorroborate = raw.firstOrNull { it.videoId !in excludedVideoIds && VideoMatcher.needsPublisherCorroboration(it, title, artist, album) }
            val batch = raw.map { candidate ->
                if (candidate === toCorroborate &&
                    publishers.proves(candidate.channelId!!)) {
                    log("publisher channel corroborated by verified parent: ${candidate.channelId}")
                    candidate.copy(linkedVerifiedPublisher = true)
                } else candidate
            }
            val tookMs = (System.nanoTime() - queryStarted) / 1_000_000L
            // A search that does not answer in time is a miss for the song, so it is worth a line even when nothing else is logged.
            if (answer == null) log("video search '$query': no answer within $QUERY_TIMEOUT_MS ms")
            else if (batch.isEmpty() && !exhaustive) log("video search '$query': no results after $tookMs ms")
            results += batch
            val ranked = VideoMatcher.rank(results.distinctBy { it.videoId }
                .filter { it.videoId !in excludedVideoIds && VideoMatcher.hasPublisherEvidence(it, title, artist, album) }, title, artist, durationSec, limit, album)
            if (exhaustive) log("expanded video search '$query': ${batch.size} results in $tookMs ms, eligible ${ranked.joinToString { it.videoId }}")
            if (exhaustive && ranked.isNotEmpty()) return@run ranked
            // Do not wait for speculative fallbacks when a publisher's matching song was found.
            // An album-only hit is insufficient: another track from the same film must not stop search.
            if (!exhaustive && ranked.any { candidate ->
                    val name = candidate.title.lowercase().filter { it.isLetterOrDigit() }
                    val titleMatches = name.contains(lookupTitle.lowercase().filter { it.isLetterOrDigit() }) ||
                        (titleWords.size >= 2 && titleWords.all { name.contains(it) })
                    val contextMatches = !ambiguousContext || VideoMatcher.isCreditedArtistChannel(candidate, artist)
                    titleMatches && contextMatches && VideoMatcher.advertisesVideo(candidate.title)
                }) return@run ranked
        }
        val distinct = results.distinctBy { it.videoId }
        // Matching audio can be attached to unrelated footage. Missing publisher evidence is a miss,
        // never permission to promote an unverified fan edit into an automatic music video.
        val publisherVideos = VideoMatcher.rank(distinct.filter { it.videoId !in excludedVideoIds && VideoMatcher.hasPublisherEvidence(it, title, artist, album) }, title, artist, durationSec, limit, album)
        if (publisherVideos.isNotEmpty()) return@run publisherVideos
        // No publisher has the video: an upload by anyone else is tried, and the audio check has the last word.
        val uploads = VideoMatcher.rank(distinct.filter { it.videoId !in excludedVideoIds && VideoMatcher.hasFallbackEvidence(it, title, artist, album) }, title, artist, durationSec, limit, album)
        if (uploads.isNotEmpty()) log("no publisher video for '$title': trying ${uploads.joinToString { "${it.videoId} by ${it.channelTitle}" }}")
        uploads
    }

    /**
     * One search page. A connection that stalls (a dead address, a congested socket, a queue behind other calls) is not
     * waited out: the same request is sent again after a short pause, on a fresh connection, while the first stays
     * open, and the first valid answer wins. Every try that failed is named in the log, so a miss says why.
     */
    private suspend fun search(queryText: String): List<VideoCandidate> {
        val query = URLEncoder.encode(queryText, StandardCharsets.UTF_8.name())
        val request = Request.Builder()
            .url("https://www.youtube.com/results?search_query=$query")
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        val started = System.nanoTime()
        fun sinceMs() = (System.nanoTime() - started) / 1_000_000L
        val failures = java.util.concurrent.ConcurrentLinkedQueue<String>()
        var answered: List<VideoCandidate>? = null
        try {
            answered = coroutineScope {
                val tries = HEDGE_DELAYS_MS.mapIndexed { index, pause ->
                    async<List<VideoCandidate>?> {
                        delay(pause)
                        try {
                            val answer = searchHttp.newCall(request).within(ATTEMPT_TIMEOUT_MS).fetch()
                            val page = if (answer.isSuccessful) answer.text() else null
                            // A page that carries its data and no videos is a real "nothing found"; anything else (a consent
                            // page, a throttled reply) is worth another try.
                            if (page != null && page.contains("var ytInitialData = ")) parseSearchResults(page)
                            else { failures += "try ${index + 1}: HTTP ${answer.code} without results at ${sinceMs()} ms"; null }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: IOException) {
                            failures += "try ${index + 1}: ${failure.javaClass.simpleName} at ${sinceMs()} ms"
                            null
                        }
                    }
                }
                val waiting = tries.toMutableList()
                var winner: List<VideoCandidate>? = null
                while (winner == null && waiting.isNotEmpty()) {
                    val done = select<Deferred<List<VideoCandidate>?>> { waiting.forEach { attempt -> attempt.onAwait { attempt } } }
                    waiting.remove(done)
                    winner = done.await()
                }
                tries.forEach { it.cancel() }
                winner
            }
        } finally {
            val took = sinceMs()
            if (answered == null) log("video search '$queryText' got no page: ${failures.joinToString("; ").ifEmpty { "no try had finished" }} (${took} ms)")
            else if (took > SLOW_ANSWER_MS || failures.isNotEmpty()) log("video search '$queryText' answered in $took ms${if (failures.isEmpty()) "" else " after " + failures.joinToString("; ")}")
        }
        return answered.orEmpty()
    }

    internal companion object {
        private const val QUERY_TIMEOUT_MS = 8_000L
        private const val ATTEMPT_TIMEOUT_MS = 6_000L
        private const val SLOW_ANSWER_MS = 2_500L
        private val HEDGE_DELAYS_MS = listOf(0L, 2_000L, 4_500L)
        private const val SEARCH_BUDGET_MS = 6_000L
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

        /**
         * Reads the video results of a YouTube search page, in page order. Shorts are left out; each
         * candidate carries its channel, length and whether the channel is a verified artist.
         */
        fun parseSearchResults(html: String): List<VideoCandidate> {
            val marker = "var ytInitialData = "
            val start = html.indexOf(marker).takeIf { it >= 0 } ?: return emptyList()
            val end = html.indexOf(";</script>", start).takeIf { it >= 0 } ?: return emptyList()
            val root = runCatching { Json.parseToJsonElement(html.substring(start + marker.length, end)) }.getOrNull()
                ?: return emptyList()
            val found = ArrayList<VideoCandidate>()
            collect(root, found)
            return found.distinctBy { it.videoId }
        }

        private fun collect(element: JsonElement, out: MutableList<VideoCandidate>) {
            when (element) {
                is JsonObject -> element.forEach { (key, value) ->
                    if (key == "videoRenderer" && value is JsonObject) toCandidate(value)?.let(out::add) else collect(value, out)
                }
                is JsonArray -> element.forEach { collect(it, out) }
                else -> Unit
            }
        }

        private fun toCandidate(renderer: JsonObject): VideoCandidate? {
            val videoId = text(renderer["videoId"]) ?: return null
            val url = text(renderer.path("navigationEndpoint", "commandMetadata", "webCommandMetadata", "url"))
            if (url != null && "/shorts/" in url) return null
            val verifiedArtist = (renderer["ownerBadges"] as? JsonArray).orEmpty().any {
                text((it as? JsonObject)?.path("metadataBadgeRenderer", "style"))?.contains("VERIFIED_ARTIST") == true
            }
            val verified = (renderer["ownerBadges"] as? JsonArray).orEmpty().any {
                text((it as? JsonObject)?.path("metadataBadgeRenderer", "style")) in
                    setOf("BADGE_STYLE_TYPE_VERIFIED", "BADGE_STYLE_TYPE_VERIFIED_ARTIST")
            }
            return VideoCandidate(
                videoId = videoId,
                title = runs((renderer["title"] as? JsonObject)?.get("runs")),
                channelTitle = runs((renderer["ownerText"] as? JsonObject)?.get("runs")),
                durationSec = text(renderer.path("lengthText", "simpleText"))?.let(::parseClock) ?: 0L,
                isOfficialMusicVideo = verifiedArtist,
                isVerifiedChannel = verified,
                channelId = (renderer["ownerText"] as? JsonObject)?.get("runs")
                    ?.let { it as? JsonArray }?.firstOrNull()?.let { it as? JsonObject }
                    ?.path("navigationEndpoint", "browseEndpoint", "browseId")?.let(::text),
            )
        }

        private fun JsonObject.path(vararg keys: String): JsonElement? {
            var current: JsonElement = this
            for (key in keys) current = (current as? JsonObject)?.get(key) ?: return null
            return current
        }

        private fun text(element: JsonElement?): String? = (element as? JsonPrimitive)?.contentOrNull

        private fun runs(element: JsonElement?): String =
            (element as? JsonArray)?.joinToString("") { text((it as? JsonObject)?.get("text")).orEmpty() }.orEmpty()

        /** `4:22` or `1:02:05` as seconds. */
        private fun parseClock(clock: String): Long? {
            val parts = clock.split(':').map { it.trim().toLongOrNull() ?: return null }
            return parts.fold(0L) { total, part -> total * 60 + part }
        }
    }
}
