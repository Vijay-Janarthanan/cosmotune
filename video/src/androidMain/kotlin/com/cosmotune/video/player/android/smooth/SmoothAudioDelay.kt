package com.cosmotune.video.player.android.smooth

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.cosmotune.video.player.smooth.PcmDelayLine
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * How long the sound is held back while frame smoothing is showing the picture one source frame late. One value for the process
 * (Video has one player); [SmoothAudioDelayProcessor] reads it on the audio thread, the smoothing code writes it.
 */
internal object SmoothAudioDelay {
    /** Seconds of the video's own time. 0 when smoothing is not engaged: the processor then passes the audio straight through. */
    @Volatile
    var seconds: Double = 0.0
}

/**
 * Delays PCM audio by [SmoothAudioDelay.seconds], in whole frames, through a [PcmDelayLine]. It sits in the audio sink's chain
 * before the speed processing, so the delay is in the video's own time (the engine multiplies the clock's interval by the speed).
 *
 * The player's clock follows the frames the audio track has played, silence included, so the audio is simply later against the
 * clock and the video, which is what is wanted; the delay needs no change to the player's timing. A seek (a flush) starts the
 * delay again from silence, which keeps sound and picture together after it. At the end of the stream what is still held is played.
 * With no delay the processor passes the buffers through unchanged.
 */
@OptIn(UnstableApi::class)
internal class SmoothAudioDelayProcessor : BaseAudioProcessor() {
    private var line: PcmDelayLine? = null
    private var sampleRate = 0

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (!Util.isEncodingLinearPcm(inputAudioFormat.encoding) || inputAudioFormat.bytesPerFrame <= 0 || inputAudioFormat.sampleRate <= 0) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        sampleRate = inputAudioFormat.sampleRate
        val silence = if (inputAudioFormat.encoding == C.ENCODING_PCM_8BIT) 0x80.toByte() else 0.toByte()
        line = PcmDelayLine(inputAudioFormat.bytesPerFrame, (sampleRate / 2).coerceAtLeast(1), silence)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val delay = line ?: return
        val size = inputBuffer.remaining()
        if (size == 0) return
        val wanted = framesFor(SmoothAudioDelay.seconds)
        if (wanted != delay.delayFrames) delay.setDelay(wanted)
        val output = replaceOutputBuffer(size)
        delay.process(inputBuffer, output)
        output.flip()
    }

    override fun onQueueEndOfStream() {
        val delay = line ?: return
        val held = delay.heldBytes
        if (held <= 0) return
        val output = replaceOutputBuffer(held)
        delay.drain(output)
        output.flip()
    }

    override fun onFlush() {
        line?.reset(framesFor(SmoothAudioDelay.seconds))
    }

    override fun onReset() {
        line = null
        sampleRate = 0
    }

    private fun framesFor(seconds: Double): Int = if (seconds <= 0.0) 0 else (seconds * sampleRate).roundToInt()
}

/** The service player's renderers: the defaults, with the audio sink carrying [SmoothAudioDelayProcessor]. */
@OptIn(UnstableApi::class)
internal class VideoRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
        DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf(SmoothAudioDelayProcessor()))
            .build()
}
