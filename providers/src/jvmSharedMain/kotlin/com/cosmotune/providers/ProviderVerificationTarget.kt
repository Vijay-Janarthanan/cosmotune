package com.cosmotune.providers

import java.net.URI
import java.net.URLDecoder
import java.util.Locale
import kotlinx.serialization.Serializable

/** One native browser handoff, without provider credentials or a JavaScript/native object bridge. */
@Serializable
data class ProviderVerificationTarget(val url: String, val callback: String, val state: String,
    val title: String, val domains: List<String>, val expiresAt: Long, val signed: Boolean, val packageHash: String = "") {
    init {
        require(url.length <= 16_384 && callback.length <= 8_192 && state.length in 16..512)
        require(URI(url).scheme == "https" && URI(url).userInfo == null && URI(url).fragment == null)
        require(title.length <= 200 && domains.size <= 100)
    }
    override fun toString() = "ProviderVerificationTarget(title=$title, signed=$signed)"

    fun allowsNavigation(address: String): Boolean = runCatching {
        val uri = URI(address)
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        uri.scheme == "https" && uri.userInfo == null && domains.any { domain ->
            host == domain.lowercase(Locale.ROOT) || domain.startsWith("*.") && host.endsWith(domain.drop(1).lowercase(Locale.ROOT))
        }
    }.getOrDefault(false)

    /** Only completed callbacks; a challenge's `cb` parameter alone is never treated as a result. */
    fun completedCallback(address: String, now: Long = System.currentTimeMillis()): String? = runCatching {
        require(now < expiresAt && address.length <= 16_384)
        val received = URI(address)
        val expected = URI(callback)
        if (received.scheme.equals(expected.scheme, true) && received.rawAuthority == expected.rawAuthority &&
            received.rawPath.orEmpty() == expected.rawPath.orEmpty() && received.userInfo == null) {
            val values = parameters(received.rawQuery ?: received.rawFragment.orEmpty())
            require(values["state"] == state && values["error"].isNullOrEmpty())
            require(values.containsKey("grant") xor values.containsKey("code"))
            require((values["grant"] ?: values["code"]).orEmpty().length in 1..8_192)
            parameters(expected.rawQuery.orEmpty()).forEach { (key, value) -> require(values[key] == value) }
            address
        } else {
            require(allowsNavigation(address))
            val nested = parameters(received.rawQuery.orEmpty())["cb"] ?: return null
            // Only one level of wrapping, with the complete callback's own state/address checks.
            require(parameters(URI(nested).rawQuery.orEmpty()).keys.any { it in setOf("grant", "code") })
            copy(domains = emptyList()).completedCallback(nested, now)
        }
    }.getOrNull()

    /** Some operator pages return only a grant. Read it only from this exact active challenge. */
    fun verifiedPageGrant(address: String, text: String, now: Long = System.currentTimeMillis()): String? = runCatching {
        require(signed && now < expiresAt && text.length <= 16_384)
        val expected = URI(url); val received = URI(address)
        require(expected.scheme == received.scheme && expected.rawAuthority == received.rawAuthority && expected.rawPath == received.rawPath)
        val values = parameters(received.rawQuery.orEmpty())
        parameters(expected.rawQuery.orEmpty()).forEach { (key, value) -> require(values[key] == value) }
        Regex("(?:^|[\\s?&#])grant=([A-Za-z0-9_-]{1,8192})(?=$|[\\s&#])").find(text)?.groupValues?.get(1)?.let { "grant=$it" }
    }.getOrNull()

    private fun parameters(query: String): Map<String, String> = buildMap {
        require(query.length <= 16_384)
        query.split('&').filter(String::isNotEmpty).forEach { pair ->
            val key = URLDecoder.decode(pair.substringBefore('='), "UTF-8")
            require(!containsKey(key))
            put(key, URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8"))
        }
    }

    companion object {
        // No native object, arbitrary script, storage read, clipboard read or form submission.
        const val GRANT_SCRIPT = "(function(){var n=document.getElementById('fallback');return JSON.stringify({url:location.href,text:n&&n.innerText?n.innerText.slice(0,16384):''});})()"
    }
}

/** Installed by the desktop host; UI tests and hosts without a browser retain explicit fallback. */
object ProviderVerificationWindows {
    @Volatile var launcher: ((ProviderManager, String, ProviderVerificationTarget, () -> Unit) -> AutoCloseable)? = null
}
