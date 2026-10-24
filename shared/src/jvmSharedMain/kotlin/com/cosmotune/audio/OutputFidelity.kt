package com.cosmotune.audio

/**
 * What the operating system does with the samples after Cosmotune hands them over.
 *
 * Only [BIT_PERFECT_CONFIRMED] lets a readout say "bit-perfect", and only a platform API may set
 * it: on Android 14 and later the preferred mixer attributes for the routed USB device report the
 * bit-perfect mixer behaviour with exactly the format of the open track. Nothing else counts. A
 * direct AudioTrack profile, a USB route, WASAPI shared mode or a DAC that "supports" a rate are
 * all [SYSTEM_MIXER] or [DIRECT_UNCONFIRMED]: the system may still resample, mix or apply volume.
 */
enum class OutputMixing {
    /** The system mixer (AudioFlinger, the Windows audio engine, PulseAudio/PipeWire, CoreAudio). */
    SYSTEM_MIXER,

    /** A direct or offload path was selected, but the platform does not confirm the samples arrive unchanged. */
    DIRECT_UNCONFIRMED,

    /** The platform confirms an unmixed, unprocessed path for exactly the format being written. */
    BIT_PERFECT_CONFIRMED,
}

/** Everything the verdict is decided from; each host fills in what it measured. */
data class OutputFidelityFacts(
    /** A DSD file decoded to PCM: never bit-perfect, whatever the path after it. */
    val dsdConvertedToPcm: Boolean = false,
    /** The first stage inside Cosmotune that changes sample values (loudness, EQ, spatial, volume), if any. */
    val alteringStage: String? = null,
    /** Cosmotune converted the decoder's sample rate itself. */
    val resampledByApp: Boolean = false,
    /** Whether the encoding written to the system carries the decoder's samples intact. */
    val encodingExact: Boolean = true,
    /** Why [encodingExact] is false, or a note on the encoding when it is true. */
    val encodingDetail: String? = null,
    val mixing: OutputMixing = OutputMixing.SYSTEM_MIXER,
    /** The system named in the readout: "Android", "Windows", "the system". */
    val systemName: String = "the system",
)

sealed interface OutputFidelity {
    /** The platform confirmed it and nothing in Cosmotune changes the samples. */
    data class BitPerfect(val detail: String?) : OutputFidelity

    /** Cosmotune leaves the samples alone; what the system does after that is not confirmed. */
    data class UnchangedByApp(val mixing: OutputMixing, val systemName: String) : OutputFidelity

    /** Something changes the samples; [reason] names the first stage that does. */
    data class Altered(val reason: String) : OutputFidelity
}

object OutputFidelityVerdict {

    /**
     * The verdict, naming the first deviation in signal order: the DSD conversion, then a
     * processing stage, then Cosmotune's own resampler, then an encoding that cannot carry the
     * source. Only when none applies does the system path decide between [OutputFidelity.BitPerfect]
     * and [OutputFidelity.UnchangedByApp].
     */
    fun of(facts: OutputFidelityFacts): OutputFidelity = when {
        facts.dsdConvertedToPcm -> OutputFidelity.Altered("DSD converted to PCM")
        facts.alteringStage != null -> OutputFidelity.Altered(facts.alteringStage)
        facts.resampledByApp -> OutputFidelity.Altered("resampled")
        !facts.encodingExact -> OutputFidelity.Altered(facts.encodingDetail ?: "converted downstream")
        facts.mixing == OutputMixing.BIT_PERFECT_CONFIRMED -> OutputFidelity.BitPerfect(facts.encodingDetail)
        else -> OutputFidelity.UnchangedByApp(facts.mixing, facts.systemName)
    }

    /** The readout's English wording. "Bit-perfect" appears only for [OutputFidelity.BitPerfect]. */
    fun label(verdict: OutputFidelity): String = when (verdict) {
        is OutputFidelity.BitPerfect ->
            "Bit-perfect (confirmed by the system)${verdict.detail?.let { " · $it" }.orEmpty()}"
        is OutputFidelity.UnchangedByApp -> when (verdict.mixing) {
            OutputMixing.DIRECT_UNCONFIRMED ->
                "Unchanged by Cosmotune · direct path, not confirmed bit-perfect by ${verdict.systemName}"
            else -> "Unchanged by Cosmotune · ${verdict.systemName}'s mixer may resample or apply volume"
        }
        is OutputFidelity.Altered -> "No · ${verdict.reason}"
    }

    fun label(facts: OutputFidelityFacts): String = label(of(facts))
}
