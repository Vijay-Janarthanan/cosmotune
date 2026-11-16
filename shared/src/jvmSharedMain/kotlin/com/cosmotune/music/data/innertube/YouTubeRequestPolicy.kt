package com.cosmotune.music.data.innertube

import kotlinx.serialization.json.*
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

/** Bounded wire compatibility for both clients. Never writes back discovery/session preferences. */
internal object YouTubeRequestPolicy : Interceptor {
    private const val MAX_JSON_BYTES = 4L * 1024 * 1024
    private val readEndpoints = setOf("browse", "next", "search", "player", "music/get_search_suggestions")

    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        val endpoint = request.url.encodedPath.substringAfter("/youtubei/v1/", "")
        if (endpoint !in readEndpoints) return chain.proceed(request)
        val removed = mutableSetOf<String>()
        repeat(3) {
            val response = chain.proceed(request)
            val bytes = response.peekBody(MAX_JSON_BYTES + 1).bytes()
            if (bytes.size > MAX_JSON_BYTES) return response
            val root = runCatching { Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull()
                ?: return response
            val field = rejectedLocaleField(root).takeIf { response.code == 400 ||
                response.isSuccessful && (root["error"] as? JsonObject)?.get("code")?.jsonPrimitive?.intOrNull == 400 }
            if (field != null && removed.add(field) && removed.size <= 2) {
                val retry = withoutLocale(request, field)
                if (retry != null) { response.close(); request = retry; return@repeat }
            }
            if (endpoint != "player") return response
            val filtered = preferOriginalAudio(root)
            if (filtered === root) return response
            val replacement = filtered.toString().toResponseBody(response.body?.contentType())
            response.close()
            return response.newBuilder().removeHeader("Content-Length").body(replacement).build()
        }
        // At most original + language retry + region retry; the final response is always returned.
        error("YouTube locale recovery exceeded its request bound")
    }

    internal fun rejectedLocaleField(root: JsonObject): String? {
        val error = root["error"] as? JsonObject ?: return null
        val text = error.toString().lowercase()
        val rejected = listOf("unsupported", "not supported", "invalid", "not available", "unrecognized").any { it in text }
        if (!rejected) return null
        return when {
            "language" in text || "context.client.hl" in text -> "hl"
            "country" in text || "region" in text || "context.client.gl" in text -> "gl"
            else -> null
        }
    }

    private fun withoutLocale(request: Request, field: String): Request? {
        val body = request.body ?: return null
        if (body.isOneShot() || body.isDuplex() || body.contentLength() > MAX_JSON_BYTES) return null
        val buffer = Buffer(); body.writeTo(buffer)
        if (buffer.size > MAX_JSON_BYTES) return null
        val root = runCatching { Json.parseToJsonElement(buffer.readUtf8()) as? JsonObject }.getOrNull() ?: return null
        val context = root["context"] as? JsonObject ?: return null
        val client = context["client"] as? JsonObject ?: return null
        if (field !in client && request.url.queryParameter(field) == null) return null
        val payload = JsonObject(root + ("context" to JsonObject(context + ("client" to JsonObject(client - field)))))
        return request.newBuilder().url(request.url.newBuilder().removeAllQueryParameters(field).build())
            .apply { if (field == "hl") removeHeader("Accept-Language") }
            .method(request.method, payload.toString().toRequestBody(body.contentType())).build()
    }

    internal fun isDrcUrl(url: String?): Boolean {
        val address = url?.let { runCatching { java.net.URI(it) }.getOrNull() } ?: return false
        val values = address.rawQuery.orEmpty().split('&').mapNotNull { item ->
            if (item.substringBefore('=') != "xtags") null
            else runCatching { java.net.URLDecoder.decode(item.substringAfter('=', ""), "UTF-8") }.getOrNull()
        }
        return values.any { tags -> tags.split(':', ',', ';', '&').any { it.trim() == "drc=1" } }
    }

    /** Do not let a quiet DRC copy outrank the original of the same audio track. */
    internal fun preferOriginalAudio(root: JsonObject): JsonObject {
        val streaming = root["streamingData"] as? JsonObject ?: return root
        val formats = streaming["adaptiveFormats"] as? JsonArray ?: return root
        fun audio(row: JsonObject) = (row["mimeType"] as? JsonPrimitive)?.contentOrNull?.startsWith("audio/") == true
        fun track(row: JsonObject) = (row["audioTrack"] as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull.orEmpty()
        fun drc(row: JsonObject) = (row["isDrc"] as? JsonPrimitive)?.booleanOrNull == true ||
            isDrcUrl((row["url"] as? JsonPrimitive)?.contentOrNull)
        val originals = formats.mapNotNull { it as? JsonObject }.filter { audio(it) && !drc(it) }.map(::track).toSet()
        val kept = formats.filter { element ->
            val row = element as? JsonObject
            row == null || !audio(row) || !drc(row) || track(row) !in originals
        }
        var result = if (kept.size == formats.size) root else
            JsonObject(root + ("streamingData" to JsonObject(streaming + ("adaptiveFormats" to JsonArray(kept)))))
        val audioRows = kept.mapNotNull { it as? JsonObject }.filter(::audio)
        if (audioRows.isNotEmpty() && audioRows.all(::drc)) {
            val config = result["playerConfig"] as? JsonObject
            val audioConfig = config?.get("audioConfig") as? JsonObject
            if (config != null && audioConfig != null) result = JsonObject(result +
                ("playerConfig" to JsonObject(config + ("audioConfig" to JsonObject(audioConfig - setOf("loudnessDb", "perceptualLoudnessDb"))))))
        }
        return result
    }
}
