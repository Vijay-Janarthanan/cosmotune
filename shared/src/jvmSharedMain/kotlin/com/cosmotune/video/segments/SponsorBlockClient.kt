package com.cosmotune.video.segments

import com.music.bitchord.data.DebugLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import kotlin.coroutines.cancellation.CancellationException

/**
 * SponsorBlock (spec 2.3, 7.1): `GET {base}/api/skipSegments/{4 hex of the id's SHA-256}?categories=[...]&actionTypes=
 * ["skip","mute","poi","full"]`, the entry for the video picked out of the answer, seconds turned into milliseconds. Read
 * only: no user id, no vote, no skip count. A 404 means no segments; any other failure also gives no segments, with one
 * log line, and playback never waits on it.
 *
 * The answer is kept for one hour per video and category set (a 404 included, a failure not). What [segments] returns has
 * been through [SegmentMath.prepare] with the segments of one category merged (the settings are not known here); with a
 * video duration the overload drops the segments of another cut of the video.
 *
 * @param baseUrl the service; the privacy policy suggests `https://api.sponsor.ajay.app` as another address for it.
 * @param http only its connection pool is used: the timeouts are 10 s and there is no cookie jar ([WebServicesHttp.tuned]).
 * @param userAgent `Cosmotune/<version>`; the graph passes the app's version ([WebServicesHttp.userAgent]).
 */
class SponsorBlockClient(
    baseUrl: String = DEFAULT_BASE_URL,
    http: OkHttpClient = WebServicesHttp.client,
    private val clock: () -> Long = System::currentTimeMillis,
    private val userAgent: String = WebServicesHttp.userAgent(),
) : SegmentProvider {
    private val base = baseUrl.trimEnd('/').toHttpUrl()
    private val http = WebServicesHttp.tuned(http)
    private val cache = TtlCache<String, List<SkipSegment>>(CACHE_TTL_MS, CACHE_ENTRIES, clock)

    override suspend fun segments(videoId: String, categories: Set<SegmentCategory>): List<SkipSegment> =
        segments(videoId, categories, null)

    /** [segments], then dropping what was submitted on a video whose duration is not [videoDurationMs] within 2 s (null: unknown). */
    suspend fun segments(videoId: String, categories: Set<SegmentCategory>, videoDurationMs: Long?): List<SkipSegment> {
        if (categories.isEmpty() || !WebServicesHttp.isVideoId(videoId)) return emptyList()
        val key = "$videoId:${categories.fold(0) { mask, c -> mask or (1 shl c.ordinal) }}"
        val raw = cache.get(key) ?: fetch(videoId, categories)?.also { cache.put(key, it) } ?: return emptyList()
        return SegmentMath.prepare(raw, videoDurationMs, SegmentMath::sameCategory)
    }

    /** The parsed segments, an empty list for a 404, or null when the request failed. */
    private suspend fun fetch(videoId: String, categories: Set<SegmentCategory>): List<SkipSegment>? {
        val wanted = SegmentCategory.entries.filter { it in categories }
        val url = base.newBuilder()
            .addPathSegments("api/skipSegments")
            .addPathSegment(sha256Prefix(videoId))
            .addQueryParameter("categories", wanted.joinToString(",", "[", "]") { "\"${it.apiName}\"" })
            .addQueryParameter("actionTypes", ACTION_TYPES)
            .build()
        val answer = try {
            WebServicesHttp.get(http, url, userAgent)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.w(TAG, "request failed: ${e.javaClass.simpleName}")
            return null
        }
        if (answer.code == 404) return emptyList()
        if (!answer.isSuccessful) {
            DebugLog.w(TAG, "answered ${answer.code}")
            return null
        }
        return try {
            parse(answer.body, videoId, categories)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.w(TAG, "unreadable answer: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun parse(body: String, videoId: String, categories: Set<SegmentCategory>): List<SkipSegment> {
        val entries = Json.parseToJsonElement(body) as? JsonArray ?: error("not a list")
        val entry = entries.firstOrNull { (it as? JsonObject)?.get("videoID").asString() == videoId } as? JsonObject ?: return emptyList()
        val segments = entry["segments"] as? JsonArray ?: return emptyList()
        return segments.mapNotNull { (it as? JsonObject)?.let(::toSegment) }.filter { it.category in categories }
    }

    private fun toSegment(json: JsonObject): SkipSegment? {
        val uuid = json["UUID"].asString()?.takeIf { it.isNotBlank() } ?: return null
        val category = SegmentCategory.of(json["category"].asString() ?: return null) ?: return null
        val action = actionOf(json["actionType"].asString()) ?: return null
        val times = json["segment"] as? JsonArray ?: return null
        if (times.size != 2) return null
        val start = times[0].asMillis() ?: return null
        val end = times[1].asMillis() ?: return null
        if (start < 0 || end < start) return null
        return SkipSegment(
            uuid = uuid,
            category = category,
            action = action,
            startMs = start,
            endMs = end,
            videoDurationMs = json["videoDuration"].asMillis()?.takeIf { it > 0 },
            votes = json["votes"].asDouble()?.toInt() ?: 0,
            locked = json["locked"].asBoolean() ?: false,
            description = json["description"].asString()?.takeIf { it.isNotBlank() },
        )
    }

    /** The action of an `actionType` name; a missing one is a skip (older answers have none). */
    private fun actionOf(name: String?): SegmentAction? = when (name) {
        null, "skip" -> SegmentAction.SKIP
        "mute" -> SegmentAction.MUTE
        "poi" -> SegmentAction.POI
        "full" -> SegmentAction.FULL
        "chapter" -> SegmentAction.CHAPTER
        else -> null
    }

    companion object {
        /** The service (spec 2.3). */
        const val DEFAULT_BASE_URL = "https://sponsor.ajay.app"

        /** How long an answer is kept (spec 7.1: 1 hour). */
        const val CACHE_TTL_MS = 60L * 60 * 1000

        private const val CACHE_ENTRIES = 64
        private const val ACTION_TYPES = """["skip","mute","poi","full"]"""
        private const val TAG = "SponsorBlock"
    }
}
