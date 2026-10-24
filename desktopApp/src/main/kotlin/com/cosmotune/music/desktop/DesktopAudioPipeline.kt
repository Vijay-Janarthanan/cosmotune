package com.cosmotune.music.desktop

import com.cosmotune.audio.OutputFidelityFacts
import com.cosmotune.audio.OutputMixing

/**
 * The playback signal chain as it stands right now — what the engine can say about each stage
 * between the stream arriving and the samples reaching the device.
 *
 * Every field is nullable because the chain is only partly built until something is playing.
 */
internal data class DesktopAudioPipeline(
    /** What the engine is actually playing, which the screen's own copy can lag behind. */
    val sourceFormat: DesktopStreamFormat? = null,
    val decoderName: String? = null,
    val decodedSampleRateHz: Int? = null,
    val decodedChannels: Int? = null,
    val outputSampleRateHz: Int? = null,
    val outputChannels: Int? = null,
    val outputIsFloat: Boolean? = null,
    val outputBytesPerSample: Int? = null,
    val deviceName: String? = null,
    val bufferBytes: Int = 0,
    val equalizerEnabled: Boolean = false,
    val skipSilence: Boolean = false,
    /** The output path: "WASAPI shared" on Windows, otherwise "Java Sound"; null before it opens. */
    val outputApi: String? = null,
    /** Loudness normalization is applying a gain to this track. */
    val loudnessActive: Boolean = false,
    /** The player volume is below full, which scales every sample. */
    val volumeBelowFull: Boolean = false,
    /** The name the readout gives the operating system's mixer. */
    val systemName: String = "the system",
) {
    /** The decoder under its usual name, the same one the quality badge shows. */
    val decoderLabel: String?
        get() = decoderName?.let { DesktopStreamFormat(codec = it).codecLabel }

    /** Whether the decoder's rate had to be converted to reach the device. */
    val resampling: Boolean
        get() = decodedSampleRateHz != null && outputSampleRateHz != null &&
            decodedSampleRateHz != outputSampleRateHz
}

/**
 * Whether the samples leave Cosmotune unchanged, and never "bit-perfect": every desktop output
 * today is a shared one (WASAPI shared mode, or Java Sound over the system mixer), so the system
 * may resample, mix or apply its own volume afterwards. Exclusive WASAPI, ALSA `hw:` and CoreAudio
 * hog mode are not implemented; see FEATURE_TRACKER MN-05.
 */
internal fun DesktopAudioPipeline.fidelityFacts(): OutputFidelityFacts {
    val sourceBits = sourceFormat?.bitDepth
    val outputBits = outputBytesPerSample?.let { it * 8 }
    // Float output carries any source up to 24 bits intact; an integer output narrower than the
    // source drops bits, which a 16-bit Java Sound fallback does to a 24-bit file.
    val narrowed = outputIsFloat != true && sourceBits != null && outputBits != null && outputBits < sourceBits
    return OutputFidelityFacts(
        dsdConvertedToPcm = sourceFormat?.codec?.substringAfterLast('/')?.substringBefore(';')?.trim()?.lowercase() in DSD_CODECS,
        alteringStage = when {
            loudnessActive -> "loudness normalization"
            equalizerEnabled -> "equalizer"
            volumeBelowFull -> "volume below 100%"
            else -> null
        },
        resampledByApp = resampling,
        encodingExact = !narrowed,
        encodingDetail = if (narrowed) "$sourceBits-bit source written as $outputBits-bit" else null,
        mixing = OutputMixing.SYSTEM_MIXER,
        systemName = systemName,
    )
}

private val DSD_CODECS = setOf("dsf", "dff", "dsd", "dsd_lsbf", "dsd_msbf", "dsd_lsbf_planar", "dsd_msbf_planar")
