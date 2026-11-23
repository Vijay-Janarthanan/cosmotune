package com.cosmotune.podio

import java.net.URI
import java.util.Base64
import kotlinx.serialization.Serializable

/** The user name and password a private feed asks for (HTTP Basic). Kept on this device only; it never syncs. */
@Serializable data class PodioFeedLogin(val user: String, val password: String)

/**
 * Feeds behind a user name and password (AntennaPod's password-protected feeds). The listener types them into the address
 * ("https://name:password@host/feed"); the address is kept without them, and they go with every request Podio makes to that
 * feed's server: the feed, its older pages, chapters and transcripts.
 */
object PodioFeedLogins {
    /** The logins the library holds, by feed address; the library keeps this up to date. */
    @Volatile var known: Map<String, PodioFeedLogin> = emptyMap()

    /** [address] without a login in it, and the login it carried, if any. */
    fun split(address: String): Pair<String, PodioFeedLogin?> {
        val uri = runCatching { URI(address.trim()) }.getOrNull() ?: return address.trim() to null
        val info = uri.rawUserInfo?.takeIf { it.isNotEmpty() } ?: return address.trim() to null
        val host = uri.host ?: return address.trim() to null
        val clean = "${uri.scheme}://$host${if (uri.port > 0) ":${uri.port}" else ""}${uri.rawPath.orEmpty()}${uri.rawQuery?.let { "?$it" }.orEmpty()}"
        val user = decode(info.substringBefore(':'))
        val password = decode(info.substringAfter(':', ""))
        return clean to PodioFeedLogin(user, password).takeIf { user.isNotEmpty() }
    }

    /** The login for a request to [url]: its feed's own, else one for a feed on the same server. */
    fun forUrl(url: String, logins: Map<String, PodioFeedLogin> = known): PodioFeedLogin? {
        if (logins.isEmpty()) return null
        logins[url]?.let { return it }
        val host = hostOf(url) ?: return null
        return logins.entries.firstOrNull { hostOf(it.key) == host }?.value
    }

    /** The Authorization header for [login]. */
    fun header(login: PodioFeedLogin): String =
        "Basic " + Base64.getEncoder().encodeToString("${login.user}:${login.password}".toByteArray(Charsets.UTF_8))

    private fun hostOf(url: String): String? = runCatching { URI(url).let { "${it.scheme}://${it.host?.lowercase()}:${it.port}" } }.getOrNull()
    private fun decode(text: String): String = runCatching { java.net.URLDecoder.decode(text.replace("+", "%2B"), Charsets.UTF_8) }.getOrDefault(text)
}
