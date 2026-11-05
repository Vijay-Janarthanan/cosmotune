// Derived from Tsuki 89b8d9cf6bb53303f340505f46c2ad2c1c8ac5b7; GPL-3.0.
// See manga/third-party/TSUKI_SOURCE_API.json and LICENSE.tsuki.
package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.TimeUnit
import com.cosmotune.manga.data.RequestPacer

/**
 * Sliding-window request pacing for extensions, without holding a lock across network I/O.
 */
fun OkHttpClient.Builder.rateLimit(
    permits: Int,
    period: Long = 1,
    unit: TimeUnit = TimeUnit.SECONDS,
): OkHttpClient.Builder {
    return addInterceptor(RateLimitInterceptor(permits, period, unit))
}

/**
 * Overload for extensions using milliseconds.
 */
fun OkHttpClient.Builder.rateLimitHost(
    permits: Int,
    period: Long = 1,
    unit: TimeUnit = TimeUnit.SECONDS,
): OkHttpClient.Builder {
    return addInterceptor(RateLimitInterceptor(permits, period, unit))
}

class RateLimitInterceptor(
    private val permits: Int,
    private val period: Long,
    private val unit: TimeUnit,
) : Interceptor {

    private val gate = RequestPacer(permits, period, unit)
    override fun intercept(chain: Interceptor.Chain): Response {
        gate.acquire { chain.call().isCanceled() }
        return chain.proceed(chain.request())
    }
}
