package com.cosmotune.music.data.downloads

/** A finite, unencrypted media playlist whose references can be copied without interpretation. */
data class OfflineHlsManifest(val initialization: String?, val media: List<String>) {
    companion object {
        fun parse(playlist: String, requireInitialization: Boolean): OfflineHlsManifest {
            require(playlist.toByteArray(Charsets.UTF_8).size <= ManifestDownloadTransport.MAX_MANIFEST_BYTES) { "HLS playlist is too large" }
            require(playlist.startsWith("#EXTM3U")) { "Invalid HLS playlist" }
            val lines = playlist.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
            require(lines.any { it == "#EXT-X-ENDLIST" }) { "Live HLS cannot be saved as a complete song" }
            require(lines.none { it.startsWith("#EXT-X-KEY:") && it.substringAfter(':').trim() != "METHOD=NONE" }) { "Encrypted HLS cannot be saved" }
            val unsupported = listOf("#EXT-X-BYTERANGE:", "#EXT-X-STREAM-INF:", "#EXT-X-I-FRAME-STREAM-INF:",
                "#EXT-X-MEDIA:", "#EXT-X-SESSION-KEY:", "#EXT-X-DISCONTINUITY", "#EXT-X-GAP", "#EXT-X-PART:", "#EXT-X-PRELOAD-HINT:")
            require(lines.none { line -> unsupported.any(line::startsWith) }) { "Unsupported HLS layout" }
            val maps = lines.filter { it.startsWith("#EXT-X-MAP:") }
            require(maps.size <= 1 && maps.none { it.contains("BYTERANGE=") }) { "Unsupported HLS initialization" }
            require(lines.none { it.startsWith('#') && it.contains("URI=") && !it.startsWith("#EXT-X-MAP:") }) { "Unsupported external HLS reference" }
            val init = maps.singleOrNull()?.let {
                Regex("""(?:^|,)URI="([^"]+)"(?:,|$)""").find(it.substringAfter(':'))?.groupValues?.get(1)
                    ?: error("Invalid HLS initialization")
            }
            require(!requireInitialization || init != null) { "HLS playlist has no initialization segment" }
            val media = lines.filter { !it.startsWith('#') }
            require(media.size in 1..ManifestDownloadTransport.MAX_SEGMENTS) { "HLS has no fragments or too many segments" }
            return OfflineHlsManifest(init, media)
        }
    }
}
