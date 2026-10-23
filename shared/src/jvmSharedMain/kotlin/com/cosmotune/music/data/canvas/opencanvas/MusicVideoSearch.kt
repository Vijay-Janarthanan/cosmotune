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
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Finds the music video of a song by searching YouTube, with no API key and no server.
 *
 * Each result is read as a structured record (channel, length, verified-artist badge) so clips,
 * Shorts, lyric videos, live takes and covers can be ranked out before anything is downloaded; the
 * caller is expected to confirm the top few against the song's audio (see `CanvasSession`).
 */
internal class MusicVideoSearch(private val http: OkHttpClient) {

    /** The most likely music videos for the song, best first (at most [limit]); empty when the search fails. */
    suspend fun find(title: String, artist: String, durationSec: Long = 0L, limit: Int = 6, album: String? = null): List<VideoCandidate> = run {
        val words = title.trim().split(Regex("\\s+"))
        val titleKey = title.lowercase().filter { it.isLetterOrDigit() }
        val albumKey = album.orEmpty().lowercase().filter { it.isLetterOrDigit() }
        // A title track shares its name with every result from the same film.
        // Without useful album context, another verified artist's same-title
        // video must not suppress the title-only publisher fallback.
        val ambiguousContext = albumKey.isEmpty() || albumKey == titleKey
        val queries = listOfNotNull(album?.trim()?.takeIf { it.isNotEmpty() }?.let { "$title $it video song" },
            album?.takeIf { it.isNotBlank() && words.size == 2 }?.let { "\"${words.reversed().joinToString(" ")}\" $it" },
            "$artist $title official music video",
            "\"$title\" $artist official video",
            if (ambiguousContext) "\"$title\" video song" else null).distinct()
        val results = ArrayList<VideoCandidate>()
        val titleWords = words.map { word -> word.lowercase().filter { it.isLetterOrDigit() } }.filter { it.length >= 3 }
        for (query in queries) {
            val batch = withTimeoutOrNull(8_000) { search(query) }.orEmpty()
            results += batch
            val ranked = VideoMatcher.rank(results.distinctBy { it.videoId }
                .filter { VideoMatcher.hasPublisherEvidence(it, title, artist, album) }, title, artist, durationSec, limit, album)
            // Do not wait for speculative fallbacks when a publisher's matching song was found.
            // An album-only hit is insufficient: another track from the same film must not stop search.
            if (ranked.any { candidate ->
                    val name = candidate.title.lowercase().filter { it.isLetterOrDigit() }
                    val titleMatches = name.contains(title.lowercase().filter { it.isLetterOrDigit() }) ||
                        (titleWords.size >= 2 && titleWords.all { name.contains(it) })
                    val contextMatches = !ambiguousContext || VideoMatcher.isCreditedArtistChannel(candidate, artist)
                    titleMatches && contextMatches && (VideoMatcher.saysVideo(candidate.title) ||
                        Regex("(?i)(\\b[248]k\\b|\\bmv\\b|\\bm/v\\b)").containsMatchIn(candidate.title))
                }) return@run ranked
        }
        VideoMatcher.rank(results.distinctBy { it.videoId }
            .filter { VideoMatcher.hasPublisherEvidence(it, title, artist, album) }, title, artist, durationSec, limit, album)
    }

    private suspend fun search(queryText: String): List<VideoCandidate> {
        val query = URLEncoder.encode(queryText, StandardCharsets.UTF_8.name())
        val request = Request.Builder()
            .url("https://www.youtube.com/results?search_query=$query")
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        val results = tryOrNull {
            withRetry(listOf(4_000L, 6_000L, 10_000L)) { timeoutMs ->
                val answer = http.newCall(request).within(timeoutMs).fetch()
                // an answer without any video (a consent page, a throttled reply) is worth one more try
                (if (answer.isSuccessful) parseSearchResults(answer.text()).takeIf { it.isNotEmpty() } else null)
                    ?: throw java.io.IOException("no results")
            }
        } ?: return emptyList()
        return results
    }

    internal companion object {
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
