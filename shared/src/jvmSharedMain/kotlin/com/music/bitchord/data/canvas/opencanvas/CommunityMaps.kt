package com.opencanvas.core.stream

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Duration

/** A song-to-video map published for a song, together with the music video it was measured against. */
internal class CommunityMap(val videoId: String, val offset: SyncOffset)

/**
 * Reads precomputed song-to-video maps from a static service (`<baseUrl>/<trackVideoId>.json`, the
 * same JSON [SyncStore] writes plus a `videoId`). A hit makes a song start without any measuring;
 * every failure is simply a miss.
 */
internal class CommunityMaps(http: OkHttpClient, private val baseUrl: String) {

    private val http = http.newBuilder()
        .callTimeout(Duration.ofMillis(CALL_TIMEOUT_MS))
        .build()

    suspend fun fetch(trackVideoId: String): CommunityMap? {
        if (trackVideoId.isEmpty() || trackVideoId.length > 64 || !trackVideoId.all { it.isLetterOrDigit() || it == '-' || it == '_' }) return null
        return tryOrNull {
            val answer = http.newCall(Request.Builder().url("${baseUrl.trimEnd('/')}/$trackVideoId.json").build()).fetch()
            if (!answer.isSuccessful) return@tryOrNull null
            val body = answer.text()
            val videoId = ((Json.parseToJsonElement(body) as? JsonObject)?.get("videoId") as? JsonPrimitive)?.content
            val offset = YouTubeStreamResolver.parseSyncResponse(body)
            if (videoId != null && offset != null) CommunityMap(videoId, offset) else null
        }
    }

    private companion object {
        const val CALL_TIMEOUT_MS = 2_500L
    }
}
