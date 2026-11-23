package com.cosmotune.video.source.newpipe

/**
 * Family-friendly mode: every request Video's source sends to YouTube carries `YouTube-Restrict: Strict`, the header Google's
 * own Restricted Mode for schools and homes reads (videos for grown-ups and their comments are left out of search, Up next and
 * channels, and do not play).
 */
object VideoRestrict {
    @Volatile var strict: Boolean = false

    const val HEADER = "YouTube-Restrict"

    /** Whether a request to [host] gets the header. */
    fun applies(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return strict && (h == "youtube.com" || h.endsWith(".youtube.com") || h == "youtubei.googleapis.com" || h == "youtube.googleapis.com" ||
            h.endsWith("youtube-nocookie.com"))
    }
}
