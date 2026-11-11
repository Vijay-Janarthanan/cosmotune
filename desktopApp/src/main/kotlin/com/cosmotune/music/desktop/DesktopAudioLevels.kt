package com.cosmotune.music.desktop

import com.cosmotune.music.data.audio.AudioLevelMeter
import com.cosmotune.music.data.audio.AudioLevelsHub

/**
 * The desktop's audio level tap for the Pulse lyric style: the engine's own float PCM, read just before it goes to the sink, each
 * block dated by when it will be heard (what the device still has queued in front of it). Read-only, and no work at all while
 * nothing on screen listens ([AudioLevelMeter.active]).
 */
internal object DesktopAudioLevels {
    val meter = AudioLevelMeter()

    fun install() {
        AudioLevelsHub.source = meter
    }

    /**
     * Reads [count] interleaved samples about to be written, [queuedFrames] behind what the device has not played yet, at
     * [sampleRate] and [channels]. Never touches the samples.
     */
    fun offer(samples: FloatArray, count: Int, sampleRate: Int, channels: Int, queuedFrames: Long) {
        if (!meter.active || sampleRate <= 0) return
        meter.ensureFormat(sampleRate, channels)
        meter.process(samples, count, System.nanoTime() + queuedFrames.coerceAtLeast(0L) * 1_000_000_000L / sampleRate)
    }
}
