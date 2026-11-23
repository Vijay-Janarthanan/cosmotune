package com.cosmotune.podio

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * A radio station link opened from another app (Transistor's "tap a playlist link"): an M3U or PLS address tapped in the browser,
 * a playlist file opened from the file manager, or a station address shared to Cosmotune. It goes to [PodioLinks.pending], where
 * Podio's Add a station dialog picks it up.
 */
object PodioIntentLink {
    private const val EXTRA_CONSUMED = "cosmotune.podioLinkConsumed"
    private val PLAYLIST_TYPES = setOf("audio/x-mpegurl", "audio/mpegurl", "application/x-mpegurl", "audio/x-scpls", "application/pls+xml")

    /** Reads [intent]; true when it carried a station link (and nothing else should read it). */
    fun consume(context: Context, intent: Intent?): Boolean {
        if (intent == null || intent.getBooleanExtra(EXTRA_CONSUMED, false)) return false
        val taken = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let { view(context, it, intent.type) } ?: false
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim()
                ?.takeIf { PodioLinks.accepts(it) }?.let { PodioLinks.offer(it) } ?: false
            else -> false
        }
        if (taken) intent.putExtra(EXTRA_CONSUMED, true)
        return taken
    }

    private fun view(context: Context, uri: Uri, type: String?): Boolean {
        val playlistType = type?.substringBefore(';')?.trim()?.lowercase() in PLAYLIST_TYPES
        return when (uri.scheme) {
            "http", "https" -> (playlistType || PodioLinks.accepts(uri.toString())) && PodioLinks.offer(uri.toString())
            "content", "file" -> {
                val resolved = runCatching { context.contentResolver.getType(uri) }.getOrNull()?.lowercase()
                if (!playlistType && resolved !in PLAYLIST_TYPES && !PodioLinks.accepts(uri.lastPathSegment.orEmpty())) return false
                val text = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input -> String(input.readNBytesCompat(256 * 1024), Charsets.UTF_8) }
                }.getOrNull() ?: return false
                PodioLinks.offerPlaylist(text, fallbackName = uri.lastPathSegment.orEmpty().substringAfterLast('/').substringBeforeLast('.'))
            }
            else -> false
        }
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() < limit) {
            val count = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (count < 0) break
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }
}
