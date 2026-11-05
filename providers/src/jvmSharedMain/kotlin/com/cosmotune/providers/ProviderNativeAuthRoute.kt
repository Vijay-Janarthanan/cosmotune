package com.cosmotune.providers

import java.net.URI
import java.util.Locale

/** Only schemes registered by this host application, never another reader/provider's scheme. */
class ProviderNativeAuthRoute(vararg schemes: String) {
    private val owned = schemes.map { it.lowercase(Locale.ROOT) }.toSet().also { values ->
        require(values.all { it.matches(Regex("[a-z][a-z0-9+.-]{2,100}")) && it !in setOf("http", "https", "file", "content", "intent", "javascript", "data") })
    }
    fun allows(input: String): Boolean = input.length in 1..16_384 && runCatching {
        val uri = URI(input)
        uri.scheme?.lowercase(Locale.ROOT) in owned && uri.host.equals("provider-auth", true) &&
            !uri.isOpaque && uri.userInfo == null && uri.port == -1
    }.getOrDefault(false)
}
