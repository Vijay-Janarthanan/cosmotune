package com.cosmotune.video.player.android.smooth

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.cosmotune.video.player.AudioMix
import com.cosmotune.video.player.sound.SoundMixer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The sound mix the player's audio goes through; one for the process, like [SmoothAudioDelay]. */
internal object SoundMix {
    @Volatile var mix: AudioMix = AudioMix.NORMAL
}

/** Applies [SoundMix.mix] to 16-bit PCM with a [SoundMixer]; passes buffers straight through for [AudioMix.NORMAL]. */
@OptIn(UnstableApi::class)
internal class SoundMixProcessor : BaseAudioProcessor() {
    private var mixer: SoundMixer? = null
    private var channels = 0
    private var scratch = ShortArray(0)

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw UnhandledAudioFormatException(inputAudioFormat)
        mixer = SoundMixer(inputAudioFormat.sampleRate.coerceAtLeast(1))
        channels = inputAudioFormat.channelCount
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val output = replaceOutputBuffer(size)
        val mix = SoundMix.mix
        val mixer = mixer
        if (mix == AudioMix.NORMAL || mixer == null) {
            output.put(inputBuffer)
        } else {
            val count = size / 2
            if (scratch.size < count) scratch = ShortArray(count)
            inputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(scratch, 0, count)
            inputBuffer.position(inputBuffer.position() + count * 2)
            mixer.process(scratch, channels, mix, count)
            output.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(scratch, 0, count)
            output.position(output.position() + count * 2)
            // An odd trailing byte is not a sample; carry it as it was.
            while (inputBuffer.hasRemaining()) output.put(inputBuffer.get())
        }
        output.flip()
    }

    override fun onFlush() { mixer?.reset() }

    override fun onReset() { mixer = null; channels = 0 }
}

/**
 * Software-only video decoding, for a device whose hardware decoder draws a broken or green picture: when [on], the decoders
 * Media3 is offered for video are the software ones (the full list again if there are none for the format).
 */
@OptIn(UnstableApi::class)
internal object SoftwareDecoding {
    @Volatile var on: Boolean = false

    val selector = androidx.media3.exoplayer.mediacodec.MediaCodecSelector { mimeType, secure, tunneling ->
        val all = androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
        if (!on || !androidx.media3.common.MimeTypes.isVideo(mimeType)) all else all.filter { it.softwareOnly }.ifEmpty { all }
    }
}
