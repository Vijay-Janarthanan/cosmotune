package com.cosmotune.music.playback.audio

import com.cosmotune.music.data.audio.AudioLevelMeter
import com.cosmotune.music.data.audio.AudioLevelsHub

/**
 * The phone's audio level tap for the Pulse lyric style: a pass-through stage at the end of the float DSP chain in
 * [PrecisionAudioSink], reading each block after every other stage has shaped it and changing nothing. Each block is dated by
 * when it will be heard: now, plus how far the chain runs ahead of the output ([leadUs], kept up to date by the sink as the
 * renderer polls its position). One process-wide meter ([meter]), so both of the crossfade's sinks feed the same readings, and the
 * sink only offers the audible one's. No work at all while nothing on screen listens.
 */
class LevelTapProcessor(private val meter: AudioLevelMeter = AndroidAudioLevels.meter) : FloatAudioProcessor {
    private var channels = AudioBlock.DEFAULT_CHANNELS

    /** How far ahead of what is heard the block being processed is, in µs. */
    @Volatile var leadUs: Long = 0L

    override fun configure(sampleRate: Int, channelCount: Int) {
        channels = channelCount.coerceAtLeast(1)
        if (sampleRate > 0) meter.ensureFormat(sampleRate, channels)
    }

    override fun process(block: AudioBlock) {
        if (!meter.active || block.frameCount == 0) return
        meter.process(block.samples, block.sampleCount, System.nanoTime() + leadUs.coerceAtLeast(0L) * 1_000L)
    }

    override fun flush() = Unit

    override fun reset() = Unit
}

/** The phone's one meter, put where the lyrics can find it the first time a sink is made. */
object AndroidAudioLevels {
    val meter: AudioLevelMeter by lazy { AudioLevelMeter().also { AudioLevelsHub.source = it } }
}
