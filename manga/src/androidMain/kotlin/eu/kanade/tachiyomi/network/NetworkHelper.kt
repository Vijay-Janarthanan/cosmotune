// Derived from Tsuki 89b8d9cf6bb53303f340505f46c2ad2c1c8ac5b7; GPL-3.0.
// See manga/third-party/TSUKI_SOURCE_API.json and LICENSE.tsuki.
package eu.kanade.tachiyomi.network

import okhttp3.OkHttpClient

/**
 * Mihon-compatible NetworkHelper interface.
 * Provides access to OkHttpClient for extensions.
 *
 * This will be implemented by app to bridge with its existing network stack.
 */
abstract class NetworkHelper {

    /**
     * The bounded Manga client. Browser verification is surfaced explicitly when required.
     */
    abstract val client: OkHttpClient
    open val cookieJar: AndroidCookieJar get() = client.cookieJar as? AndroidCookieJar ?: AndroidCookieJar(client.cookieJar)

    /**
     * @deprecated Since extension-lib 1.5
     */
    @Deprecated("The regular client handles Cloudflare by default")
    open val cloudflareClient: OkHttpClient
        get() = client

    /**
     * Returns the default user agent string.
     */
    abstract fun defaultUserAgentProvider(): String
}
