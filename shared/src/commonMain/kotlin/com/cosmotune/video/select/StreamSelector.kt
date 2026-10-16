package com.cosmotune.video.select

import com.cosmotune.video.model.AudioCodec
import com.cosmotune.video.model.AudioStream
import com.cosmotune.video.model.AudioTrackKind
import com.cosmotune.video.model.StreamSet
import com.cosmotune.video.model.VideoCodec
import com.cosmotune.video.model.VideoStream
import com.cosmotune.video.player.EngineCapabilities
import com.cosmotune.video.player.MediaInput
import com.cosmotune.video.player.QualityOption
import com.cosmotune.video.player.Selection
import com.cosmotune.video.player.StreamChooser
import com.cosmotune.video.settings.VideoSettings

/**
 * Picks the streams to play (spec 6.4; pure, stateless). The steps, each with its test in `StreamSelectorTest`:
 *
 * 1. **The rung.** The user's pick ([QualityOption] `manual`) when there is one: the highest rung at or below its height.
 *    Otherwise Auto: the lowest of the network's setting ([VideoSettings.wifiQuality] or [VideoSettings.meteredQuality];
 *    null is Auto and the default on both networks, Q7: no cap), the display's height and
 *    [EngineCapabilities.autoCeilingHeight]; and, once the bandwidth is known, the highest rung whose bitrate (video and
 *    audio) times 1.4 fits it.
 * 2. **The codec** at that rung: [CodecOrder].
 * 3. **The audio.** The track asked for, else the original, else the one in the content language, else the first; in it,
 *    Opus when the engine plays Opus and its bitrate is within 20 % of the best AAC, else AAC. The same on every network.
 * 4. **The input.** A live set gives [MediaInput.Hls]; a muxed stream [MediaInput.Progressive] alone; two streams with init
 *    and index ranges on an engine that takes side-loaded DASH a [MediaInput.Dash] manifest; anything else
 *    [MediaInput.Progressive] (the desktop's EDL, spec 6.3).
 */
class StreamSelector : StreamChooser {

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
        val hls = streams.hlsUrl
        if (hls != null && streams.videoOnly.isEmpty()) {
            return Selection(option = null, input = MediaInput.Hls(hls), video = null, audio = null, ladder = emptyList())
        }

        val rungs = QualityLadder.rungs(streams, caps, settings.codec)
        val ladder = rungs.map { it.option }
        val audioStream = chooseAudio(streams.audio, caps, audioTrackId, settings.contentLanguage)
        val rung = targetRung(rungs, caps, settings, metered, manual, bandwidthKbps, audioStream)

        val video: VideoStream?
        val option: QualityOption?
        if (rung != null) {
            video = rung.stream
            option = rung.option
        } else {
            // Nothing the engine says it decodes: still hand over the smallest picture, so the engine can try and report its own error.
            video = undecodableFallback(streams)
            option = video?.let { QualityLadder.optionOf(it) }
        }

        val audio = if (video != null && video.hasAudio) null else audioStream
        return Selection(option = option, input = inputFor(video, audio, caps, durationMs), video = video, audio = audio, ladder = ladder)
    }

    // ------------------------------------------------------------------ the rung

    private fun targetRung(
        rungs: List<Rung>,
        caps: EngineCapabilities,
        settings: VideoSettings,
        metered: Boolean,
        manual: QualityOption?,
        bandwidthKbps: Int?,
        audio: AudioStream?,
    ): Rung? {
        if (rungs.isEmpty()) return null
        if (manual != null) return rungs.firstOrNull { it.option.height <= manual.height } ?: rungs.last()

        val network = if (metered) settings.meteredQuality else settings.wifiQuality
        val ceiling = listOfNotNull(network, caps.displayHeightPx.takeIf { it > 0 }, caps.autoCeilingHeight?.takeIf { it > 0 }).minOrNull()
        val allowed = if (ceiling == null) rungs else rungs.filter { it.option.height <= ceiling }.ifEmpty { listOf(rungs.last()) }

        if (bandwidthKbps == null || bandwidthKbps <= 0) return allowed.first()
        return allowed.firstOrNull { fits(it.stream, audio, bandwidthKbps) } ?: allowed.last()
    }

    /** Whether the video and audio bitrate, times 1.4, is within the measured bandwidth. */
    private fun fits(video: VideoStream, audio: AudioStream?, bandwidthKbps: Int): Boolean {
        val bits = video.bitrate.toLong().coerceAtLeast(0) + if (video.hasAudio) 0L else (audio?.bitrate?.toLong() ?: 0L).coerceAtLeast(0)
        return bits * 14 <= bandwidthKbps.toLong() * 10_000
    }

    /** The smallest standard-range stream (H.264 first among equals) when no decoder covers anything. */
    private fun undecodableFallback(streams: StreamSet): VideoStream? {
        val pool = streams.videoOnly.ifEmpty { streams.muxed }.filter { QualityLadder.heightOf(it) > 0 }
        val standard = pool.filter { !it.hdr }.ifEmpty { pool }
        return standard.minWithOrNull(compareBy<VideoStream> { QualityLadder.heightOf(it) }.thenBy { if (it.codec == VideoCodec.H264) 0 else 1 })
    }

    // ------------------------------------------------------------------ the audio

    private fun chooseAudio(audio: List<AudioStream>, caps: EngineCapabilities, audioTrackId: String?, contentLanguage: String?): AudioStream? {
        if (audio.isEmpty()) return null
        val tracks = audio.groupBy { it.trackId }.values.toList()
        val track = audioTrackId?.let { id -> tracks.firstOrNull { it.first().trackId == id } }
            ?: tracks.firstOrNull { t -> t.any { it.trackKind == AudioTrackKind.ORIGINAL } }
            ?: contentLanguage?.let { lang -> tracks.firstOrNull { t -> t.any { sameLanguage(it.language, lang) } } }
            ?: tracks.first()
        return opusOrAac(track, caps)
    }

    /** Opus when the engine plays it and it is not more than 20 % below the best AAC (or there is no AAC), else the best AAC. */
    private fun opusOrAac(track: List<AudioStream>, caps: EngineCapabilities): AudioStream? {
        val aac = track.filter { it.codec == AudioCodec.AAC }.maxByOrNull { it.bitrate }
        val opus = track.filter { it.codec == AudioCodec.OPUS }.maxByOrNull { it.bitrate }
        if (caps.opus && opus != null && (aac == null || opus.bitrate.toLong() * 10 >= aac.bitrate.toLong() * 8)) return opus
        if (aac != null) return aac
        return track.filter { caps.opus || it.codec != AudioCodec.OPUS }.maxByOrNull { it.bitrate } ?: track.maxByOrNull { it.bitrate }
    }

    /** Whether two language tags name the same language: "en" and "en-US" do. */
    private fun sameLanguage(a: String?, b: String): Boolean {
        if (a == null) return false
        return a.substringBefore('-').substringBefore('_').equals(b.substringBefore('-').substringBefore('_'), ignoreCase = true)
    }

    // ------------------------------------------------------------------ the input

    private fun inputFor(video: VideoStream?, audio: AudioStream?, caps: EngineCapabilities, durationMs: Long): MediaInput {
        if (video == null) return MediaInput.Progressive(null, audio)
        if (video.hasAudio) return MediaInput.Progressive(video, null)
        if (caps.sideloadedDash && audio != null && video.init != null && video.index != null && audio.init != null && audio.index != null) {
            return MediaInput.Dash(DashManifest.build(video, audio, durationMs), video, audio)
        }
        return MediaInput.Progressive(video, audio)
    }
}
