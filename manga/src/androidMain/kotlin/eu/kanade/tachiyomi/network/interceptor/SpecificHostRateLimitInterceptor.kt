// Derived from Tsuki 89b8d9cf6bb53303f340505f46c2ad2c1c8ac5b7; GPL-3.0.
// See manga/third-party/TSUKI_SOURCE_API.json and LICENSE.tsuki.
package eu.kanade.tachiyomi.network.interceptor

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import com.cosmotune.manga.data.RequestPacer
import java.util.concurrent.TimeUnit

/**
 * Rate limit interceptor for specific hosts.
 *
 * This is a compatibility shim for Mihon extensions that use SpecificHostRateLimitInterceptor.
 */
class SpecificHostRateLimitInterceptor(
    private val host: HttpUrl,
    private val permits: Int = 1,
    private val period: Long = 1,
    private val unit: TimeUnit = TimeUnit.SECONDS,
) : Interceptor {

    private val gate = RequestPacer(permits, period, unit)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host.equals(host.host, ignoreCase = true)) gate.acquire { chain.call().isCanceled() }
        return chain.proceed(request)
    }
}

/**
 * Extension function to add specific host rate limiting to OkHttpClient.
 */
fun OkHttpClient.Builder.rateLimit(
    host: HttpUrl,
    permits: Int = 1,
    period: Long = 1,
    unit: TimeUnit = TimeUnit.SECONDS,
): OkHttpClient.Builder {
    return addInterceptor(SpecificHostRateLimitInterceptor(host, permits, period, unit))
}

/**
 * Extension function to add specific host rate limiting by hostname.
 */
fun OkHttpClient.Builder.rateLimit(
    hostname: String,
    permits: Int = 1,
    period: Long = 1,
    unit: TimeUnit = TimeUnit.SECONDS,
): OkHttpClient.Builder {
    val url = HttpUrl.Builder()
        .scheme("https")
        .host(hostname)
        .build()
    return rateLimit(url, permits, period, unit)
}

/**
 * Alias for rateLimit(HttpUrl, ...) - used by some extensions.
 */
fun OkHttpClient.Builder.rateLimitHost(
    url: HttpUrl,
    permits: Int = 1,
    period: Long = 1,
    unit: TimeUnit = TimeUnit.SECONDS,
): OkHttpClient.Builder {
    return rateLimit(url, permits, period, unit)
}

/**
 * Alias for rateLimit(String, ...) - used by some extensions.
 */
fun OkHttpClient.Builder.rateLimitHost(
    hostname: String,
    permits: Int = 1,
    period: Long = 1,
    unit: TimeUnit = TimeUnit.SECONDS,
): OkHttpClient.Builder {
    return rateLimit(hostname, permits, period, unit)
}
