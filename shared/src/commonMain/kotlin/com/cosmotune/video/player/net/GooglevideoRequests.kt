package com.cosmotune.video.player.net

/**
 * The request rules both engines and the downloads share (spec 6.2, 6.3), after NewPipe's `YoutubeHttpDataSource`:
 * `&rn=<n>` on every `videoplayback` request, `&range=<start>-<end>` in chunks of [CHUNK_BYTES] instead of a `Range`
 * header, and a POST with the body [POST_BODY]; the client a stream set was minted for is read from its `c=` parameter.
 * Pure string work: no network, no `java.net`.
 */
object GooglevideoRequests {
    /** The size of one range request: 2 MiB (spec 6.2, 6.3). */
    const val CHUNK_BYTES = 2L * 1024 * 1024

    /** The bytes 0x78, 0x00, as NewPipe's YoutubeHttpDataSource sends; a fresh copy each time, so no caller can change it. */
    val POST_BODY: ByteArray
        get() = byteArrayOf(0x78, 0x00)

    private const val HOST = "googlevideo.com"
    private const val PATH = "/videoplayback"

    /** Whether [url] is a `videoplayback` address on a `googlevideo.com` host (http or https). */
    fun isVideoplayback(url: String): Boolean {
        val parts = split(url) ?: return false
        val host = parts.host.lowercase()
        if (host != HOST && !host.endsWith(".$HOST")) return false
        return parts.path == PATH || parts.path.startsWith("$PATH/")
    }

    /** [url] with `range=<start>-<endInclusive>` as its last parameter, replacing any `range` it had. */
    fun withRange(url: String, start: Long, endInclusive: Long): String = withParameter(url, "range", "$start-$endInclusive")

    /** [url] with `rn=<n>` (the request number) as its last parameter, replacing any `rn` it had. */
    fun withRequestNumber(url: String, n: Int): String = withParameter(url, "rn", n.toString())

    /** The client the address was minted for, its `c=` value ("WEB", "ANDROID_VR", "IOS"), or null when it has none. */
    fun clientOf(url: String): String? {
        val query = url.substringBefore('#').substringAfter('?', missingDelimiterValue = "")
        if (query.isEmpty()) return null
        return query.split('&').firstOrNull { it.substringBefore('=') == "c" }?.substringAfter('=', "")?.takeIf { it.isNotEmpty() }
    }

    private fun withParameter(url: String, name: String, value: String): String {
        val fragment = url.substringAfter('#', missingDelimiterValue = "")
        val beforeFragment = url.substringBefore('#')
        val base = beforeFragment.substringBefore('?')
        val query = beforeFragment.substringAfter('?', missingDelimiterValue = "")
        val kept = if (query.isEmpty()) emptyList() else query.split('&').filter { it.isNotEmpty() && it.substringBefore('=') != name }
        val params = kept + "$name=$value"
        return buildString {
            append(base).append('?').append(params.joinToString("&"))
            if (fragment.isNotEmpty()) append('#').append(fragment)
        }
    }

    private class Parts(val host: String, val path: String)

    /** The host (without user info or port) and path of an http or https address, or null when it is not one. */
    private fun split(url: String): Parts? {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = url.substring(0, schemeEnd).lowercase()
        if (scheme != "http" && scheme != "https") return null
        val rest = url.substring(schemeEnd + 3)
        val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
        val authority = rest.substring(0, authorityEnd).substringAfterLast('@')
        val host = authority.substringBefore(':')
        if (host.isEmpty()) return null
        val path = rest.substring(authorityEnd).substringBefore('?').substringBefore('#')
        return Parts(host, path)
    }
}
