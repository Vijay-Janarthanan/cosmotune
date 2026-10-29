package com.cosmotune.video.account

import com.cosmotune.music.data.innertube.Innertube
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import java.io.IOException

/**
 * The calls [InnerTubeVideoAccount] makes: one signed youtubei POST to the YouTube site. The real one is [InnertubeWeb]; tests
 * hand in a fake that answers saved JSON.
 */
internal interface YoutubeWeb {
    /** Posts [body] to the youtubei [endpoint] ("browse", "like/like"...) and returns the answer. Failures are thrown as they come. */
    suspend fun post(endpoint: String, body: JsonObjectBuilder.() -> Unit): JsonObject
}

/** The shared Innertube client's WEB call, signed with the session Music holds. */
internal object InnertubeWeb : YoutubeWeb {
    override suspend fun post(endpoint: String, body: JsonObjectBuilder.() -> Unit): JsonObject = Innertube.postWeb(endpoint, body)
}

/**
 * Runs [block] and turns whatever it throws into an [AccountFailure] inside the [Result]; cancellation is rethrown so a cancelled
 * screen does not see a failure.
 */
internal inline fun <T> attempt(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(failureOf(e))
}

/** The typed failure for an exception from the network layer or the parser. */
internal fun failureOf(e: Throwable): AccountFailure = when (e) {
    is AccountFailure -> e
    is ResponseException -> {
        val status = e.response.status.value
        when {
            status == 401 -> AccountFailure.SessionExpired("HTTP 401")
            status == 403 -> AccountFailure.SessionExpired("HTTP 403")
            status == 429 -> AccountFailure.RateLimited(e.response.headers["Retry-After"]?.trim()?.toLongOrNull())
            // 400 is what a stale account scope is answered with; it is a request the site no longer accepts as built.
            status in 400..499 -> AccountFailure.Changed("HTTP $status", e)
            else -> AccountFailure.Network("HTTP $status", e)
        }
    }
    is HttpRequestTimeoutException -> AccountFailure.Network("Timed out", e)
    is IOException -> AccountFailure.Network(e.message ?: "I/O error", e)
    is SerializationException, is IllegalArgumentException, is IllegalStateException, is ClassCastException, is NullPointerException ->
        AccountFailure.Changed(e.message ?: e::class.java.simpleName, e)
    else -> AccountFailure.Network(e.message ?: e::class.java.simpleName, e)
}
