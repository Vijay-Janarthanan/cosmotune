package com.cosmotune.video.player

import com.cosmotune.video.model.AudioStream
import com.cosmotune.video.model.StreamSet
import com.cosmotune.video.model.VideoCodec
import com.cosmotune.video.model.VideoStream
import com.cosmotune.video.settings.VideoSettings

/**
 * One rung of the quality ladder (spec 6.4): a height with its best frame rate, the codec chosen for it and HDR, and the
 * label the settings sheet shows ("1080p60", "2160p HDR"; see [labelOf]).
 */
data class QualityOption(val height: Int, val fps: Int, val codec: VideoCodec, val hdr: Boolean, val label: String) {
    companion object {
        /**
         * The ladder's label (spec 6.4, 6.5): the height and "p", the frame rate when above 30 ("1080p60"), and " HDR"
         * for an HDR stream ("2160p60 HDR").
         */
        fun labelOf(height: Int, fps: Int, hdr: Boolean): String = buildString {
            append(height).append('p')
            if (fps > 30) append(fps)
            if (hdr) append(" HDR")
        }
    }
}

/**
 * The selector's answer (spec 6.4): the rung chosen ([option], null when nothing has a height, as for HLS), what the
 * engine loads, the concrete streams and the whole ladder the device can play.
 */
data class Selection(
    val option: QualityOption?,
    val input: MediaInput,
    val video: VideoStream?,
    val audio: AudioStream?,
    val ladder: List<QualityOption>,
)

/**
 * Picks the streams to play (spec 6.4; pure). [manual] is the rung the user picked, or null for Auto; [metered] is mobile
 * data; [bandwidthKbps] the measured bandwidth (null in the first 5 s). The real one is `StreamSelector` (plan task Y-5).
 */
interface StreamChooser {
    /** The selection for [streams] on an engine with [caps] under [settings]. */
    fun choose(
        streams: StreamSet,
        caps: EngineCapabilities,
        settings: VideoSettings,
        metered: Boolean,
        manual: QualityOption?,
        audioTrackId: String?,
        bandwidthKbps: Int?,
        durationMs: Long,
    ): Selection
}

/**
 * The simplest chooser, for the fake graph and tests (plan task Y-2): the first video-only stream (else the first muxed
 * one) and the first audio stream, as [MediaInput.Progressive]; an HLS address when there is no stream at all. The
 * ladder lists each video-only height once with its best frame rate, top to bottom. Stateless: write
 * `FirstStreamChooser()` or use the companion, `FirstStreamChooser`, which is the same chooser.
 */
class FirstStreamChooser : StreamChooser {
    /** The shared instance: `FirstStreamChooser` works wherever a [StreamChooser] is wanted. */
    companion object : StreamChooser by FirstStreamChooser()

    override fun choose(
        streams: StreamSet,
        caps: EngineCapabilities,
        settings: VideoSettings,
        metered: Boolean,
        manual: QualityOption?,
        audioTrackId: String?,
        bandwidthKbps: Int?,
        durationMs: Long,
    ): Selection {
        val ladder = streams.videoOnly
            .groupBy { it.height }
            .map { (_, sameHeight) -> sameHeight.maxBy { it.fps }.toOption() }
            .sortedWith(compareByDescending<QualityOption> { it.height }.thenByDescending { it.fps })
        val video = streams.videoOnly.firstOrNull() ?: streams.muxed.firstOrNull()
        val audio = if (video?.hasAudio == true) null else streams.audio.firstOrNull()
        val hls = streams.hlsUrl
        val input = if (video == null && audio == null && hls != null) MediaInput.Hls(hls) else MediaInput.Progressive(video, audio)
        return Selection(option = video?.toOption(), input = input, video = video, audio = audio, ladder = ladder)
    }

    private fun VideoStream.toOption() = QualityOption(height, fps, codec, hdr, QualityOption.labelOf(height, fps, hdr))
}
