package com.cosmotune.youtube.account

import java.security.MessageDigest
import java.util.Locale

/**
 * The `Authorization` header Google's web clients send with a cookie session, for one origin.
 *
 * Each hash is `<ts>_<sha1(ts + " " + secret + " " + origin)>`. The site sends three of them in one header, each over its own
 * cookie: `SAPISIDHASH` over `SAPISID`, `SAPISID1PHASH` over `__Secure-1PAPISID` and `SAPISID3PHASH` over `__Secure-3PAPISID`.
 * A request carrying only the first is still accepted today, but it is the shape of an old client, and the first-party and
 * third-party forms are what a partitioned-cookie login has instead of a plain `SAPISID`.
 *
 * `SAPISIDHASH` itself is taken over the first of [SECRET_NAMES] the jar holds, as before: a jar with only the `__Secure-` forms
 * (which is what such a login produces) still signs.
 */
object SapisidAuth {
    /** The cookies a request can be signed with, in the order `SAPISIDHASH` looks for them. */
    val SECRET_NAMES: List<String> = listOf("SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID")

    private const val FIRST_PARTY = "__Secure-1PAPISID"
    private const val THIRD_PARTY = "__Secure-3PAPISID"

    /** The secret `SAPISIDHASH` is taken over, or null when the jar cannot sign. */
    fun secretOf(jar: YouTubeCookieJar): String? = SECRET_NAMES.firstNotNullOfOrNull { jar[it]?.takeIf(String::isNotEmpty) }

    /** The header for [cookieHeader] at [origin], or null when it holds no signing secret. */
    fun authorization(cookieHeader: String?, origin: String, nowSeconds: Long = System.currentTimeMillis() / 1000): String? =
        authorization(YouTubeCookieJar.parse(cookieHeader), origin, nowSeconds)

    /** The header for [jar] at [origin], or null when it holds no signing secret. */
    fun authorization(jar: YouTubeCookieJar, origin: String, nowSeconds: Long = System.currentTimeMillis() / 1000): String? {
        val secret = secretOf(jar) ?: return null
        val parts = ArrayList<String>(3)
        parts += "SAPISIDHASH " + hash(nowSeconds, secret, origin)
        jar[FIRST_PARTY]?.takeIf(String::isNotEmpty)?.let { parts += "SAPISID1PHASH " + hash(nowSeconds, it, origin) }
        jar[THIRD_PARTY]?.takeIf(String::isNotEmpty)?.let { parts += "SAPISID3PHASH " + hash(nowSeconds, it, origin) }
        return parts.joinToString(" ")
    }

    /** `<ts>_<sha1("ts secret origin")>`, lower-case hex. */
    fun hash(timestamp: Long, secret: String, origin: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("$timestamp $secret $origin".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.ROOT, it) }
        return "${timestamp}_$digest"
    }
}
