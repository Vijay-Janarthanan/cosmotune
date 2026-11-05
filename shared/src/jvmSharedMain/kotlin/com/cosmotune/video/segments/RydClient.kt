package com.cosmotune.video.segments

import com.cosmotune.music.data.DebugLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import kotlin.coroutines.cancellation.CancellationException

/**
 * Return YouTube Dislike (spec 2.3, 7.3): `GET {base}/votes?videoId={id}` answers the like, dislike and view counts.
 * Third-party use is allowed with attribution (the link in Video settings) and at most 100 requests a minute and 10,000 a
 * day per client, so [limiter] refuses beyond that without asking the server; a 429 backs the client off for 60 s (no request
 * at all, for any video, and null) and an answer is kept for 3 minutes (the service's `max-age=180`; a 404 included, a
 * failure not). A cached answer is still served while backing off. Null means "no counts": the chips stay hidden.
 *
 * @param limiter the client-side rule; the default is the service's.
 */
class RydClient(
    baseUrl: String = DEFAULT_BASE_URL,
    http: OkHttpClient = WebServicesHttp.client,
    private val clock: () -> Long = System::currentTimeMillis,
    private val userAgent: String = WebServicesHttp.userAgent(),
    private val limiter: RateLimiter = RateLimiter(clock = clock),
) : VotesProvider {
    private val base = baseUrl.trimEnd('/').toHttpUrl()
    private val http = WebServicesHttp.tuned(http)
    private val cache = TtlCache<String, Votes>(CACHE_TTL_MS, CACHE_ENTRIES, clock)

    @Volatile
    private var backoffUntilMs = Long.MIN_VALUE

    override suspend fun votes(videoId: String): Votes? {
        if (!WebServicesHttp.isVideoId(videoId)) return null
        cache.get(videoId)?.let { return it.takeIf { found -> found != NONE } }
        if (clock() < backoffUntilMs) return null
        if (!limiter.tryAcquire()) return null

        val url = base.newBuilder().addPathSegment("votes").addQueryParameter("videoId", videoId).build()
        val answer = try {
            WebServicesHttp.get(http, url, userAgent)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.w(TAG, "request failed: ${e.javaClass.simpleName}")
            return null
        }
        when {
            answer.code == 404 -> {
                cache.put(videoId, NONE)
                return null
            }
            answer.code == 429 -> {
                backoffUntilMs = clock() + BACKOFF_MS
                DebugLog.w(TAG, "rate limited, backing off for ${BACKOFF_MS / 1000} s")
                return null
            }
            !answer.isSuccessful -> {
                DebugLog.w(TAG, "answered ${answer.code}")
                return null
            }
        }
        val votes = try {
            parse(answer.body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.w(TAG, "unreadable answer: ${e.javaClass.simpleName}")
            return null
        }
        cache.put(videoId, votes ?: NONE)
        return votes
    }

    /** The counts, or null when the video was deleted or the answer has none. */
    private fun parse(body: String): Votes? {
        val json = Json.parseToJsonElement(body) as? JsonObject ?: error("not an object")
        if (json["deleted"].asBoolean() == true) return null
        val votes = Votes(json["likes"].asLong(), json["dislikes"].asLong(), json["viewCount"].asLong())
        return votes.takeIf { it.likes != null || it.dislikes != null || it.viewCount != null }
    }

    companion object {
        /** The service (spec 2.3). */
        const val DEFAULT_BASE_URL = "https://returnyoutubedislikeapi.com"

        /** How long an answer is kept (spec 7.3: 3 minutes). */
        const val CACHE_TTL_MS = 3L * 60 * 1000

        /** How long the client stays silent after a 429 (plan task Y-6: 60 s). */
        const val BACKOFF_MS = 60_000L

        private const val CACHE_ENTRIES = 256
        private const val TAG = "ReturnDislike"

        /** Stands in the cache for "the service has no counts for this video". */
        private val NONE = Votes(null, null, null)
    }
}
