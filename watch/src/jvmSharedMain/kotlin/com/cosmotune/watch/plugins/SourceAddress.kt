package com.cosmotune.watch.plugins

import java.net.URLDecoder
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Independent normalization of add-on addresses. A malformed network URL never becomes a local file or custom scheme. */
object SourceAddress {
    data class Parsed(val url: String, val headers: Map<String, String>)

    fun parse(value: String, headers: Map<String, String> = emptyMap()): Parsed? {
        if (value.length > 32_768 || value.any { it.code < 32 || it.code == 127 }) return null
        val address = value.trim().substringBefore('|')
        val fixed = when {
            address.startsWith("https:/", true) -> "https://" + address.substringAfter(':').trimStart('/')
            address.startsWith("http:/", true) -> "http://" + address.substringAfter(':').trimStart('/')
            address.startsWith("//") -> "https:$address"
            else -> address
        }
        val url = fixed.toHttpUrlOrNull()?.takeIf { it.username.isEmpty() && it.password.isEmpty() } ?: return null
        val inline = if ('|' in value) value.substringAfter('|').split('&').take(32).mapNotNull { entry ->
            if ('=' !in entry) null else runCatching {
                URLDecoder.decode(entry.substringBefore('='), "UTF-8") to URLDecoder.decode(entry.substringAfter('='), "UTF-8")
            }.getOrNull()
        }.toMap() else emptyMap()
        // Explicit proxy request headers win; matching ignores case so inline headers cannot shadow them.
        val safe = linkedMapOf<String, Pair<String, String>>()
        (inline.entries + headers.entries).forEach { (name, content) ->
            if (safeHeader(name, content)) safe[name.lowercase()] = name to content
        }
        return Parsed(url.toString(), safe.values.toMap())
    }

    fun safeHeader(name: String, value: String): Boolean = name.length in 1..128 &&
        name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) && name.lowercase() !in setOf(
            "host", "connection", "content-length", "transfer-encoding", "proxy-authorization", "proxy-connection", "range", "upgrade", "te", "trailer"
        ) && value.length <= 8_192 && value.none { it.code < 32 || it.code == 127 }
}
