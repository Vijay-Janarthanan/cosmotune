package com.cosmotune.canvas

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assume.assumeTrue
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.test.Test

/**
 * Explicit opt-in (COSMOTUNE_RECORD_VIDEO_SEARCH=1): reads the public YouTube search pages the matcher asks for a few
 * reported songs and keeps only what the matcher reads of each result (id, title, channel, length, badges), as the
 * fixtures under `src/jvmTest/resources/opencanvas-search/` (search-06 was taken from the search endpoint, youtubei/v1/search,
 * whose JSON carries the same records). No account, cookie or user setting is involved.
 */
class VideoSearchFixtureRecorder {
    @Test fun recordTheReportedSongsSearchPages() {
        assumeTrue(System.getenv("COSMOTUNE_RECORD_VIDEO_SEARCH") == "1")
        // Redirects are not followed: a burst of searches is answered with youtube.com's captcha page, which redirects to
        // itself. That is reported and the recording stops, rather than asking again and again.
        val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).followRedirects(false).build()
        // Into the build folder: copy a recording into src/jvmTest/resources/opencanvas-search/ by hand when it is wanted.
        val out = File("build/opencanvas-search-recorded").apply { mkdirs() }
        val queries = System.getenv("COSMOTUNE_RECORD_QUERIES")?.split('|')?.map(String::trim)?.filter(String::isNotEmpty)
            ?: DEFAULT_QUERIES
        for ((number, query) in queries.withIndex()) {
            if (number > 0) Thread.sleep(PAUSE_MS)
            val url = "https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8")
            val (code, page) = http.newCall(Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
                .header("Accept-Language", "en-US,en;q=0.9").build()).execute().use { it.code to it.body?.string().orEmpty() }
            val start = page.indexOf("var ytInitialData = ")
            val end = page.indexOf(";</script>", start)
            if (code != 200 || start < 0 || end < 0) {
                println("RECORDER: '$query' answered HTTP $code without results; stopping")
                return
            }
            val root = Json.parseToJsonElement(page.substring(start + "var ytInitialData = ".length, end))
            val renderers = ArrayList<JsonObject>()
            collect(root, renderers)
            val trimmed = buildJsonObject {
                put("query", query)
                put("contents", buildJsonArray {
                    renderers.forEach { renderer ->
                        add(buildJsonObject {
                            put("videoRenderer", JsonObject(renderer.filterKeys { it in KEPT }))
                        })
                    }
                })
            }
            File(out, "search-%02d.json".format(number + 1)).writeText(trimmed.toString())
            println("RECORDER: '$query' -> ${renderers.size} results")
        }
    }

    private fun collect(element: JsonElement, out: MutableList<JsonObject>) {
        when (element) {
            is JsonObject -> element.forEach { (key, value) ->
                if (key == "videoRenderer" && value is JsonObject) out += value else collect(value, out)
            }
            is JsonArray -> element.forEach { collect(it, out) }
            else -> Unit
        }
    }

    private companion object {
        const val PAUSE_MS = 5_000L
        val KEPT = setOf("videoId", "title", "ownerText", "lengthText", "ownerBadges", "navigationEndpoint")
        val DEFAULT_QUERIES = listOf(
            "Hangova DC video song",
            "\"Hangova\" video song",
            "Anirudh Ravichander, Heisenberg Hangova official music video",
            "\"Hangova\" Anirudh Ravichander, Heisenberg official video",
            "Hangova DC video song -\"5.1\" -audio -lyrics -lyrical",
            "DC movie songs Hangova",
            "Ain't Nobody DC video song",
            "\"Nobody Ain't\" DC",
            "DC movie songs Ain't Nobody",
            "Ain't Nobody DC video song -\"5.1\" -audio -lyrics -lyrical",
        )
    }
}
