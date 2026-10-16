package com.cosmotune.video.source.newpipe.fixtures

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Answers NewPipe's requests from recorded files, never from the network (plan task Y-4, step 6).
 *
 * A scenario is a numbered series of files `video/newpipe/<scenario>_01.json`, `_02.json`, ... on the test classpath, each
 * `{ "method", "url", "status", "headers", "body" }` (the body is the answer's text). A request is matched by its method,
 * host and path; when several recorded answers share them (a search and its continuation pages are all `POST
 * youtubei/v1/search`) they are given in the order they were recorded, and the last one repeats. A request no file matches
 * fails the test: it is recorded in [unexpected] and answered with an `AssertionError`, which the extractor does not catch.
 */
class ReplayDownloader(val scenario: String) : Downloader() {

    class Recorded(val method: String, val url: String, val status: Int, val headers: Map<String, List<String>>, val body: String)

    private data class Key(val method: String, val host: String, val path: String)

    private val answers: Map<Key, List<Recorded>> = load(scenario).groupBy { Key(it.method.uppercase(), hostOf(it.url), pathOf(it.url)) }
    private val served = ConcurrentHashMap<Key, Int>()

    /** Every request served, as `METHOD host/path`, in order. */
    val seen = CopyOnWriteArrayList<String>()

    /** Every request received (answered or not), whole, in order: a test can look at what a call really sent. */
    val requests = CopyOnWriteArrayList<Request>()

    /** Every request no recorded answer matched. */
    val unexpected = CopyOnWriteArrayList<String>()

    /** The number of recorded answers for [method] and the host and path of [url]. */
    fun recordedCount(method: String, url: String): Int = answers[Key(method.uppercase(), hostOf(url), pathOf(url))]?.size ?: 0

    override fun execute(request: Request): Response {
        val key = Key(request.httpMethod().uppercase(), hostOf(request.url()), pathOf(request.url()))
        seen += "${key.method} ${key.host}${key.path}"
        requests += request
        val list = answers[key]
        if (list == null) {
            val message = "ReplayDownloader($scenario): no recorded answer for ${key.method} ${key.host}${key.path}; recorded: ${answers.keys.map { "${it.method} ${it.host}${it.path}" }}"
            unexpected += message
            throw AssertionError(message)
        }
        val index = served.merge(key, 1, Int::plus)!! - 1
        val recorded = list[minOf(index, list.lastIndex)]
        return Response(recorded.status, "", recorded.headers, recorded.body, recorded.url)
    }

    companion object {
        /** Where the recordings live on the test classpath. */
        const val RESOURCE_DIRECTORY = "video/newpipe"

        private fun hostOf(url: String) = url.toHttpUrlOrNull()?.host ?: url
        private fun pathOf(url: String) = url.toHttpUrlOrNull()?.encodedPath ?: ""

        /** The recorded answers of [scenario] in file order; an empty scenario is a test failure. */
        fun load(scenario: String): List<Recorded> {
            val out = mutableListOf<Recorded>()
            var n = 1
            while (true) {
                val name = "/$RESOURCE_DIRECTORY/${scenario}_${n.toString().padStart(2, '0')}.json"
                val stream = ReplayDownloader::class.java.getResourceAsStream(name) ?: break
                val json = Json.parseToJsonElement(stream.use { it.readBytes().toString(Charsets.UTF_8) }).jsonObject
                out += parse(json)
                n++
            }
            check(out.isNotEmpty()) { "No recorded answers for scenario $scenario under $RESOURCE_DIRECTORY (run the Recorder)" }
            return out
        }

        private fun parse(json: JsonObject): Recorded {
            val headers: Map<String, List<String>> = json["headers"]?.jsonObject?.mapValues { (_, value) ->
                (value as? JsonArray ?: JsonArray(listOf(value))).map { it.jsonPrimitive.content }
            }.orEmpty()
            return Recorded(
                method = json.getValue("method").jsonPrimitive.content,
                url = json.getValue("url").jsonPrimitive.content,
                status = json.getValue("status").jsonPrimitive.int,
                headers = headers,
                body = json.getValue("body").jsonPrimitive.content,
            )
        }
    }
}
