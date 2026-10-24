package com.cosmotune.music.data.sources

import okhttp3.ResponseBody
import java.io.IOException

/** Applies to provider metadata, never the audio stream itself. Count decoded bytes, including chunked bodies. */
const val MAX_ADDON_JSON_BYTES = 2L * 1024 * 1024

fun ResponseBody.readAddonJsonBody(): String {
    if (contentLength() > MAX_ADDON_JSON_BYTES) throw IOException("Addon response exceeds 2 MB")
    val input = source()
    input.request(MAX_ADDON_JSON_BYTES + 1)
    if (input.buffer.size > MAX_ADDON_JSON_BYTES) throw IOException("Addon response exceeds 2 MB")
    return string()
}
