package com.opencanvas.core.stream

import com.cosmotune.youtube.YoutubeAds
import com.opencanvas.core.matcher.VideoCandidate
import com.opencanvas.core.matcher.VideoMatcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Dispatcher
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ProtocolException

/**
 * What a search for a song's music video came back with. [unanswered] counts the queries that got no page at all
 * (a timeout, a dropped connection, youtube.com asking for a captcha): an empty [candidates] list with unanswered
 * queries is a network failure, not proof that the song has no video, and must not be remembered as one.
 */
internal class VideoSearchOutcome(val candidates: List<VideoCandidate>, val unanswered: Int, val asked: Int) {
    val complete: Boolean get() = unanswered == 0
}

/**
 * Finds the music video of a song by searching YouTube, with no API key and no server.
 *
 * Each result is read as a structured record (channel, length, verified-artist badge) so clips,
 * Shorts, lyric videos, live takes and covers can be ranked out before anything is downloaded; the
 * caller is expected to confirm the top few against the song's audio (see `CanvasSession`).
 */
internal class MusicVideoSearch(private val http: OkHttpClient, private val log: (String) -> Unit = {}) {
    private val publishers = PublisherChannelProof(http)

    /** Which searches go first: the playing song's, then (one page at a time) the queue's. */
    val traffic = SearchTraffic()

