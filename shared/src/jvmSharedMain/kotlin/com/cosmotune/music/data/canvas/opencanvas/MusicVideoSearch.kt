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

/**
 * Finds the music video of a song by searching YouTube, with no API key and no server.
 *
 * Each result is read as a structured record (channel, length, verified-artist badge) so clips,
 * Shorts, lyric videos, live takes and covers can be ranked out before anything is downloaded; the
 * caller is expected to confirm the top few against the song's audio (see `CanvasSession`).
 */
internal class MusicVideoSearch(private val http: OkHttpClient) {

    /** The most likely music videos for the song, best first (at most [limit]); empty when the search fails. */
    suspend fun find(title: String, artist: String, durationSec: Long = 0L, limit: Int = 6): List<VideoCandidate> {
        val query = URLEncoder.encode("$artist $title official music video", StandardCharsets.UTF_8.name())
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
        return VideoMatcher.rank(results, title, artist, durationSec, limit)
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
            return VideoCandidate(
                videoId = videoId,
                title = runs((renderer["title"] as? JsonObject)?.get("runs")),
                channelTitle = runs((renderer["ownerText"] as? JsonObject)?.get("runs")),
                durationSec = text(renderer.path("lengthText", "simpleText"))?.let(::parseClock) ?: 0L,
                isOfficialMusicVideo = verifiedArtist,
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
