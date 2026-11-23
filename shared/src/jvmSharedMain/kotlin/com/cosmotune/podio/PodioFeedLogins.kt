package com.cosmotune.podio

import java.net.URI
import java.util.Base64
import kotlinx.serialization.Serializable

/** The user name and password a private feed asks for (HTTP Basic). Kept on this device only; it never syncs. */
@Serializable data class PodioFeedLogin(val user: String, val password: String)

/**
 * Feeds behind a user name and password (AntennaPod's password-protected feeds). The listener types them into the address
 * ("https://name:password@host/feed"); the address is kept without them, and they go with every request made to that feed's
 * server: the feed, its older pages, chapters and transcripts, and the episodes as they play or download ([interceptor] on the
 * app's own HTTP client, [headersFor] for the desktop's FFmpeg reader). A redirect to another server never carries them.
 */
object PodioFeedLogins {
    @Volatile private var loaded: Map<String, PodioFeedLogin> = emptyMap()
    @Volatile private var library: java.io.File? = null
    @Volatile private var read = false

    /** The logins the library holds, by feed address; the library keeps this up to date, and it is read on first use. */
    var known: Map<String, PodioFeedLogin>
        get() {
            if (!read) library?.let { file -> synchronized(this) { if (!read) { runCatching { loaded = PodioLibrary(file).state.value.feedLogins }; read = true } } }
            return loaded
        }
        set(value) { loaded = value; read = true }

    /** Where the library is, so a player or a download that starts before any Podio screen still finds the logins. */
    fun install(libraryFile: java.io.File) { library = libraryFile; read = false }

    /** [headers] with the login for [url] added, when its server has one and the headers carry no sign-in of their own. */
    fun headersFor(url: String, headers: Map<String, String> = emptyMap()): Map<String, String> {
        if (!url.startsWith("http", ignoreCase = true) || headers.keys.any { it.equals("Authorization", true) }) return headers
        val login = forUrl(url) ?: return headers
        return headers + ("Authorization" to header(login))
    }

    /** Adds the login to the app's requests to a private feed's server (episodes playing or downloading). */
    val interceptor = okhttp3.Interceptor { chain ->
        val request = chain.request()
        if (request.header("Authorization") != null) return@Interceptor chain.proceed(request)
        val login = forUrl(request.url.toString()) ?: return@Interceptor chain.proceed(request)
        chain.proceed(request.newBuilder().header("Authorization", header(login)).build())
    }

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
