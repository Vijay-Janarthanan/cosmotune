package eu.kanade.tachiyomi.network

import com.cosmotune.manga.data.MangaCookies
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/** Binary-compatible source cookie API, scoped to Manga's store rather than music account cookies. */
class AndroidCookieJar(private val delegate: CookieJar = defaultSourceJar) : CookieJar {
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = delegate.saveFromResponse(url, cookies)
    override fun loadForRequest(url: HttpUrl): List<Cookie> = delegate.loadForRequest(url)
    fun get(url: HttpUrl): List<Cookie> = loadForRequest(url)
    fun remove(url: HttpUrl, cookieNames: List<String>? = null, maxAge: Int = -1): Int {
        val removed = get(url).filter { cookieNames == null || it.name in cookieNames }
        delegate.saveFromResponse(url, removed.map { it.newBuilder().value("").expiresAt(
            if (maxAge > 0) System.currentTimeMillis() + maxAge * 1000L else 0L).build() })
        return removed.size
    }
    fun removeAll() { (delegate as? MangaCookies)?.clear() }
    companion object { @Volatile internal var defaultSourceJar: CookieJar = CookieJar.NO_COOKIES }
}
