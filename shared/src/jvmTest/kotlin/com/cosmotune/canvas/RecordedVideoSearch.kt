package com.cosmotune.canvas

import com.opencanvas.core.matcher.VideoCandidate
import com.opencanvas.core.stream.MusicVideoSearch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

/**
 * The public YouTube search answers recorded for the reported songs (see [VideoSearchFixtureRecorder]), replayed with no
 * network: each fixture is the search endpoint's JSON for one query, trimmed to what the matcher reads.
 */
internal object RecordedVideoSearch {
    /** Query text to its recorded answer. */
    val answers: Map<String, String> by lazy {
        (1..6).associate { number ->
            val body = RecordedVideoSearch::class.java.getResource("/opencanvas-search/search-%02d.json".format(number))!!.readText()
            Json.parseToJsonElement(body).jsonObject["query"]!!.jsonPrimitive.content to body
        }
    }

    const val EMPTY = """{"contents":[]}"""

    /** Every result of the recorded query [query], as the matcher reads it. */
    fun results(query: String): List<VideoCandidate> = MusicVideoSearch.parseAnswer(answers.getValue(query))!!

    fun candidate(query: String, videoId: String): VideoCandidate = results(query).first { it.videoId == videoId }

    /** The query a search request asks, whether through the results page or the search endpoint. */
    fun queryOf(request: Request): String = request.url.queryParameter("search_query")
        ?: request.body?.let { body -> Buffer().also(body::writeTo).readUtf8() }
            ?.let { Json.parseToJsonElement(it).jsonObject["query"]?.jsonPrimitive?.content }.orEmpty()

    /** A client whose every search is answered by [answer] (a recorded body, or [EMPTY]). */
    fun client(answer: (query: String) -> String): OkHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        respond(chain.request(), answer(queryOf(chain.request())))
    }).build()

    /** The recorded answer for each query asked, nothing for any other. */
    fun replaying(): OkHttpClient = client { answers[it] ?: EMPTY }

    fun respond(request: Request, body: String): Response = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
        .body(body.toResponseBody()).build()
}
