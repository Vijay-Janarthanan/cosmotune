package com.cosmotune.listening

import com.cosmotune.music.data.model.Song
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI

data class M3uPlaylistExport(val text: String, val included: Int, val skipped: Int)

/** An ordered UTF-8 local-file playlist. Expiring stream URLs and account credentials never enter it. */
fun exportLocalM3u8(name: String, songs: List<Song>, localLibrary: List<Song> = emptyList(),
    readable: (File) -> Boolean = { it.isFile && it.canRead() }): M3uPlaylistExport {
    require(songs.size <= 10_000) { "Export at most 10,000 tracks in one playlist" }
    val output = ByteArrayOutputStream()
    fun line(text: String) {
        val bytes = (text + "\n").toByteArray(Charsets.UTF_8)
        require(output.size() + bytes.size <= 4 * 1024 * 1024) { "Playlist exceeds the 4 MB export limit" }
        output.write(bytes)
    }
    fun label(text: String) = text.map { if (it.code < 32 || it.code == 127) ' ' else it }.joinToString("").trim().take(1_000)
    line("#EXTM3U"); line("# Cosmotune playlist: ${label(name)}")
    val localCandidates = localLibrary.groupBy { it.videoId }
    fun localFile(song: Song): File? =
        song.localPath?.takeIf(String::isNotBlank)?.let(::File)?.takeIf { it.isAbsolute && readable(it) }
            ?: song.localUri?.let { raw -> runCatching {
                val uri = URI(raw)
                require(uri.scheme.equals("file", true) && uri.rawUserInfo == null && uri.query == null && uri.fragment == null)
                File(uri).takeIf { it.isAbsolute && readable(it) }
            }.getOrNull() }
    var included = 0
    for (song in songs) {
        val path = localFile(song) ?: localCandidates[song.videoId]?.firstNotNullOfOrNull(::localFile)
        if (path == null) continue
        val address = path.absoluteFile.toURI().toASCIIString()
        if (address.length > 16_384) continue
        line("#EXTINF:${durationSeconds(song.durationText)},${label(song.artist)} - ${label(song.title)}")
        line(address)
        included++
    }
    require(included > 0) { "No readable local audio files are available to export" }
    return M3uPlaylistExport(output.toString("UTF-8"), included, songs.size - included)
}

private fun durationSeconds(text: String?): Long = runCatching {
    val parts = requireNotNull(text).trim().split(':')
    require(parts.size in 1..3)
    var result = 0L
    for ((index, part) in parts.withIndex()) {
        val value = requireNotNull(part.toLongOrNull()); require(value >= 0 && (index == 0 || value < 60))
        result = Math.addExact(Math.multiplyExact(result, 60), value)
    }
    require(result <= Int.MAX_VALUE)
    result
}.getOrDefault(-1L)
