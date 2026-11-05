// Derived from Mihon d001e6f210e6a077a30dd9a1392573a3225bba2f; Apache-2.0.
// See manga/third-party/MIHON_SOURCE_API.json and LICENSE.mihon.
package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Catches any uncaught exceptions from later in the chain and rethrows as a non-fatal
 * IOException to avoid catastrophic failure.
 *
 * This should be the first interceptor in the client.
 *
 * See https://square.github.io/okhttp/4.x/okhttp/okhttp3/-interceptor/
 */
class UncaughtExceptionInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        return try {
            chain.proceed(chain.request())
        } catch (e: Exception) {
            if (e is IOException) {
                throw e
            } else {
                throw IOException(e)
            }
        }
    }
}
