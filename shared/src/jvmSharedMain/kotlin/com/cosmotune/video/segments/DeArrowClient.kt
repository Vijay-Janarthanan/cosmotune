package com.cosmotune.video.segments

import com.cosmotune.music.data.DebugLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs

/**
 * DeArrow (spec 2.3, 7.2): `GET {base}/api/branding/{4 hex of the id's SHA-256}` answers an object keyed by video id; the
 * entry for the video gives its first title and its first thumbnail, each used only if `locked` or `votes >= 0`. An
 * `original` title or thumbnail means DeArrow found the original better: no replacement. A thumbnail with a timestamp
 * becomes `{thumbBase}/api/v1/getThumbnail?videoID={id}&time={seconds}` (the thumbnail service needs the raw id; that
 * request is made by the image loader, not here, and may answer 204, when the original thumbnail stays).
 *
 * The title is returned as DeArrow has it, `>` markers included: [DeArrowTitles.clean] applies the title-case setting. A
 * thumbnail time is not used when the video's duration (the [branding] argument) and the one DeArrow recorded differ by more
 * than 2 s (another cut of the video; the title is still used). The answer is kept for 24 hours (a 404 or a missing entry
 * included, a failure not). Failures give null and one log line.
 *
 * @param baseUrl the service that answers titles (the same host as SponsorBlock).
 * @param thumbBase the thumbnail service.
 */
class DeArrowClient(
    baseUrl: String = SponsorBlockClient.DEFAULT_BASE_URL,
    private val thumbBase: String = DEFAULT_THUMB_BASE,
    http: OkHttpClient = WebServicesHttp.client,
    private val clock: () -> Long = System::currentTimeMillis,
    private val userAgent: String = WebServicesHttp.userAgent(),
) : BrandingProvider {
    private val base = baseUrl.trimEnd('/').toHttpUrl()
    private val http = WebServicesHttp.tuned(http)
    private val cache = TtlCache<String, Entry>(CACHE_TTL_MS, CACHE_ENTRIES, clock)

    /** What DeArrow knows of one video, before the caller's duration is looked at. */
    private class Entry(val title: String?, val thumbnailTimeMs: Long?, val videoDurationMs: Long?)

    override suspend fun branding(videoId: String, durationMs: Long?): Branding? {
        if (!WebServicesHttp.isVideoId(videoId)) return null
        val entry = cache.get(videoId) ?: fetch(videoId)?.also { cache.put(videoId, it) } ?: return null
        val thumbnailMs = entry.thumbnailTimeMs?.takeIf { sameCut(entry.videoDurationMs, durationMs) }
        if (entry.title == null && thumbnailMs == null) return null
        return Branding(entry.title, thumbnailMs, thumbnailMs?.let { thumbnailUrl(videoId, it) })
    }

    private suspend fun fetch(videoId: String): Entry? {
        val url = base.newBuilder().addPathSegments("api/branding").addPathSegment(sha256Prefix(videoId)).build()
        val answer = try {
            WebServicesHttp.get(http, url, userAgent)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.w(TAG, "request failed: ${e.javaClass.simpleName}")
            return null
        }
        if (answer.code == 404) return NOTHING
        if (!answer.isSuccessful) {
            DebugLog.w(TAG, "answered ${answer.code}")
            return null
        }
        return try {
            parse(answer.body, videoId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.w(TAG, "unreadable answer: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun parse(body: String, videoId: String): Entry {
        val root = Json.parseToJsonElement(body) as? JsonObject ?: error("not an object")
        val video = root[videoId] as? JsonObject ?: return NOTHING

        val first = (video["titles"] as? JsonArray)?.firstOrNull() as? JsonObject
        val title = first?.takeIf { trusted(it) && it["original"].asBoolean() != true }
            ?.get("title").asString()?.takeIf { it.isNotBlank() }

        val firstThumbnail = (video["thumbnails"] as? JsonArray)?.firstOrNull() as? JsonObject
        val thumbnailMs = firstThumbnail?.takeIf { trusted(it) && it["original"].asBoolean() != true }
            ?.get("timestamp").asMillis()?.takeIf { it >= 0 }

        return Entry(title, thumbnailMs, video["videoDuration"].asMillis()?.takeIf { it > 0 })
    }

    /** DeArrow's rule: use the first entry only if a moderator locked it or its votes are not negative. */
    private fun trusted(entry: JsonObject): Boolean =
        entry["locked"].asBoolean() == true || (entry["votes"].asDouble() ?: 0.0) >= 0.0

    /** A thumbnail time belongs to this video when the durations are unknown or within 2 s. */
    private fun sameCut(recordedMs: Long?, videoMs: Long?): Boolean =
        recordedMs == null || recordedMs <= 0 || videoMs == null || videoMs <= 0 ||
            abs(recordedMs - videoMs) <= SegmentMath.DURATION_TOLERANCE_MS

    private fun thumbnailUrl(videoId: String, timeMs: Long): String {
        val whole = timeMs / 1000
        val fraction = (timeMs % 1000).toString().padStart(3, '0').trimEnd('0')
        val seconds = if (fraction.isEmpty()) "$whole" else "$whole.$fraction"
        return "${thumbBase.trimEnd('/')}/api/v1/getThumbnail?videoID=$videoId&time=$seconds"
    }

    companion object {
        /** The thumbnail service (spec 2.3). */
        const val DEFAULT_THUMB_BASE = "https://dearrow-thumb.ajay.app"

        /** How long an answer is kept (spec 7.2: 24 hours). */
        const val CACHE_TTL_MS = 24L * 60 * 60 * 1000

        private const val CACHE_ENTRIES = 512
        private const val TAG = "DeArrow"
        private val NOTHING = Entry(null, null, null)
    }
}
