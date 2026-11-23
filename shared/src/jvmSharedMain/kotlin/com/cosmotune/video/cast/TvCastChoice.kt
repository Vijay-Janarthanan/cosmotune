package com.cosmotune.video.cast

import com.cosmotune.video.model.StreamSet
import com.cosmotune.video.model.VideoCodec
import com.cosmotune.video.model.VideoStream

/** What a TV is handed for a YouTube video: a playlist address it fetches itself, or one file with sound inside, relayed by this device. */
sealed interface TvCastChoice {
    /** What the sheet shows ("720p"). */
    val label: String

    data class Hls(val url: String, override val label: String = "Live") : TvCastChoice
    data class Muxed(val stream: VideoStream, val headers: Map<String, String>, override val label: String) : TvCastChoice
}

object TvCast {
    const val NOTHING_PLAYABLE = "This video has no copy a TV can play. Watch it on this device."

    /** Largest picture sent to a TV: files with sound inside stop at 720p in practice; this only keeps a rare 4K muxed copy out. */
    private const val MAX_HEIGHT = 1080

    /**
     * Picks what to cast: the HLS address where the source offers one, else the best file with picture and sound together
     * (MP4 with H.264 first, the formats TVs play; itag 22 is 720p, 18 is 360p). Split picture and sound (DASH) cannot be
     * cast, so when only those exist the answer is null. The label tells the listener which quality they get.
     */
    fun choose(streams: StreamSet?): TvCastChoice? {
        streams ?: return null
        streams.hlsUrl?.takeIf { it.startsWith("http") }?.let { return TvCastChoice.Hls(it) }
        val best = streams.muxed
            .filter { it.hasAudio && it.url.startsWith("http") && it.height <= MAX_HEIGHT }
            .sortedWith(
                compareByDescending<VideoStream> { it.mimeType.contains("mp4") }
                    .thenByDescending { it.codec == VideoCodec.H264 }
                    .thenByDescending { it.height }
                    .thenByDescending { it.bitrate },
            )
            .firstOrNull() ?: return null
        return TvCastChoice.Muxed(best, streams.requestHeaders, "${best.height}p")
    }

    fun mimeOf(choice: TvCastChoice): String = when (choice) {
        is TvCastChoice.Hls -> "application/vnd.apple.mpegurl"
        is TvCastChoice.Muxed -> choice.stream.mimeType.substringBefore(';').trim().ifEmpty { "video/mp4" }
    }
}
