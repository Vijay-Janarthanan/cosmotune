package com.cosmotune.music.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.cosmotune.music.playback.audio.AudioBlock
import com.cosmotune.music.playback.audio.PcmBoundary
import java.nio.ByteOrder
import com.cosmotune.audio.SpatialDsp
import com.cosmotune.audio.SpatialPreset
import com.cosmotune.settings.Extras

/**
 * Headphone spatial audio: the crossfeed, virtual speakers or mono fold of the listener's [SpatialPreset] (chosen in
 * Extras, under the existing on/off switch), and binaural rendering of a 5.1 or 7.1 mix when that is asked for. The DSP is
 * [SpatialDsp] in the shared module, the same code the desktop runs, ported from Mochy Music's bs2b crossfeed and
 * spherical-head renderer. It replaced a mid/side widener that was only ever a stand-in.
 *
 * Runs inside ExoPlayer's own audio processor pipeline because the platform [android.media.audiofx.Virtualizer] produced
 * no audible difference on the reference device, likely swallowed by the OEM's own effect chain.
 */
@UnstableApi
class SpatialAudioProcessor : BaseAudioProcessor() {

    @Volatile
    var enabled: Boolean = false

    private var sampleRate: Int = 0
    private var channelCount: Int = 0
    private var dsp: SpatialDsp? = null
    private var scratch = FloatArray(0)

    private val preset: SpatialPreset get() = Extras.settings.spatialPreset.value
    private val surround: Boolean get() = Extras.settings.spatialSurround.value

    /** Configures the Float32 DSP engine for [sampleRate] and [channelCount]. */
    fun configure(sampleRate: Int, channelCount: Int) {
        this.sampleRate = sampleRate
        this.channelCount = channelCount
        dsp = if (sampleRate > 0 && (channelCount == 2 || channelCount in 3..8)) SpatialDsp(sampleRate, channelCount) else null
    }

    /** Processes interleaved Float32 audio samples in [block] in place. Headroom is kept; nothing is clamped. */
    fun process(block: AudioBlock) {
        if (block.frameCount == 0) return
        val processor = dsp ?: return
        if (block.channelCount != channelCount) return
        processor.process(block.samples, block.sampleCount, enabled, preset, surround)
    }

    /**
     * Stereo 16-bit only: the widening is written in terms of a left and a
     * right sample, and there is no mid/side of a mono voice note or of a 5.1
     * mix to widen.
     *
     * Bowing out with [AudioProcessor.AudioFormat.NOT_SET] rather than an
     * [AudioProcessor.UnhandledAudioFormatException] is what keeps those
     * tracks playable at all.
     * [DefaultAudioSink][androidx.media3.exoplayer.audio.DefaultAudioSink]
     * configures every processor in its chain whether or not the effect is
     * switched on, and a throw from any
     * of them fails the whole sink — the renderer dies with
     * "MediaCodecAudioRenderer error" before a sample is written. NOT_SET
     * means "inactive for this format" and the chain routes around this
     * processor instead.
     *
     * Nothing from YouTube is anything but stereo, so this only ever showed
     * itself on files from the device: every mono or multichannel track in the
     * local library failed to play while downloads were fine.
     */
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount != 2) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        configure(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        return inputAudioFormat
    }

    override fun onFlush() {
        dsp?.reset()
    }

    override fun onReset() {
        dsp = null
        channelCount = 0
        sampleRate = 0
    }

    override fun queueInput(inputBuffer: java.nio.ByteBuffer) {
        val frameCount = inputBuffer.remaining() / BYTES_PER_FRAME
        if (frameCount == 0) return
        val outputBuffer = replaceOutputBuffer(frameCount * BYTES_PER_FRAME)
        val processor = dsp
        if (!enabled || processor == null) {
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }
        inputBuffer.order(ByteOrder.nativeOrder())
        outputBuffer.order(ByteOrder.nativeOrder())
        val count = frameCount * 2
        if (scratch.size < count) scratch = FloatArray(count)
        val invScale = 1.0f / 32768.0f
        for (i in 0 until count) scratch[i] = inputBuffer.short.toFloat() * invScale
        processor.process(scratch, count, enabled, preset, renderSurround = false)
        for (i in 0 until count) outputBuffer.putShort(PcmBoundary.clamp16FromFloat(scratch[i]))
        outputBuffer.flip()
    }

    private companion object {
        const val BYTES_PER_FRAME = 4 // stereo, 16-bit
    }
}
