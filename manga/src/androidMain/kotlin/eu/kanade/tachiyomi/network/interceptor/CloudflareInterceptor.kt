package eu.kanade.tachiyomi.network.interceptor

import com.cosmotune.manga.data.MangaBrowserRequired
import okhttp3.Interceptor
import okhttp3.Response

/** SDK-compatible interception point. Verification needs an isolated browser, never music cookies. */
class CloudflareInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val explicit = response.header("cf-mitigated").equals("challenge", true)
        val possible = response.code in setOf(403, 503) && response.header("Server").orEmpty().contains("cloudflare", true)
        val challenge = explicit || possible && response.peekBody(16 * 1024).string().let {
            it.contains("cf-chl-", true) || it.contains("challenge-platform", true) || it.contains("<title>Just a moment", true)
        }
        if (challenge) { response.close(); throw MangaBrowserRequired(chain.request().url.toString()) }
        return response
    }
}
