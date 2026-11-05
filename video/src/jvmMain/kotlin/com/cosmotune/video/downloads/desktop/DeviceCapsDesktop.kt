package com.cosmotune.video.downloads.desktop

import com.cosmotune.video.downloads.DeviceCaps
import com.cosmotune.video.model.VideoCodec
import com.cosmotune.video.player.EngineCapabilities

/**
 * What the desktop plays back from a download (plan task Y-24, step 6): the desktop engine's capability, libmpv's ceiling
 * (spec 2.4, 6.3): H.264, VP9 and AV1 to 2160p60 in software, with Auto stopping at 1440. FFmpeg writes WebM, so VP9 is kept
 * with Opus in WebM.
 */
object DeviceCapsDesktop {
    /** libmpv's ceiling as the plan gives the desktop engine's capabilities (Y-12): 2160p60, Auto at 1440. */
    val DEFAULT = DeviceCaps(
        maxHeight = 2160,
        maxFps = 60,
        codecs = setOf(VideoCodec.H264, VideoCodec.VP9, VideoCodec.AV1),
        autoCeilingHeight = 1440,
        joinsToWebm = true,
    )

    /** The caps of [engine] (the desktop engine's `capabilities`), or [DEFAULT] without one. */
    fun of(engine: EngineCapabilities? = null): DeviceCaps = engine?.let { DeviceCaps.from(it, joinsToWebm = true) } ?: DEFAULT
}
