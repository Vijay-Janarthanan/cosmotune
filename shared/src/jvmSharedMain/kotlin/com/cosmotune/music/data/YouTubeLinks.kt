package com.cosmotune.music.data

import java.net.URI
import java.net.URLDecoder

/** What a YouTube or YouTube Music link typed or pasted into Search points at. */
sealed interface YouTubeLink {
    /** A song or video, by its id: `watch?v=`, `youtu.be/`, a Short or an embed. */
    data class Song(val videoId: String) : YouTubeLink

    /** An album, playlist or artist page, by its browse id (`VL…` for a playlist, `UC…` for an artist, `MPREb…` for a release). */
    data class Page(val browseId: String) : YouTubeLink {
        val isArtist: Boolean get() = browseId.startsWith("UC")
    }
}

/**
 * Reads a link pasted into Search, so Search plays the song or opens the page instead of searching for the address. The same
 * rules as the phone's "Open with Cosmotune" links: any YouTube host, the video winning over the playlist it came from.
 */
object YouTubeLinks {

    private val HOSTS = setOf("youtube.com", "music.youtube.com", "m.youtube.com", "youtu.be")

    /** The link in [text] (the whole of it, trimmed, with or without `https://`), or null when it is not a YouTube link. */
    fun parse(text: String): YouTubeLink? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
        val withScheme = if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            trimmed
        } else {
            "https://$trimmed"
        }
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
        if (host !in HOSTS) return null
        val segments = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
        val query = parameters(uri.rawQuery)
        if (host == "youtu.be") return segments.firstOrNull()?.let(::song)
        val list = query["list"].orEmpty()
        return when (segments.firstOrNull()) {
            "watch" -> query["v"]?.let(::song) ?: playlist(list)
            "playlist" -> playlist(list)
            "shorts", "embed", "v", "live" -> segments.getOrNull(1)?.let(::song)
            "channel", "browse" -> segments.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { YouTubeLink.Page(it) }
            else -> playlist(list)
        }
    }

    private fun song(id: String): YouTubeLink.Song? =
        id.trim().takeIf { VIDEO_ID.matches(it) }?.let { YouTubeLink.Song(it) }

    private fun playlist(id: String): YouTubeLink.Page? {
        if (id.isBlank()) return null
        return YouTubeLink.Page(if (id.startsWith("VL")) id else "VL$id")
    }

    private fun parameters(raw: String?): Map<String, String> =
        raw.orEmpty().split('&').mapNotNull { pair ->
            val name = pair.substringBefore('=', "")
            if (name.isEmpty()) null else name to URLDecoder.decode(pair.substringAfter('=', ""), Charsets.UTF_8)
        }.toMap()

    private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
}
