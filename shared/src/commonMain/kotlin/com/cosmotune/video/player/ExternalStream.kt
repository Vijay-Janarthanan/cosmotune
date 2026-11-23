package com.cosmotune.video.player

import com.cosmotune.video.model.StreamSet

/** One address another player (VLC, mpv, Kodi, a browser) can open on its own: the address and its type. */
data class ExternalStream(val url: String, val mimeType: String)

object ExternalStreams {
    /**
     * What to hand another player: a live stream's HLS address, else the sharpest stream with picture and sound in one file (another
     * player cannot join the separate picture and sound streams), else the HLS address when there is one, else nothing.
     */
    fun pick(streams: StreamSet, live: Boolean): ExternalStream? {
        val hls = streams.hlsUrl?.takeIf { it.isNotBlank() }?.let { ExternalStream(it, "application/x-mpegURL") }
        if (live && hls != null) return hls
        val muxed = streams.muxed.filter { it.url.isNotBlank() }.maxWithOrNull(compareBy({ it.height }, { it.bitrate }))
        if (muxed != null) return ExternalStream(muxed.url, muxed.mimeType.substringBefore(';').ifBlank { "video/*" })
        return hls
    }
}
