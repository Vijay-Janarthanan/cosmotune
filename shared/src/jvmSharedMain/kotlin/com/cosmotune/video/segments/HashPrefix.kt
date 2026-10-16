package com.cosmotune.video.segments

import java.security.MessageDigest

private const val HEX = "0123456789abcdef"

/**
 * The first [chars] characters of the lower-case hex SHA-256 of [videoId]'s UTF-8 bytes: what SponsorBlock and DeArrow take
 * instead of a video id, so the service cannot tell which video of the ones sharing a prefix was meant (spec 2.3, 7.1: 4
 * characters; SponsorBlock accepts 4 to 32, DeArrow exactly 4). [chars] is 1 to 64, the length of the digest.
 */
fun sha256Prefix(videoId: String, chars: Int = 4): String {
    require(chars in 1..64) { "chars must be 1 to 64" }
    val digest = MessageDigest.getInstance("SHA-256").digest(videoId.toByteArray(Charsets.UTF_8))
    val out = CharArray(chars)
    for (i in 0 until chars) {
        val byte = digest[i / 2].toInt() and 0xFF
        out[i] = HEX[if (i % 2 == 0) byte ushr 4 else byte and 0x0F]
    }
    return String(out)
}
