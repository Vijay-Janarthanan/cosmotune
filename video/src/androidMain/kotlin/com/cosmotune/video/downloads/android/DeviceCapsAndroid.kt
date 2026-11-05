package com.cosmotune.video.downloads.android

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import com.cosmotune.video.downloads.DeviceCaps
import com.cosmotune.video.downloads.DownloadPlanner
import com.cosmotune.video.model.VideoCodec

/**
 * What this phone plays back from a download (plan task Y-24, step 5): from `MediaCodecList`, the codecs with a hardware
 * decoder (H.264 always: its software decoder plays 1080p everywhere) and the highest rung (height, frame rate) a hardware
 * decoder supports. A device that decodes 2160p only at 30 fps but 1080p at 60 is given 1080p60: a 2160p60 download would
 * not play there, and 60 fps from 1080p is the more useful rung. The Transformer writes MP4 only, so no WebM.
 */
object DeviceCapsAndroid {
    private val MIMES = linkedMapOf(VideoCodec.H264 to "video/avc", VideoCodec.VP9 to "video/x-vnd.on2.vp9", VideoCodec.AV1 to "video/av01")

    /** What a phone with no usable answer from the codec list gets. */
    val FALLBACK = DeviceCaps(maxHeight = 720, maxFps = 30, codecs = setOf(VideoCodec.H264))

    /** The caps of this device; reads the codec list (a few milliseconds): call it once and keep it. */
    fun of(): DeviceCaps = runCatching { read() }.getOrNull() ?: FALLBACK

    private fun read(): DeviceCaps? {
        val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
        var best: Pair<Int, Int>? = null
        val codecs = mutableSetOf<VideoCodec>()
        for ((codec, mime) in MIMES) {
            val all = decoders.filter { info -> info.supportedTypes.any { it.equals(mime, ignoreCase = true) } && !isAlias(info) }
            val usable = all.filter(::isHardware).ifEmpty { if (codec == VideoCodec.H264) all else emptyList() }
            val rung = usable.mapNotNull { rungOf(it, mime, software = !isHardware(it)) }.maxWithOrNull(compareBy({ it.first }, { it.second })) ?: continue
            codecs += codec
            if (best == null || compareValuesBy(rung, best!!, { it.first }, { it.second }) > 0) best = rung
        }
        val (height, fps) = best ?: return null
        return DeviceCaps(maxHeight = height, maxFps = fps, codecs = codecs)
    }

    /** The best rung of one decoder: the tallest at 60 fps when it reaches 1080p (or the 30 fps height), else the tallest at 30. */
    private fun rungOf(info: MediaCodecInfo, mime: String, software: Boolean): Pair<Int, Int>? {
        val video = runCatching { info.getCapabilitiesForType(mime).videoCapabilities }.getOrNull() ?: return null
        val ladder = if (software) DownloadPlanner.LADDER.filter { it <= 1080 } else DownloadPlanner.LADDER
        fun tallest(fps: Double) = ladder.firstOrNull { h -> runCatching { video.areSizeAndRateSupported(widthOf(h), h, fps) }.getOrDefault(false) }
        val at30 = tallest(30.0) ?: return null
        val at60 = tallest(60.0)
        return if (at60 != null && at60 >= minOf(at30, 1080)) at60 to 60 else at30 to 30
    }

    private fun widthOf(height: Int): Int = (height * 16 / 9 + 1) / 2 * 2

    private fun isHardware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.isHardwareAccelerated
        else info.name.let { !it.startsWith("OMX.google.") && !it.startsWith("c2.android.") && !it.contains(".sw.") }

    private fun isAlias(info: MediaCodecInfo): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isAlias
}
