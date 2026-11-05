package com.opencanvas.core.stream

import com.opencanvas.core.heatmap.HeatmapParser
import com.opencanvas.core.sync.CanvasSyncDefaults
import com.opencanvas.core.sync.SyncMap
import com.opencanvas.core.sync.SyncSegment
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Duration
import kotlin.math.roundToLong

/**
 * A measured offset between a song's audio and its music video.
 * Definition: `videoTimeMs = trackTimeMs + offsetMs`.
 *
 * @property offsetMs Offset in milliseconds; positive when the video has an intro before the song.
 * @property confidence Measurement confidence in `0.0..1.0`.
 */
data class SyncOffset(
    val offsetMs: Long,
    val confidence: Double,
    /**
     * The measured map from song time to video time. A music video is usually an edit of the song,
     * so several segments with different offsets is normal; empty means one constant [offsetMs] for the
     * whole song (see [toMap]).
     */
    val segments: List<SyncSegment> = emptyList(),
    /** Share of the song with a trusted match in the video, `0.0..1.0`. */
    val coverage: Double = 1.0,
) {
    /** The song-to-video map: the measured [segments], or one constant [offsetMs] without them. */
    fun toMap(): SyncMap = if (segments.isEmpty()) SyncMap.constant(offsetMs) else SyncMap(segments)
}

/**
 * Client-side YouTube metadata: the most replayed stretch of a video and the parser for stored sync
 * maps. Queries YouTube directly with zero backend.
 */
object YouTubeStreamResolver {

    /** Minimum confidence for a measured sync map to be accepted; lower-confidence measurements are discarded. */
    const val MIN_SYNC_CONFIDENCE = CanvasSyncDefaults.MIN_SYNC_CONFIDENCE

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(10))
        .readTimeout(Duration.ofSeconds(15))
        .build()

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    /**
     * Fetches the raw InnerTube player response containing playback markers and stream formats.
     */
    fun fetchPlayerData(videoId: String): String? {
        val url = "https://www.youtube.com/youtubei/v1/player"
        val payload = """
            {
                "context": {
                    "client": {
                        "clientName": "WEB",
                        "clientVersion": "2.20240313.05.00",
                        "hl": "en",
                        "gl": "US"
                    }
                },
                "videoId": "$videoId"
            }
        """.trimIndent()

        val request = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .header("User-Agent", USER_AGENT)
            .build()

        return runCatching {
            client.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        }.getOrNull()
    }

    /**
     * Resolves the best 8-12s loop window for a video using its engagement heatmap.
     */
    fun resolveLoopWindow(
        videoId: String,
        videoDurationMs: Long,
        preferredLoopMs: Long = 10000L,
    ): Pair<Long, Long> {
        val playerJson = fetchPlayerData(videoId)
        return if (playerJson != null) {
            HeatmapParser.findPeakLoopWindow(playerJson, videoDurationMs, preferredLoopMs)
        } else {
            // Fallback: 40% into video
            val start = (videoDurationMs * 0.40).toLong()
            start to (start + preferredLoopMs).coerceAtMost(videoDurationMs)
        }
    }

    /**
     * Parses a stored or published sync map, for example
     * `{"trackId":"..","videoId":"..","offsetMs":22500,"confidence":0.93,"segments":[..]}`.
     * Unknown fields are ignored; `offsetMs` and `confidence` may be numbers or numeric strings.
     *
     * @return The parsed offset, or null when the body is not a JSON object, a field is missing or
     *   not finite, or the confidence is below [minConfidence]. Confidence above 1 is clamped to 1.
     */
    internal fun parseSyncResponse(body: String, minConfidence: Double = MIN_SYNC_CONFIDENCE): SyncOffset? {
        val obj = parseJsonObject(body) ?: return null
        val offset = obj.finiteNumber("offsetMs") ?: return null
        val confidence = obj.finiteNumber("confidence")?.coerceIn(0.0, 1.0) ?: return null
        if (confidence < minConfidence) return null
        val segments = (obj["segments"] as? JsonArray)?.mapNotNull { element ->
            val segment = element as? JsonObject ?: return@mapNotNull null
            val start = segment.finiteNumber("songStartMs") ?: return@mapNotNull null
            val end = segment.finiteNumber("songEndMs") ?: return@mapNotNull null
            val segmentOffset = segment.finiteNumber("offsetMs") ?: return@mapNotNull null
            val rate = segment.finiteNumber("rate")?.takeIf { it > 0.5 && it < 2.0 } ?: 1.0
            if (end <= start) null else SyncSegment(start.roundToLong(), end.roundToLong(), segmentOffset.roundToLong(), rate = rate)
        }.orEmpty()
        return SyncOffset(
            offsetMs = offset.roundToLong(),
            confidence = confidence,
            segments = segments,
            coverage = obj.finiteNumber("coverage")?.coerceIn(0.0, 1.0) ?: 1.0,
        )
    }

    private fun parseJsonObject(body: String): JsonObject? =
        try {
            Json.parseToJsonElement(body) as? JsonObject
        } catch (_: Exception) {
            null
        }

    /** Reads [key] as a finite number, accepting JSON numbers and numeric strings; else null. */
    private fun JsonObject.finiteNumber(key: String): Double? =
        (this[key] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }
}
