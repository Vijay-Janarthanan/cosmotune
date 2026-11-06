package com.cosmotune.watch.plugins

import com.cosmotune.watch.model.Container
import com.cosmotune.watch.model.StreamProtocol

/** Reads what a stream is from its address and its label, the way add-ons and playlists describe them. */
object StreamGuess {
    fun protocol(url: String, mime: String? = null): StreamProtocol {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        val type = mime?.lowercase().orEmpty()
        return when {
            url.startsWith("file:") || url.startsWith("/") || Regex("^[a-zA-Z]:\\\\").containsMatchIn(url) -> StreamProtocol.LOCAL_FILE
            path.endsWith(".m3u8") || "mpegurl" in type || "/hls/" in path -> StreamProtocol.HLS
            path.endsWith(".mpd") || "dash+xml" in type -> StreamProtocol.DASH
            else -> StreamProtocol.PROGRESSIVE
        }
    }

    fun container(url: String, mime: String? = null): Container {
        val ext = url.substringBefore('?').substringBefore('#').substringAfterLast('/').substringAfterLast('.', "").lowercase()
        val type = mime?.lowercase().orEmpty()
        return when {
            ext == "mp4" || ext == "m4v" || "mp4" in type -> Container.MP4
            ext == "mkv" || "matroska" in type -> Container.MKV
            ext == "ts" || ext == "m2ts" || "mp2t" in type -> Container.TS
            ext == "webm" || "webm" in type -> Container.WEBM
            ext == "avi" -> Container.AVI
            ext == "mov" || "quicktime" in type -> Container.MOV
            else -> Container.UNKNOWN
        }
    }

    /** 2160, 1080, 720, 480 from "4K", "2160p", "1080p HEVC", "UHD"... */
    fun height(vararg texts: String?): Int? {
        val all = texts.filterNotNull().joinToString(" ").lowercase()
        Regex("(\\d{3,4})p").find(all)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 144..4320 }?.let { return it }
        return when {
            Regex("\\b(4k|uhd)\\b").containsMatchIn(all) -> 2160
            Regex("\\b(fhd|full ?hd)\\b").containsMatchIn(all) -> 1080
            Regex("\\bhd\\b").containsMatchIn(all) -> 720
            Regex("\\bsd\\b").containsMatchIn(all) -> 480
            else -> null
        }
    }

    fun hdr(vararg texts: String?): String? {
        val all = texts.filterNotNull().joinToString(" ").lowercase()
        return when {
            Regex("\\b(dv|dolby ?vision|dovi)\\b").containsMatchIn(all) -> "DV"
            "hdr10" in all || Regex("\\bhdr\\b").containsMatchIn(all) -> "HDR10"
            "hlg" in all -> "HLG"
            else -> null
        }
    }

    fun codec(vararg texts: String?): String? {
        val all = texts.filterNotNull().joinToString(" ").lowercase()
        return when {
            Regex("\\b(hevc|x265|h\\.?265)\\b").containsMatchIn(all) -> "hevc"
            Regex("\\bav1\\b").containsMatchIn(all) -> "av1"
            Regex("\\b(avc|x264|h\\.?264)\\b").containsMatchIn(all) -> "avc"
            else -> null
        }
    }

    /** Bytes from "58 GB", "1.4 GiB", "700 MB". */
    fun size(text: String?): Long? {
        val m = Regex("(\\d+(?:[.,]\\d+)?)\\s*(gb|gib|mb|mib)", RegexOption.IGNORE_CASE).find(text ?: return null) ?: return null
        val n = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        return (n * if (m.groupValues[2].lowercase().startsWith("g")) 1024.0 * 1024 * 1024 else 1024.0 * 1024).toLong()
    }

    /** Only these schemes are ever played; magnet, torrent and anything else are dropped. */
    fun playableScheme(url: String): Boolean = url.startsWith("https://") || url.startsWith("http://")

    fun isHttps(url: String) = url.startsWith("https://")
}
