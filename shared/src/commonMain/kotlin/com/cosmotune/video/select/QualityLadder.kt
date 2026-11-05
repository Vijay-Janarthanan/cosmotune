package com.cosmotune.video.select

import com.cosmotune.video.model.StreamSet
import com.cosmotune.video.model.VideoStream
import com.cosmotune.video.player.EngineCapabilities
import com.cosmotune.video.player.QualityOption
import com.cosmotune.video.settings.CodecPreference

/** One rung of the ladder with the stream that plays it (the selector needs the stream; the settings sheet only the option). */
internal data class Rung(val option: QualityOption, val stream: VideoStream)

/**
 * The quality ladder (spec 6.4, "Ladder"; pure): the heights the settings sheet and Auto choose between, top to bottom.
 *
 * - One rung per distinct height of the video-only streams (of the muxed streams when there are no video-only ones, or
 *   none that the device decodes: a muxed 360p is better than no picture), each with its best frame rate: `2160p60`,
 *   `1440p60`, `1080p60`, `720p60`, `480p`, `360p`, `240p`, `144p`.
 * - A rung is kept only if some decoder of the engine covers its codec at that size and frame rate ([CodecOrder.covers]);
 *   HDR streams only on an HDR display, and then they win their height (label `1080p60 HDR`).
 * - The rung's codec is [CodecOrder.choose]'s, so the label, the option and the stream that plays always agree.
 */
object QualityLadder {

    /**
     * The ladder of [streams] on an engine with [caps]. [codec] is the preferred-codec setting: it decides which stream
     * stands for a height, so pass the user's setting to get the ladder the selector plays from (the default, Auto, is
     * the order of [CodecOrder]). Empty for a live set or when nothing is decodable.
     */
    fun of(streams: StreamSet, caps: EngineCapabilities, codec: CodecPreference = CodecPreference.AUTO): List<QualityOption> =
        rungs(streams, caps, codec).map { it.option }

    /**
     * The quality height of [stream]: the smallest standard height (144, 240, 360, 480, 720, 1080, 1440, 2160, 4320) whose
     * 16:9 frame holds the stream in either orientation. So 1920 x 1080, 1440 x 1080 (4:3), 1920 x 804 (2.39:1) and a
     * Short of 1080 x 1920 are all 1080; a stream whose width is unknown ([VideoStream.width] of 0) counts by its height.
     */
    fun heightOf(stream: VideoStream): Int = nominalHeight(stream.width, stream.height)

    internal fun rungs(streams: StreamSet, caps: EngineCapabilities, codec: CodecPreference): List<Rung> =
        rungsOf(streams.videoOnly, caps, codec).ifEmpty { rungsOf(streams.muxed, caps, codec) }

    private fun rungsOf(pool: List<VideoStream>, caps: EngineCapabilities, codec: CodecPreference): List<Rung> {
        return pool
            .filter { heightOf(it) > 0 && (caps.hdrDisplay || !it.hdr) && CodecOrder.covers(caps, it) }
            .groupBy { heightOf(it) }
            .map { (height, same) ->
                val stream = checkNotNull(CodecOrder.choose(same, caps, codec))
                Rung(optionOf(stream, height), stream)
            }
            .sortedByDescending { it.option.height }
    }

    internal fun optionOf(stream: VideoStream, height: Int = heightOf(stream)) =
        QualityOption(height, stream.fps, stream.codec, stream.hdr, QualityOption.labelOf(height, stream.fps, stream.hdr))

    /** Standard heights with the width of their 16:9 frame, smallest first. */
    private val CLASSES = listOf(
        144 to 256, 240 to 426, 360 to 640, 480 to 854, 720 to 1280, 1080 to 1920, 1440 to 2560, 2160 to 3840, 4320 to 7680,
    )

    /** 8 % of slack on the width, so 4096 x 2160 is still 2160. */
    private const val WIDTH_SLACK_PERCENT = 108

    private fun nominalHeight(width: Int, height: Int): Int {
        if (height <= 0) return 0
        if (width <= 0) return height
        val long = maxOf(width, height)
        val short = minOf(width, height)
        for ((classHeight, classWidth) in CLASSES) {
            if (short <= classHeight && long.toLong() * 100 <= classWidth.toLong() * WIDTH_SLACK_PERCENT) return classHeight
        }
        return short
    }
}
