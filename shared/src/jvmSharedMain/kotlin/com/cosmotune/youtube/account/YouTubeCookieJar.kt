package com.cosmotune.youtube.account

import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * The Google session's cookies as a structured jar rather than an opaque header string.
 *
 * One Google login is spread over several origins: the WebView captures one set for music.youtube.com, another for
 * www.youtube.com, and both carry the domain-wide `.youtube.com` cookies as well. They are merged here by name into the one jar
 * both clients (Music's WEB_REMIX and Video's WEB) sign with, and the `Set-Cookie` answers of either client are applied to it, so a
 * rotated `__Secure-1PSIDTS` or `SIDCC` is kept instead of being thrown away with the response.
 *
 * Immutable: every change makes a new jar, so a jar handed out is never edited under its reader. Names keep the order in which they
 * were first seen, which keeps the header stable from one rotation to the next.
 */
class YouTubeCookieJar private constructor(private val entries: Map<String, String>) {

    /** Whether the jar holds nothing. */
    val isEmpty: Boolean get() = entries.isEmpty()

    /** The cookie names, in first-seen order. */
    val names: Set<String> get() = entries.keys

    /** The value of the cookie [name], or null. */
    operator fun get(name: String): String? = entries[name]

    /** The `Cookie` header for this jar: `name=value` pairs joined by `"; "`. */
    fun header(): String = entries.entries.joinToString("; ") { "${it.key}=${it.value}" }

    /** Whether a request can be signed with this jar (it holds one of [SapisidAuth.SECRET_NAMES]). */
    val canSign: Boolean get() = SapisidAuth.secretOf(this) != null

    /**
     * Which Google login this jar belongs to: its API signing secret, which stays the same for the life of a login while the
     * `*PSIDTS` / `*SIDCC` cookies around it rotate. Null when the jar cannot sign.
     */
    val loginKey: String? get() = SapisidAuth.secretOf(this)

    /** This jar with every cookie of [other] laid over it: [other] wins where both have a name. */
    fun mergedWith(other: YouTubeCookieJar): YouTubeCookieJar {
        if (other.isEmpty) return this
        if (isEmpty) return other
        val out = LinkedHashMap(entries)
        out.putAll(other.entries)
        return YouTubeCookieJar(out)
    }

    /**
     * This jar after the `Set-Cookie` header values [setCookies] of a YouTube response: a cookie set is replaced or added, an
     * expired one (`Max-Age=0`, or an `Expires` in the past) is removed. Cookies scoped to a domain outside [domain] are ignored:
     * the YouTube jar is only ever sent to YouTube, the accounts jar only to google.com.
     */
    fun withSetCookies(setCookies: List<String>, nowMs: Long, domain: String = YOUTUBE_DOMAIN): YouTubeCookieJar {
        if (setCookies.isEmpty()) return this
        val out = LinkedHashMap(entries)
        for (raw in setCookies) {
            val cookie = parseSetCookie(raw, nowMs) ?: continue
            if (!appliesTo(cookie.domain, domain)) continue
            if (cookie.expired) out.remove(cookie.name) else out[cookie.name] = cookie.value
        }
        return if (out == entries) this else YouTubeCookieJar(out)
    }

    override fun equals(other: Any?): Boolean = other is YouTubeCookieJar && other.entries == entries

    override fun hashCode(): Int = entries.hashCode()

    override fun toString(): String = "YouTubeCookieJar(${entries.size} cookies)"

    /** One parsed `Set-Cookie` value: its name and value, the `Domain` it named (if any), and whether it deletes the cookie. */
    data class SetCookie(val name: String, val value: String, val domain: String?, val expired: Boolean)

    companion object {
        /** The empty jar. */
        val EMPTY = YouTubeCookieJar(emptyMap())

        /** The jar of a `Cookie` header (`a=1; b=2`); entries without a name or a value are left out. Null or blank is [EMPTY]. */
        fun parse(header: String?): YouTubeCookieJar {
            if (header.isNullOrBlank()) return EMPTY
            val out = LinkedHashMap<String, String>()
            for (entry in header.split(';')) {
                val name = entry.substringBefore('=').trim()
                val value = entry.substringAfter('=', "").trim()
                if (name.isEmpty() || value.isEmpty()) continue
                out[name] = value
            }
            return if (out.isEmpty()) EMPTY else YouTubeCookieJar(out)
        }

        /** The jars of [headers] merged in order: a later header wins where two have a name. Nulls are skipped. */
        fun merge(vararg headers: String?): YouTubeCookieJar =
            headers.fold(EMPTY) { jar, header -> jar.mergedWith(parse(header)) }

        /** Whether two cookie headers belong to the same Google login (see [loginKey]); false when either cannot sign. */
        fun sameLogin(a: String?, b: String?): Boolean {
            val left = parse(a).loginKey ?: return false
            return left == parse(b).loginKey
        }

        /** One `Set-Cookie` header value, or null when it has no name. */
        fun parseSetCookie(raw: String, nowMs: Long): SetCookie? {
            val parts = raw.split(';')
            val pair = parts.firstOrNull() ?: return null
            val name = pair.substringBefore('=').trim()
            if (name.isEmpty()) return null
            val value = pair.substringAfter('=', "").trim().removeSurrounding("\"")
            var domain: String? = null
            var maxAge: Long? = null
            var expiresPast = false
            for (attribute in parts.drop(1)) {
                val key = attribute.substringBefore('=').trim().lowercase()
                val arg = attribute.substringAfter('=', "").trim()
                when (key) {
                    "domain" -> domain = arg.removePrefix(".").lowercase().ifEmpty { null }
                    "max-age" -> arg.toLongOrNull()?.let { maxAge = it }
                    "expires" -> expiresPast = expiresInPast(arg, nowMs)
                }
            }
            // Max-Age outranks Expires when both are there (RFC 6265, 5.3). An empty value is how Google clears a cookie.
            val expired = value.isEmpty() || (maxAge?.let { it <= 0 } ?: expiresPast)
            return SetCookie(name, value, domain, expired)
        }

        /** The domain of the jar both clients send. */
        const val YOUTUBE_DOMAIN = "youtube.com"

        /** The domain of the accounts jar (accounts.google.com). */
        const val GOOGLE_DOMAIN = "google.com"

        private fun appliesTo(cookieDomain: String?, domain: String): Boolean =
            cookieDomain == null || cookieDomain == domain || cookieDomain.endsWith(".$domain")

        /** Whether a cookie `Expires` date is in the past; Google writes it as `Thu, 01-Jan-1970 00:00:10 GMT`. */
        private fun expiresInPast(raw: String, nowMs: Long): Boolean {
            val normalized = raw.trim().replace('-', ' ')
            val parsed = runCatching {
                ZonedDateTime.parse(normalized, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            }.getOrNull()
            if (parsed != null) return parsed <= nowMs
            // An unreadable date still tells its year, which is all a deletion (1970) needs.
            val year = YEAR.find(raw)?.value?.toIntOrNull() ?: return false
            return year < Instant.ofEpochMilli(nowMs).atZone(ZoneOffset.UTC).year
        }

        private val YEAR = Regex("""\b(19|20)\d{2}\b""")
    }
}
