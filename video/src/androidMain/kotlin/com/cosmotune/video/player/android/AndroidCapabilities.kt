package com.cosmotune.video.player.android

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import com.cosmotune.video.model.VideoCodec
import com.cosmotune.video.player.DecoderCaps
import com.cosmotune.video.player.EngineCapabilities

/**
 * What this phone decodes and how the Android engine wants its input (spec 2.5, 6.2 "Capabilities", 6.4).
 *
 * For H.264, VP9 and AV1, every decoder `MediaCodecUtil.getDecoderInfos(mime, false, false)` lists is asked, in the order of
 * [RUNGS] (2160p60, 2160p30, 1440p60, 1080p60, 1080p30, 720p60, 720p30), for the highest size and frame rate
 * `isVideoSizeAndRateSupported` accepts; its `hardwareAccelerated` flag comes with it (exact from API 29). When that highest rung
 * is a 30 fps one, the highest 60 fps rung below it is listed too (a decoder good for 2160p30 and 1440p60 says both), since the
 * selector keeps a rung when "some decoder covers its codec at that size and frame rate". Opus always plays; the display's HDR
 * capability; the display's height is the shorter side of its current bounds in pixels (the video's height when the phone is
 * turned); the engine plays the side-loaded DASH manifest; no Auto ceiling of its own.
 */
@OptIn(UnstableApi::class)
object AndroidCapabilities {

    private class Rung(val width: Int, val height: Int, val fps: Int)

    /** The rungs asked, highest first (spec 2.5). */
    private val RUNGS = listOf(
        Rung(3840, 2160, 60), Rung(3840, 2160, 30), Rung(2560, 1440, 60), Rung(1920, 1080, 60), Rung(1920, 1080, 30),
        Rung(1280, 720, 60), Rung(1280, 720, 30),
    )

    private val CODECS = listOf(
        VideoCodec.H264 to MimeTypes.VIDEO_H264,
        VideoCodec.VP9 to MimeTypes.VIDEO_VP9,
        VideoCodec.AV1 to MimeTypes.VIDEO_AV1,
    )

    /** The decoder list, asked once per process (the codec list does not change while the app runs). */
    @Volatile
    private var decoders: List<DecoderCaps>? = null

    /** The capabilities now: the decoders (asked once), the display's HDR and height (read each time: a foldable can change). */
    fun of(context: Context): EngineCapabilities {
        val display = context.applicationContext.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        return EngineCapabilities(
            video = decoders ?: queryDecoders().also { decoders = it },
            opus = true,
            hdrDisplay = display?.isHdr == true,
            displayHeightPx = shorterSide(display),
            sideloadedDash = true,
            autoCeilingHeight = null,
        )
    }

    private fun queryDecoders(): List<DecoderCaps> {
        val result = ArrayList<DecoderCaps>()
        for ((codec, mime) in CODECS) {
            val infos = try {
                MediaCodecUtil.getDecoderInfos(mime, /* secure = */ false, /* tunneling = */ false)
            } catch (_: MediaCodecUtil.DecoderQueryException) {
                emptyList()
            }
            for (info in infos) result += capsOf(codec, info)
        }
        return result
    }

    private fun capsOf(codec: VideoCodec, info: MediaCodecInfo): List<DecoderCaps> {
        val highest = RUNGS.firstOrNull { supports(info, it) } ?: return emptyList()
        val caps = DecoderCaps(codec, highest.width, highest.height, highest.fps, hardware = info.hardwareAccelerated)
        if (highest.fps >= 60) return listOf(caps)
        val fast = RUNGS.firstOrNull { it.fps >= 60 && it.height < highest.height && supports(info, it) } ?: return listOf(caps)
        return listOf(caps, DecoderCaps(codec, fast.width, fast.height, fast.fps, hardware = info.hardwareAccelerated))
    }

    private fun supports(info: MediaCodecInfo, rung: Rung): Boolean =
        runCatching { info.isVideoSizeAndRateSupported(rung.width, rung.height, rung.fps.toDouble()) }.getOrDefault(false)

    @Suppress("DEPRECATION") // getRealSize: the display's current size in pixels, from any context (no window needed)
    private fun shorterSide(display: Display?): Int {
        if (display == null) return 0
        val size = Point()
        display.getRealSize(size)
        return minOf(size.x, size.y)
    }
}
