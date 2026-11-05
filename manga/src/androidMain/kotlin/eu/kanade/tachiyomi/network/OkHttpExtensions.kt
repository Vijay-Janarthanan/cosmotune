// Derived from Tsuki 89b8d9cf6bb53303f340505f46c2ad2c1c8ac5b7; GPL-3.0.
// See manga/third-party/TSUKI_SOURCE_API.json and LICENSE.tsuki.
package eu.kanade.tachiyomi.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import rx.Producer
import rx.Subscription
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlin.coroutines.resumeWithException

/**
 * OkHttp extension functions for Mihon compatibility.
 */

val jsonMime = "application/json; charset=utf-8".toMediaType()

fun Call.asObservable(): Observable<Response> {
    return Observable.unsafeCreate { subscriber ->
        val call = clone()

        val requestArbiter = object : Producer, Subscription {
            val boolean = AtomicBoolean(false)
            override fun request(n: Long) {
                if (n == 0L || !boolean.compareAndSet(false, true)) return

                try {
                    val response = call.execute()
                    if (!subscriber.isUnsubscribed) {
                        subscriber.onNext(response)
                        subscriber.onCompleted()
                    }
                } catch (e: Exception) {
                    if (!subscriber.isUnsubscribed) {
                        subscriber.onError(e)
                    }
                }
            }

            override fun unsubscribe() {
                call.cancel()
            }

            override fun isUnsubscribed(): Boolean {
                return call.isCanceled()
            }
        }

        subscriber.add(requestArbiter)
        subscriber.setProducer(requestArbiter)
    }
}

fun Call.asObservableSuccess(): Observable<Response> {
    return asObservable().doOnNext { response ->
        if (!response.isSuccessful) {
            response.close()
            throw HttpException(response.code)
        }
    }
}

suspend fun Call.await(): Response {
    val callStack = Exception().stackTrace.run { copyOfRange(1, size) }
    return suspendCancellableCoroutine { continuation ->
        val callback = object : Callback {
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) {
                    response.body.close()
                }
            }

            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isCancelled) return
                val exception = IOException(e.message, e).apply { stackTrace = callStack }
                continuation.resumeWithException(exception)
            }
        }

        enqueue(callback)

        continuation.invokeOnCancellation {
            try {
                cancel()
            } catch (ex: Throwable) {
                // Ignore cancel exception
            }
        }
    }
}

/**
 * @since extensions-lib 1.5
 */
suspend fun Call.awaitSuccess(): Response {
    val callStack = Exception().stackTrace.run { copyOfRange(1, size) }
    val response = await()
    if (!response.isSuccessful) {
        response.close()
        throw HttpException(response.code).apply { stackTrace = callStack }
    }
    return response
}

fun OkHttpClient.newCachelessCallWithProgress(request: Request, listener: ProgressListener): Call {
    val progressClient = newBuilder()
        .cache(null)
        .addNetworkInterceptor { chain ->
            val originalResponse = chain.proceed(chain.request())
            originalResponse.newBuilder()
                .body(ProgressResponseBody(originalResponse.body, listener))
                .build()
        }
        .build()

    return progressClient.newCall(request)
}

fun OkHttpClient.newCachelessCallWithProgress(request: Request, listener: ProgressListener, existingSize: Long): Call {
    require(existingSize >= 0)
    val resumed = if (existingSize > 0) request.newBuilder().header("Range", "bytes=$existingSize-").build() else request
    return newCachelessCallWithProgress(resumed, listener)
}

/**
 * Exception that handles HTTP codes considered not successful by OkHttp.
 * Use it to have a standardized error message in the app across the extensions.
 *
 * @since extensions-lib 1.5
 * @param code [Int] the HTTP status code
 */
class HttpException(val code: Int) : IllegalStateException("HTTP error $code")

/**
 * Binary bridge for the Json context parameter used by current extensions.
 * Its JVM signature is (Json, DeserializationStrategy, Response), including for inlined parseAs calls.
 * API reference: Mihon d001e6f, core/common/network/OkHttpExtensions.kt.
 */
fun <T> decodeFromJsonResponse(json: Json, deserializer: DeserializationStrategy<T>, response: Response): T = response.use {
    require(it.body.contentLength() <= 2 * 1024 * 1024) { "Source JSON exceeds 2 MB" }
    val output = java.io.ByteArrayOutputStream()
    it.body.byteStream().use { input ->
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = input.read(buffer); if (count < 0) break
            require(output.size() + count <= 2 * 1024 * 1024) { "Source JSON exceeds 2 MB" }
            output.write(buffer, 0, count)
        }
    }
    json.decodeFromString(deserializer, output.toByteArray().toString(Charsets.UTF_8))
}
