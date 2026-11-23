package com.cosmotune.watch.torrent

import java.net.URLDecoder

/** A torrent the listener has: its info hash (40 hex characters, lower case), the name and trackers a magnet link carries. */
data class TorrentLink(val hash: String, val name: String? = null, val trackers: List<String> = emptyList()) {
    val magnet: String get() = "magnet:?xt=urn:btih:$hash"
}

/**
 * Reads what a listener pastes into Watch search: a magnet link (`magnet:?xt=urn:btih:` with a hex or base32 hash, `dn` and `tr`),
 * or a bare 40-character info hash. Anything else is not a torrent and is searched for as words.
 */
object TorrentLinks {
    private val HEX = Regex("[0-9a-fA-F]{40}")
    private val BASE32 = Regex("[A-Za-z2-7]{32}")

    fun parse(text: String): TorrentLink? {
        val trimmed = text.trim()
        if (HEX.matches(trimmed)) return TorrentLink(trimmed.lowercase())
        if (!trimmed.startsWith("magnet:?", ignoreCase = true) || trimmed.length > 8_000) return null
        val params = trimmed.substringAfter('?').split('&').mapNotNull { part ->
            val name = part.substringBefore('=').lowercase()
            val value = part.substringAfter('=', "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            name to runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
        }
        val hash = params.filter { it.first == "xt" || it.first.startsWith("xt.") }.map { it.second }
            .firstNotNullOfOrNull { xt -> hashOf(xt.substringAfter("urn:btih:", "").takeIf { xt.startsWith("urn:btih:", ignoreCase = true) }) }
            ?: return null
        val name = params.firstOrNull { it.first == "dn" }?.second?.trim()?.take(200)?.takeIf { it.isNotBlank() }
        val trackers = params.filter { it.first == "tr" || it.first.startsWith("tr.") }.map { it.second.trim() }.filter { it.length <= 256 }.distinct().take(8)
        return TorrentLink(hash, name, trackers)
    }

    private fun hashOf(value: String?): String? = when {
        value == null -> null
        HEX.matches(value) -> value.lowercase()
        BASE32.matches(value) -> base32ToHex(value.uppercase())
        else -> null
    }

    private fun base32ToHex(value: String): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var buffer = 0L
        var bits = 0
        val out = StringBuilder()
        for (c in value) {
            buffer = (buffer shl 5) or alphabet.indexOf(c).toLong()
            bits += 5
            while (bits >= 8) {
                bits -= 8
                out.append("%02x".format(((buffer shr bits) and 0xFF).toInt()))
            }
        }
        return out.toString()
    }

    /** Whether [bytes] look like a .torrent file (a bencoded dictionary holding an `info` dictionary), before the engine reads it. */
    fun looksLikeTorrentFile(bytes: ByteArray): Boolean =
        bytes.size in 40..(4 shl 20) && bytes[0] == 'd'.code.toByte() && String(bytes, 0, minOf(bytes.size, 1 shl 20), Charsets.ISO_8859_1).contains("4:infod")
}
