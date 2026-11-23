package com.cosmotune.watch

import com.cosmotune.watch.model.SeekPreviews

/**
 * Draws seek thumbnails for a video file on this device: the frames at [timesMs], each scaled to fit [frameWidth] x
 * [frameHeight] (black around it), laid out [columns] to a row into one JPEG image at [outPath]. False when the file cannot be
 * read.
 */
fun interface WatchFrameGrabber {
    suspend fun sheet(path: String, timesMs: List<Long>, columns: Int, frameWidth: Int, frameHeight: Int, outPath: String): Boolean
}

/** Seek thumbnails for files on this device (a download, a folder of films) that bring none of their own. */
object LocalPreviews {
    const val FRAME_WIDTH = 160
    const val FRAME_HEIGHT = 90
    const val COLUMNS = 10
    private const val MAX_FRAMES = 100
    private const val MIN_INTERVAL_MS = 5_000L

    /** How many frames of a [durationMs] video, and how far apart: never more than 100 nor closer than 5 s, on whole seconds. */
    fun plan(durationMs: Long): SeekPreviews? {
        if (durationMs < 30_000) return null
        val interval = maxOf(MIN_INTERVAL_MS, (durationMs / MAX_FRAMES + 999) / 1000 * 1000)
        val frames = (durationMs / interval).toInt().coerceIn(1, MAX_FRAMES)
        val rows = (frames + COLUMNS - 1) / COLUMNS
        return SeekPreviews(emptyList(), FRAME_WIDTH, FRAME_HEIGHT, COLUMNS, rows, interval, frames)
    }

    /** The times each frame is taken at: the middle of its stretch, so the first is not a black opening frame. */
    fun times(plan: SeekPreviews): List<Long> = List(plan.frames) { it * plan.intervalMs + plan.intervalMs / 2 }

    /** The file path of a copy on this device ([url] a `file:` address or a plain path), or null for anything else. */
    fun pathOf(url: String): String? = when {
        url.startsWith("file:") -> url.removePrefix("file://").removePrefix("file:").let { p ->
            decode(if (Regex("^/[a-zA-Z]:/").containsMatchIn(p)) p.drop(1) else p)
        }
        url.startsWith("/") || Regex("^[a-zA-Z]:[/\\\\]").containsMatchIn(url) -> url
        else -> null
    }

    private fun decode(path: String): String {
        if ('%' !in path) return path
        val out = StringBuilder()
        val bytes = mutableListOf<Byte>()
        var i = 0
        fun flush() { if (bytes.isNotEmpty()) { out.append(bytes.toByteArray().decodeToString()); bytes.clear() } }
        while (i < path.length) {
            val c = path[i]
            if (c == '%' && i + 3 <= path.length) {
                val hex = path.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) { bytes += hex.toByte(); i += 3; continue }
            }
            flush(); out.append(c); i++
        }
        flush()
        return out.toString()
    }
}
