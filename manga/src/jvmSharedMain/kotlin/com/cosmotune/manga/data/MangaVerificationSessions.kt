package com.cosmotune.manga.data

import java.util.UUID
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor

data class MangaVerificationRequest(val sourceId: String, val address: String, val nonce: String, val expiresAt: Long)
data class MangaBrowserProof(val address: String, val cookieHeader: String, val userAgent: String)

/** Browser proof is deliberately RAM-only: never part of MangaStore, cookie files or exports. */
class MangaVerificationSessions(private val clock: () -> Long = System::currentTimeMillis) {
    private data class Session(val sourceId: String, val origin: String, val cookies: List<Cookie>, val userAgent: String, val expiresAt: Long)
    private val pending = LinkedHashMap<String, MangaVerificationRequest>()
    private val sessions = LinkedHashMap<String, Session>()
    // Keep suppressing disk persistence after reset/expiry so a late reply cannot save old proof.
    private val managedOrigins = HashSet<String>()

    @Synchronized fun begin(sourceId: String, address: String): MangaVerificationRequest {
        val url = checked(address)
        require(managedOrigins.size < 64 || origin(url) in managedOrigins) { "Restart Manga before verifying another source." }
        managedOrigins += origin(url)
        val request = MangaVerificationRequest(sourceId, url.toString(), UUID.randomUUID().toString(), clock() + REQUEST_TTL_MS)
        pending[sourceId] = request
        return request
    }

    @Synchronized fun accept(request: MangaVerificationRequest, proof: MangaBrowserProof) {
        require(pending[request.sourceId] == request && clock() < request.expiresAt) { "This source verification expired. Open it again." }
        val url = checked(request.address)
        require(origin(checked(proof.address)) == origin(url)) { "Return to the source website before using its verified session." }
        require(proof.cookieHeader.length <= 32_768 && proof.userAgent.length in 1..1_024 && proof.userAgent.none(Char::isISOControl)) {
            "The browser returned an invalid source session."
        }
        val cookies = proof.cookieHeader.split(';').filter { it.isNotBlank() }.map { piece ->
            val name = piece.substringBefore('=').trim(); val value = piece.substringAfter('=', "").trim()
            require(piece.contains('=') && name.isNotBlank()) { "The browser returned an invalid cookie." }
            Cookie.Builder().name(name).value(value).hostOnlyDomain(url.host).path("/").secure().httpOnly().build()
        }.distinctBy { it.name }
        require(cookies.isNotEmpty() && cookies.size <= 128) { "The source did not return verification cookies. Complete the check first." }
        sessions[request.sourceId] = Session(request.sourceId, origin(url), cookies, proof.userAgent, clock() + SESSION_TTL_MS)
        pending.remove(request.sourceId)
    }

    @Synchronized fun reset(sourceId: String) { pending.remove(sourceId); sessions.remove(sourceId) }
    @Synchronized fun clear() { pending.clear(); sessions.clear() }
    @Synchronized fun active(sourceId: String): Boolean { expire(); return sourceId in sessions }
    @Synchronized private fun session(url: HttpUrl): Session? { expire(); return sessions.values.lastOrNull { it.origin == origin(url) } }
    private fun expire() { val now = clock(); sessions.entries.removeAll { it.value.expiresAt <= now }; pending.entries.removeAll { it.value.expiresAt <= now } }

    fun cookieJar(base: CookieJar): CookieJar = object : CookieJar {
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val proof = synchronized(this@MangaVerificationSessions) { session(url)?.cookies.orEmpty().filter { it.matches(url) && it.expiresAt > clock() } }
            return base.loadForRequest(url).filter { original -> proof.none { it.name == original.name } } + proof
        }
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val managed = synchronized(this@MangaVerificationSessions) {
                val managed = origin(url) in managedOrigins
                session(url)?.let { old ->
                    val next = (old.cookies.filter { original -> cookies.none { it.name == original.name && it.path == original.path } } + cookies)
                        .filter { it.expiresAt > clock() && it.matches(url) }.takeLast(128)
                    sessions[old.sourceId] = old.copy(cookies = next)
                }
                managed
            }
            if (!managed) base.saveFromResponse(url, cookies)
        }
    }

    /** Network interceptors run for each redirect hop; the verified UA is never a client default. */
    fun userAgentInterceptor(): Interceptor = Interceptor { chain ->
        val request = chain.request()
        val ua = synchronized(this) { session(request.url)?.userAgent }
        chain.proceed(if (ua == null) request else request.newBuilder().header("User-Agent", ua).build())
    }

    companion object {
        const val REQUEST_TTL_MS = 10L * 60 * 1000
        const val SESSION_TTL_MS = 30L * 60 * 1000
        fun checked(address: String): HttpUrl = address.toHttpUrl().also {
            require(it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.fragment == null && address.length <= 8_192) {
                "Use the source's HTTPS website without embedded credentials."
            }
        }
        fun origin(url: HttpUrl): String = "${url.scheme}://${url.host}:${url.port}"
    }
}
