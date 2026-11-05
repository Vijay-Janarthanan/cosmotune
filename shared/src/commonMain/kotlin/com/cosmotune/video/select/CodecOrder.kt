package com.cosmotune.video.select

import com.cosmotune.video.model.VideoCodec
import com.cosmotune.video.model.VideoStream
import com.cosmotune.video.player.DecoderCaps
import com.cosmotune.video.player.EngineCapabilities
import com.cosmotune.video.settings.CodecPreference

/**
 * Which codec plays a rung (spec 6.4, "Codec"; pure). The order, after the preferred codec when the device decodes it
 * there: AV1 if decoded in hardware, else VP9 if decoded in hardware, else H.264 if decoded in hardware, else H.264, else
 * VP9, else AV1 (software). HDR streams are only offered when the display reports HDR ([EngineCapabilities.hdrDisplay]).
 */
object CodecOrder {

    /**
     * Whether some decoder of [caps] covers [stream]: its codec, at its size and at its frame rate. The size is compared
     * without regard to orientation (a 1080 x 1920 Short fits a decoder that reports 1920 x 1080), and a stream with
     * a frame rate of 0 or less (unknown) is covered at any rate.
     */
    fun covers(caps: EngineCapabilities, stream: VideoStream): Boolean =
        caps.video.any { it.codec == stream.codec && fits(it, stream) }

    /** Whether [stream] is covered by a decoder of [caps] that runs in hardware. */
    fun hardware(caps: EngineCapabilities, stream: VideoStream): Boolean =
        caps.video.any { it.hardware && it.codec == stream.codec && fits(it, stream) }

    /**
     * The stream to play out of [candidates] (all of one rung's height, all decodable): the [preference] when a candidate
     * has that codec, else the order above. Within the codec the HDR streams come first on an HDR display and the
     * standard ones otherwise, then the best frame rate, then the highest bitrate. Null when [candidates] is empty.
     *
     * With the preference, the preferred codec's own best frame rate is taken even if another codec has a higher one
     * (a user who picked H.264 wants H.264); without it, only the best frame rate of the height is considered, so the
     * rung's label ("1080p60") is the one played.
     */
    fun choose(candidates: List<VideoStream>, caps: EngineCapabilities, preference: CodecPreference = CodecPreference.AUTO): VideoStream? {
        if (candidates.isEmpty()) return null
        val wanted = codecOf(preference)
        if (wanted != null) {
            val same = candidates.filter { it.codec == wanted }
            if (same.isNotEmpty()) return best(same, caps)
        }
        val pool = hdrPool(candidates, caps)
        val topFps = pool.maxOf { it.fps }
        val top = pool.filter { it.fps == topFps }
        val hardwareFirst: List<(VideoStream) -> Boolean> = listOf(
            { it.codec == VideoCodec.AV1 && hardware(caps, it) },
            { it.codec == VideoCodec.VP9 && hardware(caps, it) },
            { it.codec == VideoCodec.H264 && hardware(caps, it) },
            { it.codec == VideoCodec.H264 },
            { it.codec == VideoCodec.VP9 },
            { it.codec == VideoCodec.AV1 },
        )
        for (step in hardwareFirst) {
            val hit = top.filter(step)
            if (hit.isNotEmpty()) return hit.maxBy { it.bitrate }
        }
        return top.maxBy { it.bitrate }
    }

    /** The codec a [CodecPreference] names, or null for Auto. */
    fun codecOf(preference: CodecPreference): VideoCodec? = when (preference) {
        CodecPreference.AUTO -> null
        CodecPreference.AV1 -> VideoCodec.AV1
        CodecPreference.VP9 -> VideoCodec.VP9
        CodecPreference.H264 -> VideoCodec.H264
    }

    private fun best(list: List<VideoStream>, caps: EngineCapabilities): VideoStream {
        val pool = hdrPool(list, caps)
        val topFps = pool.maxOf { it.fps }
        return pool.filter { it.fps == topFps }.maxBy { it.bitrate }
    }

    /** HDR streams when the display reports HDR and there are any; else the standard ones (HDR ones only if nothing else is left). */
    private fun hdrPool(list: List<VideoStream>, caps: EngineCapabilities): List<VideoStream> {
        val hdr = list.filter { it.hdr }
        val sdr = list.filter { !it.hdr }
        return if (caps.hdrDisplay && hdr.isNotEmpty()) hdr else sdr.ifEmpty { hdr }
    }

    private fun fits(decoder: DecoderCaps, stream: VideoStream): Boolean {
        val streamLong = maxOf(stream.width, stream.height)
        val streamShort = minOf(stream.width, stream.height).coerceAtLeast(0)
        val decoderLong = maxOf(decoder.maxWidth, decoder.maxHeight)
        val decoderShort = minOf(decoder.maxWidth, decoder.maxHeight)
        return streamLong <= decoderLong && streamShort <= decoderShort && stream.fps <= decoder.maxFps
    }
}
