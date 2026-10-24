package com.cosmotune.listening

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Finished ListenBrainz listens for both hosts, with the shared offline queue: a listen that fails
 * for a network reason, a server error, rate limiting or a token the listener has not fixed yet is
 * kept (metadata only, never the token) and sent before the next one, in listening order. A listen
 * the service refuses as invalid (HTTP 400) is dropped.
 */
class ListenBrainzSubmitter(private val http: OkHttpClient, private val endpoint: String = DEFAULT_ENDPOINT) {

    fun body(item: PendingScrobble): String = buildJsonObject {
        put("listen_type", "single")
        putJsonArray("payload") {
            addJsonObject {
                put("listened_at", item.timestampSeconds)
                putJsonObject("track_metadata") {
                    put("artist_name", item.artist)
                    put("track_name", item.track)
                    item.album?.takeIf { it.isNotBlank() }?.let { put("release_name", it) }
                    putJsonObject("additional_info") {
                        put("submission_client", "Cosmotune")
                        item.durationSeconds?.takeIf { it > 0 }?.let { put("duration_ms", it * 1000L) }
                    }
                }
            }
        }
    }.toString()

    suspend fun submit(token: String, item: PendingScrobble): ScrobbleOutcome = withContext(Dispatchers.IO) {
        if (token.isBlank()) return@withContext ScrobbleOutcome.RETRY_LATER
        val request = Request.Builder().url(endpoint)
            .post(body(item).toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Token $token")
            .build()
        try {
            http.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> ScrobbleOutcome.SENT
                    // A token being fixed, rate limiting and server trouble are worth another try.
                    response.code == 401 || response.code == 429 || response.code >= 500 -> ScrobbleOutcome.RETRY_LATER
                    else -> ScrobbleOutcome.REJECTED
                }
            }
        } catch (error: java.io.IOException) {
            ScrobbleOutcome.RETRY_LATER
        }
    }

    /** Sends earlier queued listens first, then [listen]; keeps [listen] when it could not be sent yet. */
    suspend fun submitWithQueue(queue: ScrobbleQueue, token: String, listen: PendingScrobble): ScrobbleOutcome {
        require(listen.service == SERVICE)
        queue.flush(service = SERVICE) { submit(token, it) }
        // Something earlier is still waiting: this one queues behind it to keep the order.
        val outcome = if (queue.pending().any { it.service == SERVICE }) ScrobbleOutcome.RETRY_LATER else submit(token, listen)
        if (outcome == ScrobbleOutcome.RETRY_LATER) queue.add(listen)
        return outcome
    }

    /** Retries waiting listens, for example after the token is entered again. */
    suspend fun flush(queue: ScrobbleQueue, token: String): ScrobbleFlush = queue.flush(service = SERVICE) { submit(token, it) }

    companion object {
        const val SERVICE = "listenbrainz"
        const val DEFAULT_ENDPOINT = "https://api.listenbrainz.org/1/submit-listens"
    }
}
