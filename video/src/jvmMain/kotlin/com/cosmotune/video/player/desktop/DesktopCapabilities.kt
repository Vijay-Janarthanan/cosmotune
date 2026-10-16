package com.cosmotune.video.player.desktop

import com.cosmotune.video.model.VideoCodec
import com.cosmotune.video.player.DecoderCaps
import com.cosmotune.video.player.EngineCapabilities
import java.awt.GraphicsEnvironment

/**
 * What the desktop engine plays (spec 6.3, 6.4): H.264, VP9 and AV1 up to 2160p60 everywhere (libmpv decodes them in software
 * at worst; dav1d is in the runtime), reported as software because hardware decoding (`hwdec=auto-copy`) is a bonus mpv picks
 * per file; Opus; no HDR output on the software render path; the separate streams joined by an EDL address rather than a DASH
 * manifest; and Auto never above 1440p (the ceiling of the copy path, spec 2.4), nor above the display's height, which the
 * selector applies from [EngineCapabilities.displayHeightPx].
 */
object DesktopCapabilities {
    /** Auto's ceiling on the desktop (spec 6.3). */
    const val AUTO_CEILING_HEIGHT = 1440

    /** The display height assumed when no screen can be asked (a headless run). */
    const val FALLBACK_DISPLAY_HEIGHT = 1080

    private val decoders = listOf(VideoCodec.H264, VideoCodec.VP9, VideoCodec.AV1).map {
        DecoderCaps(codec = it, maxWidth = 3840, maxHeight = 2160, maxFps = 60, hardware = false)
    }

    /** The capabilities on this computer: the decoders above and the largest screen's height in pixels. */
    fun of(): EngineCapabilities = of(largestDisplayHeight())

    /** The capabilities with a display [displayHeightPx] pixels tall. */
    fun of(displayHeightPx: Int): EngineCapabilities = EngineCapabilities(
        video = decoders,
        opus = true,
        hdrDisplay = false,
        displayHeightPx = displayHeightPx,
        sideloadedDash = false,
        autoCeilingHeight = AUTO_CEILING_HEIGHT,
    )

    /**
     * The height in pixels of the largest picture a landscape video can fill on any screen: each screen's shorter side (a monitor
     * turned upright is as wide as a video's frame is high; Y-5 caps Auto at this height), the largest of them, or
     * [FALLBACK_DISPLAY_HEIGHT] without a screen.
     */
    fun largestDisplayHeight(): Int = runCatching {
        if (GraphicsEnvironment.isHeadless()) return@runCatching null
        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.maxOfOrNull { landscapeHeight(it.displayMode.width, it.displayMode.height) }
    }.getOrNull()?.takeIf { it > 0 } ?: FALLBACK_DISPLAY_HEIGHT

    /** The height of a landscape picture that fills a screen of [width] x [height] pixels: its shorter side. */
    internal fun landscapeHeight(width: Int, height: Int): Int = minOf(width, height)
}
