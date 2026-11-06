package com.cosmotune.watch

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * A shareable list of add-ons: manifest URLs and names, deflated and base64url-encoded into a `cosmotune://watch/pack?d=`
 * link short enough for a QR code. Servers, playlists and anything signed in never go into a pack, and neither does a
 * manifest URL that looks configured with a secret (a long opaque path segment, JSON or a token in it).
 */
object PluginPack {
    const val PREFIX = "cosmotune://watch/pack?d="
    private const val MAX_ITEMS = 50

    @Serializable data class Item(val u: String, val n: String = "")
    @Serializable data class Pack(val v: Int = 1, val a: List<Item>)

    private val json = Json { ignoreUnknownKeys = true }

    /** Whether [manifestUrl] may be shared: no query, no user info and no path segment that could carry a key. */
    fun shareable(manifestUrl: String): Boolean {
        val uri = runCatching { java.net.URI(manifestUrl) }.getOrNull() ?: return false
        if (uri.scheme != "https" || uri.rawUserInfo != null || uri.rawQuery != null) return false
        val segments = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }.dropLast(1)
        return segments.none { s ->
            val d = java.net.URLDecoder.decode(s, "UTF-8")
            d.length > 32 || '{' in d || '=' in d || d.startsWith("eyJ") || Regex("[A-Za-z0-9_-]{24,}").matches(d)
        }
    }

    fun encode(items: List<Item>): String {
        val raw = json.encodeToString(Pack.serializer(), Pack(a = items.filter { shareable(it.u) }.distinctBy { it.u }.take(MAX_ITEMS))).toByteArray()
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true).apply { setInput(raw); finish() }
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }

    /** The items of a pack link (or of its `d` value alone). Throws for anything that is not a pack. */
    fun decode(payload: String): List<Item> {
        val data = payload.trim().substringAfter("pack?d=").substringAfter("d=").substringBefore('&')
        val bytes = Base64.getUrlDecoder().decode(data)
        require(bytes.size < 16 * 1024) { "This pack is too large" }
        val inflater = Inflater(true).apply { setInput(bytes) }
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (!inflater.finished()) {
            val n = inflater.inflate(buffer)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            out.write(buffer, 0, n)
            require(out.size() < 256 * 1024) { "This pack is too large" }
        }
        inflater.end()
        val pack = json.decodeFromString(Pack.serializer(), out.toString(Charsets.UTF_8.name()))
        return pack.a.filter { shareable(it.u) }.take(MAX_ITEMS)
    }
}

/** Install links: `stremio://host/manifest.json`, `https://.../manifest.json`, `cosmotune://watch/addon?url=<encoded>`. */
object InstallLinks {
    fun manifestUrl(link: String): String? {
        val t = link.trim()
        val raw = when {
            t.startsWith("cosmotune://watch/addon") -> t.substringAfter("url=", "").substringBefore('&').let { java.net.URLDecoder.decode(it, "UTF-8") }
            else -> t
        }
        if (raw.isBlank()) return null
        val url = com.cosmotune.watch.plugins.stremio.StremioClient.normalise(raw)
        return url.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    }

    /** Whether a link opened from outside (a deep link, a URL handler) is one Watch should offer to install. */
    fun isInstallLink(link: String): Boolean {
        val t = link.trim()
        return t.startsWith("stremio://") || t.startsWith("cosmotune://watch/addon") ||
            ((t.startsWith("https://") || t.startsWith("http://")) && t.substringBefore('?').endsWith("/manifest.json"))
    }

    fun isPackLink(link: String) = link.trim().startsWith(PluginPack.PREFIX)
}
