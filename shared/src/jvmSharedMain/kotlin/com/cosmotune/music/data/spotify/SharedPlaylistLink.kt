package com.cosmotune.music.data.spotify

import com.cosmotune.music.data.YtMusicRepository
import com.cosmotune.music.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI
import java.net.URLDecoder

/** Common intake for the Android share sheet and the desktop's import dialog. */
object SharedPlaylistLink {
    data class Imported(val name: String, val songs: List<Song>, val unmatched: List<SpotifyImportTrack>, val unavailable: Int = 0)

    private val linkPattern = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private val spotifyShortHosts = setOf("spotify.link", "spotify.app.link")
    private val youtubeHosts = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com")
    private val youtubeList = Regex("[A-Za-z0-9_-]{10,150}")

    internal fun urls(text: String): Sequence<HttpUrl> = linkPattern.findAll(text.take(16_384))
        .mapNotNull { it.value.trimEnd('.', ',', ')', ']', ';').toHttpUrlOrNull()?.takeIf(::safe) }

    internal fun safe(url: HttpUrl) = url.isHttps && url.port == 443 && url.username.isEmpty() && url.password.isEmpty()

    fun fromText(text: String): String? {
        val uri = text.trim().takeIf { it.startsWith("spotify:playlist:") && SpotifyImporter.extractPlaylistId(it) != null }
        return uri ?: urls(text).firstOrNull { url ->
            spotifyId(url) != null || url.host in spotifyShortHosts || youtubeId(url) != null ||
                PlaylistLinkImport.serviceOf(url.toString()) != null
        }?.toString()
    }

    fun fromOpenUri(input: String): String? {
        if (!input.startsWith("cosmotune:", ignoreCase = true)) return fromText(input)
        val uri = runCatching { URI(input) }.getOrNull() ?: return null
        if (uri.host != "import" || uri.path != "/playlist" || uri.userInfo != null || uri.port != -1) return null
        val value = uri.rawQuery.orEmpty().split('&').singleOrNull { it.startsWith("url=") }?.removePrefix("url=") ?: return null
        return runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull()?.let(::fromText)
    }

    suspend fun import(text: String, onProgress: (Int, Int) -> Unit = { _, _ -> }): Imported {
        val link = fromText(text) ?: throw PlaylistLinkImport.ImportException("Choose a supported public playlist link.")
        val originalUrl = link.toHttpUrlOrNull()
        val url = if (originalUrl != null && originalUrl.host in spotifyShortHosts) {
            withContext(Dispatchers.IO) { PlaylistLinkImport.resolveRedirect(originalUrl, spotifyShortHosts + "open.spotify.com") }
        } else originalUrl
        val spotify = if (url == null) SpotifyImporter.extractPlaylistId(link) else spotifyId(url)
        if (spotify != null) {
            val (name, tracks) = SpotifyImporter.fetchPlaylistTracks(spotify)
            val (songs, missing) = PlaylistLinkImport.match(
                tracks.map { PlaylistLinkImport.LinkTrack(it.title, it.artist, durationMs = it.durationMs) }, onProgress,
            )
            return Imported(name, songs, missing)
        }
        url?.let(::youtubeId)?.let { id ->
            val first = YtMusicRepository.browseSongs(id).getOrThrow()
            val songs = YtMusicRepository.collectCompleteSongPages(first, YtMusicRepository::moreSongs, keepRepeated = true)
            val name = first.header?.title?.takeIf { it.isNotBlank() }
                ?: throw PlaylistLinkImport.ImportException("YouTube Music did not return a playlist name.")
            return Imported(name, songs, emptyList())
        }
        val playlist = PlaylistLinkImport.read(link)
        val (songs, missing) = PlaylistLinkImport.match(playlist.tracks, onProgress)
        val unavailable = ((playlist.declaredCount ?: playlist.tracks.size) - playlist.tracks.size).coerceAtLeast(0)
        return Imported(playlist.name, songs, missing, unavailable)
    }

    private fun spotifyId(url: HttpUrl): String? = url.takeIf { it.host == "open.spotify.com" }
        ?.toString()?.let(SpotifyImporter::extractPlaylistId)

    private fun youtubeId(url: HttpUrl): String? {
        if (url.host !in youtubeHosts || url.encodedPath !in setOf("/playlist", "/watch")) return null
        if (url.encodedPath == "/watch" && !url.queryParameter("v").isNullOrBlank()) return null
        return url.queryParameter("list")?.takeIf { youtubeList.matches(it) }
            ?.let { if (it.startsWith("VL")) it else "VL$it" }
    }
}