    /**
     * The searches' own dispatcher over the same connections: the client's default allows five calls per host, and a song
     * that has just started has the player, radio and video lookups already queued to youtube.com ahead of a search.
     * Redirects are not followed: the one a search gets is youtube.com's captcha page (google.com/sorry), which redirects
     * to itself until OkHttp gives up with "Too many follow-up requests" seconds later.
     */
    private val searchHttp = http.newBuilder()
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 8; maxRequests = 32 })
        .followRedirects(false).followSslRedirects(false)
        .build()

    /** Until when each way of searching is being refused with a captcha; asked again only after that. */
    @Volatile private var pageThrottledUntil = 0L
    @Volatile private var apiThrottledUntil = 0L

    /** The most likely music videos for the song, best first (at most [limit]); empty when the search fails. */
    suspend fun find(title: String, artist: String, durationSec: Long = 0L, limit: Int = 6, album: String? = null,
        exhaustive: Boolean = false, excludedVideoIds: Set<String> = emptySet(),
        foreground: StateFlow<Boolean> = SearchTraffic.PLAYING, budgetMs: Long = FIND_BUDGET_MS): List<VideoCandidate> =
        findOutcome(title, artist, durationSec, limit, album, exhaustive, excludedVideoIds, foreground, budgetMs).candidates

    /**
     * [find], saying as well whether every query was answered. [foreground] is true for the playing song; a prefetch for a
     * queued song waits while one is searching. [budgetMs] bounds the whole search; once a likely video is in hand the
     * remaining queries are skipped sooner.
     */
    suspend fun findOutcome(title: String, artist: String, durationSec: Long = 0L, limit: Int = 6, album: String? = null,
        exhaustive: Boolean = false, excludedVideoIds: Set<String> = emptySet(),
        foreground: StateFlow<Boolean> = SearchTraffic.PLAYING, budgetMs: Long = FIND_BUDGET_MS): VideoSearchOutcome {
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
        fun elapsedMs() = (System.nanoTime() - startedAt) / 1_000_000L
        var unanswered = 0
        var asked = 0
        fun eligible() = VideoMatcher.rank(results.distinctBy { it.videoId }
            .filter { it.videoId !in excludedVideoIds && VideoMatcher.hasPublisherEvidence(it, title, artist, album) }, title, artist, durationSec, limit, album)
        val lease = traffic.lease(foreground)
        try {
            for (query in queries) {
                // Searches run one after another: a slow network must not hold the song's video for half a minute. Once a
                // publisher's upload that says it is the video is in hand, the rest are skipped sooner; an art track or a
                // still-picture audio upload alone does not end the search.
                val elapsed = elapsedMs()
                if (elapsed >= budgetMs - MIN_QUERY_MS) break
                if (elapsed > SOFT_BUDGET_MS && eligible().any { VideoMatcher.advertisesVideo(it.title) }) break
                val queryStarted = System.nanoTime()
                asked++
                val answer = lease.page { search(query, minOf(QUERY_TIMEOUT_MS, budgetMs - elapsed), hedged = foreground.value) }
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
                if (answer == null) {
                    unanswered++
                    log("video search '$query': no answer within $tookMs ms")
                } else if (batch.isEmpty() && !exhaustive) log("video search '$query': no results after $tookMs ms")
                results += batch
                val ranked = eligible()
                if (exhaustive) log("expanded video search '$query': ${batch.size} results in $tookMs ms, eligible " +
                    ranked.joinToString { it.videoId }.ifEmpty { "none" } + leftOut(batch, ranked, title, artist, durationSec, album, excludedVideoIds))
                if (exhaustive && ranked.isNotEmpty()) return VideoSearchOutcome(preferMovingPictures(ranked, title, artist, album), unanswered, asked)
                // Do not wait for speculative fallbacks when a publisher's matching song was found.
                // An album-only hit is insufficient: another track from the same film must not stop search.
                if (!exhaustive && ranked.any { candidate ->
                        val name = candidate.title.lowercase().filter { it.isLetterOrDigit() }
                        val titleMatches = name.contains(lookupTitle.lowercase().filter { it.isLetterOrDigit() }) ||
                            (titleWords.size >= 2 && titleWords.all { name.contains(it) })
                        val contextMatches = !ambiguousContext || VideoMatcher.isCreditedArtistChannel(candidate, artist)
                        titleMatches && contextMatches && VideoMatcher.advertisesVideo(candidate.title)
                    }) return VideoSearchOutcome(preferMovingPictures(ranked, title, artist, album), unanswered, asked)
            }
        } finally {
            lease.close()
        }
        val distinct = results.distinctBy { it.videoId }
        // Matching audio can be attached to unrelated footage. Missing publisher evidence is a miss,
        // never permission to promote an unverified fan edit into an automatic music video.
        val publisherVideos = VideoMatcher.rank(distinct.filter { it.videoId !in excludedVideoIds && VideoMatcher.hasPublisherEvidence(it, title, artist, album) }, title, artist, durationSec, limit, album)
        if (publisherVideos.isNotEmpty()) return VideoSearchOutcome(preferMovingPictures(publisherVideos, title, artist, album), unanswered, asked)
        // No publisher has the video: an upload by anyone else is tried, and the audio check has the last word.
        val uploads = VideoMatcher.rank(distinct.filter { it.videoId !in excludedVideoIds && VideoMatcher.hasFallbackEvidence(it, title, artist, album) }, title, artist, durationSec, limit, album)
        if (uploads.isNotEmpty()) log("no publisher video for '$title': trying ${uploads.joinToString { "${it.videoId} by ${it.channelTitle}" }}")
        if (uploads.isEmpty() && unanswered > 0) log("video search for '$title': nothing found, but $unanswered of $asked search(es) got no answer")
        return VideoSearchOutcome(preferMovingPictures(uploads, title, artist, album), unanswered, asked)
    }

    /** Why each of this query's results is not eligible: "; left out X "title…" (reason), …". */
    private fun leftOut(batch: List<VideoCandidate>, ranked: List<VideoCandidate>, title: String, artist: String, durationSec: Long,
        album: String?, excluded: Set<String>): String {
        val eligible = ranked.mapTo(HashSet()) { it.videoId }
        val reasons = batch.filter { it.videoId !in eligible }.map { candidate ->
            val why = if (candidate.videoId in excluded) "already tried"
                else VideoMatcher.rejection(candidate, title, artist, durationSec, album) ?: "only an upload by someone else"
            "${candidate.videoId} \"${candidate.title.take(40)}\" by ${candidate.channelTitle} ($why)"
        }
        if (reasons.isEmpty()) return ""
        return "; left out " + reasons.take(MAX_LOGGED_REJECTIONS).joinToString() +
            if (reasons.size > MAX_LOGGED_REJECTIONS) " and ${reasons.size - MAX_LOGGED_REJECTIONS} more" else ""
    }

    /**
     * Within one ranked list the order of measuring is: the label's or artist's uploads that say they are the video
     * ("Video Song", "Music Video"), then anyone's that say so, then the rest. An artist's art track ("Hangova (From
     * "DC")") ranks high on its channel alone but is a still picture: measured first, it used up the first batch.
     */
    private fun preferMovingPictures(ranked: List<VideoCandidate>, title: String, artist: String, album: String?): List<VideoCandidate> =
        ranked.sortedBy { candidate ->
            when {
                VideoMatcher.saysVideo(candidate.title) && VideoMatcher.hasPublisherEvidence(candidate, title, artist, album) -> 0
                VideoMatcher.advertisesVideo(candidate.title) -> 1
                else -> 2
            }
        }

    private enum class Endpoint(val label: String) { PAGE("page"), API("api") }

    private sealed interface Attempt {
        class Answered(val results: List<VideoCandidate>) : Attempt
        class Failed(val endpoint: Endpoint, val retryable: Boolean) : Attempt
    }

    /**
     * One search. A connection that stalls (a dead address, a congested socket, a queue behind other calls) is not
     * waited out: for the playing song the same question is asked through youtube.com's other search endpoint after a
     * short pause, while the first stays open, and the first valid answer wins. A try that times out or breaks off
     * (InterruptedIOException, ProtocolException) or meets the captcha page is asked once more the other way. Every
     * try that failed is named in the log, so a miss says why. Null when no try answered within [timeoutMs].
     */
    private suspend fun search(queryText: String, timeoutMs: Long, hedged: Boolean): List<VideoCandidate>? {
        val started = System.nanoTime()
        fun sinceMs() = (System.nanoTime() - started) / 1_000_000L
        val failures = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val now = System.currentTimeMillis()
        val first = if (now < pageThrottledUntil) Endpoint.API else Endpoint.PAGE
        val second = if (first == Endpoint.PAGE) Endpoint.API else Endpoint.PAGE
        if (now < pageThrottledUntil && now < apiThrottledUntil) {
            log("video search '$queryText' not sent: youtube.com is asking for a captcha for another ${(minOf(pageThrottledUntil, apiThrottledUntil) - now) / 1000} s")
            return null
        }
        var answered: List<VideoCandidate>? = null
        try {
            answered = withTimeoutOrNull(timeoutMs) {
                coroutineScope {
                    val attempts = Channel<Attempt>(Channel.UNLIMITED)
                    val running = ArrayList<Job>()
                    var launched = 0
                    val hedgeStarted = java.util.concurrent.atomic.AtomicBoolean(false)
                    fun ask(pause: Long, endpoint: Endpoint, isHedge: Boolean = false): Job {
                        val number = ++launched
                        return launch {
                            delay(pause)
                            if (isHedge) hedgeStarted.set(true)
                            attempts.send(attempt(number, endpoint, queryText, failures, ::sinceMs))
                        }.also { running += it }
                    }
                    ask(0L, first)
                    val hedge = if (hedged) ask(HEDGE_DELAY_MS, second, isHedge = true) else null
                    var pending = launched
                    var retried = false
                    var winner: List<VideoCandidate>? = null
                    while (winner == null && pending > 0) {
                        val attempt = attempts.receive()
                        pending--
                        when (attempt) {
                            is Attempt.Answered -> winner = attempt.results
                            is Attempt.Failed -> if (attempt.retryable && !retried && timeoutMs - sinceMs() >= MIN_QUERY_MS) {
                                retried = true
                                // The retry goes out now, the other way; a hedge still waiting to go is the same question.
                                if (hedge != null && !hedgeStarted.get()) {
                                    hedge.cancel()
                                    pending--
                                }
                                ask(0L, if (attempt.endpoint == Endpoint.PAGE) Endpoint.API else Endpoint.PAGE)
                                pending++
                            }
                        }
                    }
                    running.forEach { it.cancel() }
                    winner
                }
            }
        } finally {
            val took = sinceMs()
            if (answered == null) log("video search '$queryText' got no page: ${failures.joinToString("; ").ifEmpty { "no try had finished" }} (${took} ms)")
            else if (took > SLOW_ANSWER_MS || failures.isNotEmpty()) log("video search '$queryText' answered in $took ms${if (failures.isEmpty()) "" else " after " + failures.joinToString("; ")}")
        }
        return answered
    }

    private suspend fun attempt(number: Int, endpoint: Endpoint, queryText: String, failures: MutableCollection<String>, sinceMs: () -> Long): Attempt {
        val request = when (endpoint) {
            Endpoint.PAGE -> Request.Builder()
                .url("https://www.youtube.com/results?search_query=" + URLEncoder.encode(queryText, StandardCharsets.UTF_8.name()))
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            Endpoint.API -> Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/search?prettyPrint=false")
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("X-YouTube-Client-Name", "1")
                .header("X-YouTube-Client-Version", WEB_CLIENT_VERSION)
                .post(apiBody(queryText).toRequestBody("application/json".toMediaType()))
                .build()
        }
        return try {
            val answer = searchHttp.newCall(request).within(ATTEMPT_TIMEOUT_MS).fetch()
            val location = answer.headers["Location"].orEmpty()
            if (answer.code == 429 || answer.code in 300..399 && "/sorry" in location) {
                val until = System.currentTimeMillis() + THROTTLE_PAUSE_MS
                if (endpoint == Endpoint.PAGE) pageThrottledUntil = until else apiThrottledUntil = until
                failures += "try $number: ${endpoint.label} refused with a captcha (HTTP ${answer.code}) at ${sinceMs()} ms"
                return Attempt.Failed(endpoint, retryable = true)
            }
            // A page that carries its data and no videos is a real "nothing found"; anything else (a consent page, a
            // throttled reply) is worth another try.
            val parsed = if (answer.isSuccessful) parseAnswer(answer.text()) else null
            if (parsed != null) Attempt.Answered(parsed)
            else {
                failures += "try $number: ${endpoint.label} HTTP ${answer.code} without results at ${sinceMs()} ms"
                Attempt.Failed(endpoint, retryable = true)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            failures += "try $number: ${endpoint.label} ${failure.javaClass.simpleName} at ${sinceMs()} ms"
            Attempt.Failed(endpoint, retryable = failure is InterruptedIOException || failure is ProtocolException)
        }
    }

    internal companion object {
        /** One query: a try of [ATTEMPT_TIMEOUT_MS] plus the hedge or the retry. Typical answers take 6-7 s on a busy line. */
        private const val QUERY_TIMEOUT_MS = 12_000L
        private const val ATTEMPT_TIMEOUT_MS = 9_000L
        private const val HEDGE_DELAY_MS = 2_500L
        private const val SLOW_ANSWER_MS = 2_500L
        /** A query is not started, nor retried, with less than this left: it could not answer in time. */
        private const val MIN_QUERY_MS = 2_000L
        /** All of one search's queries. */
        const val FIND_BUDGET_MS = 15_000L
        /** After this, a search that already holds a likely video asks nothing more. */
        private const val SOFT_BUDGET_MS = 6_000L
        /** How long a captcha answer keeps that way of searching closed. */
        private const val THROTTLE_PAUSE_MS = 90_000L
        private const val MAX_LOGGED_REJECTIONS = 8
        private const val WEB_CLIENT_VERSION = "2.20251001.01.00"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

        private fun apiBody(query: String): String = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "WEB")
                    put("clientVersion", WEB_CLIENT_VERSION)
                    put("hl", "en")
                }
            }
            put("query", query)
        }.toString()

        /**
         * The results of a search answer, or null when it is not one: a results page (`var ytInitialData = `) or the
         * search endpoint's JSON, which carries the same `videoRenderer` records.
         */
        fun parseAnswer(body: String): List<VideoCandidate>? {
            if (body.contains("var ytInitialData = ")) return parseSearchResults(body)
            if (!body.trimStart().startsWith("{")) return null
            val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
            if ("contents" !in root && "estimatedResults" !in root) return null
            val found = ArrayList<VideoCandidate>()
            collect(root, found)
            return found.distinctBy { it.videoId }
        }

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
                    if (YoutubeAds.isAdRenderer(key)) return@forEach
                    if (key == "videoRenderer" && value is JsonObject) {
                        if (!YoutubeAds.isAdItem(value)) toCandidate(value)?.let(out::add)
                    } else {
                        collect(value, out)
                    }
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
