package com.cosmotune.watch

import com.cosmotune.watch.model.Stream
import kotlin.math.roundToLong

/** Stream picks keep the resolver's ranking and distinguish providers with identical display names. */
data class WatchStreamFilter(val quality: String? = null, val providerId: String? = null) {
    fun applyTo(streams: List<Stream>): List<Stream> = streams.filter {
        (quality == null || it.displayQuality == quality) &&
            (providerId == null || it.source.pluginId == providerId)
    }

    companion object {
        fun qualities(streams: List<Stream>): List<String> = streams.map { it.displayQuality }.distinct()
    }
}

private val Stream.displayQuality: String get() = qualityBadge ?: "Unknown"

fun Stream.watchMetadata(playingDurationMs: Long? = null, titleRuntimeMin: Int? = null): String {
    if (!isPlayable) return "Opens in your browser"
    val bytes = sizeBytes?.takeIf { it > 0 }
    val size = when {
        bytes == null -> "Size unknown"
        bytes >= (1L shl 30) -> {
            val tenths = (bytes.toDouble() * 10 / (1L shl 30)).roundToLong()
            "${tenths / 10}.${tenths % 10} GB"
        }
        bytes >= (1L shl 20) -> "${(bytes.toDouble() / (1L shl 20)).roundToLong()} MB"
        bytes >= 1024 -> "${(bytes.toDouble() / 1024).roundToLong()} KB"
        else -> "$bytes B"
    }
    val seconds = (playingDurationMs?.takeIf { it > 0 } ?: durationMs?.takeIf { it > 0 })?.div(1000)
    val runtime = when {
        isLive -> "Live"
        seconds != null -> {
            val minutes = seconds / 60
            val clock = "${(minutes % 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}"
            if (minutes >= 60) "${minutes / 60}:$clock" else clock
        }
        titleRuntimeMin != null && titleRuntimeMin > 0 -> "Title runtime ${titleRuntimeMin} min"
        else -> "Duration unknown"
    }
    return "$size · $runtime"
}

const val WATCH_SKIP_MS = 30_000L
