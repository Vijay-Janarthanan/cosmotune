package com.cosmotune.video.library

/** A video a link asks Cosmotune to open, and where to start (null: the beginning, or where it was left). */
data class OpenVideo(val videoId: String, val startMs: Long?)

/**
 * The links that open a video in Cosmotune's Video mode: the browser add-on's `cosmotune://video?url=<YouTube address>` (also
 * `cosmotune://video?v=<id>`), and a YouTube address itself (`watch?v=`, `youtu.be/`, `shorts/`, `live/`, `embed/`) given to the
 * desktop app. A `t=` (seconds, or `1h2m3s`) becomes the start.
 */
object VideoOpenLinks {
    fun parse(link: String): OpenVideo? {
        val text = link.trim()
        if (text.startsWith("cosmotune://video", ignoreCase = true)) {
            val query = params(text.substringAfter('?', ""))
            query["url"]?.let { inner -> return youtube(inner) }
            val id = query["v"]?.takeIf(::isVideoId) ?: return null
            return OpenVideo(id, query["t"]?.let(::seconds)?.times(1000))
        }
        return youtube(text)
    }

    private fun youtube(url: String): OpenVideo? {
        val withScheme = if ("://" in url) url else "https://$url"
        val scheme = withScheme.substringBefore("://").lowercase()
        if (scheme != "https" && scheme != "http") return null
        val rest = withScheme.substringAfter("://")
        val host = rest.substringBefore('/').substringBefore('?').substringBefore('#').lowercase().removePrefix("www.").removePrefix("m.")
        val path = rest.substringAfter('/', "").substringBefore('?').substringBefore('#')
        val query = params(rest.substringAfter('?', "").substringBefore('#'))
        val segments = path.split('/').filter { it.isNotEmpty() }
        val id = when {
            host == "youtu.be" -> segments.firstOrNull()
            host == "youtube.com" || host == "youtube-nocookie.com" -> when (segments.firstOrNull()) {
                "watch" -> query["v"]
                "shorts", "live", "embed", "v" -> segments.getOrNull(1)
                else -> null
            }
            else -> null
        }?.takeIf(::isVideoId) ?: return null
        return OpenVideo(id, (query["t"] ?: query["start"])?.let(::seconds)?.times(1000))
    }

    private fun params(query: String): Map<String, String> = query.split('&').filter { '=' in it }.associate {
        val (k, v) = it.split('=', limit = 2)
        k to decode(v)
    }

    private fun decode(s: String): String {
        val out = StringBuilder()
        val bytes = ArrayList<Byte>()
        var i = 0
        fun flush() {
            if (bytes.isNotEmpty()) { out.append(bytes.toByteArray().decodeToString()); bytes.clear() }
        }
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hex = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) { bytes.add(hex.toByte()); i += 3; continue }
            }
            flush()
            out.append(if (c == '+') ' ' else c)
            i++
        }
        flush()
        return out.toString()
    }

    /** "90", "90s", "1m30s", "1h2m3s" in seconds. */
    private fun seconds(t: String): Long? {
        t.toLongOrNull()?.let { return it.takeIf { s -> s >= 0 } }
        val m = Regex("""^(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?$""").matchEntire(t.lowercase()) ?: return null
        if (m.value.isEmpty()) return null
        val (h, min, s) = m.destructured
        return (h.toLongOrNull() ?: 0) * 3600 + (min.toLongOrNull() ?: 0) * 60 + (s.toLongOrNull() ?: 0)
    }

    private fun isVideoId(s: String): Boolean = s.length == 11 && s.all { it.isLetterOrDigit() || it == '_' || it == '-' }
}
