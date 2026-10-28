package com.cosmotune.music.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Opt-in. A failed direct-device negotiation always falls back to ordinary playback. */
internal object DesktopOutputMode {
    private const val KEY = "audio_direct_output"
    private val mutable = MutableStateFlow(DesktopPersistence().boolean(KEY, false))
    val direct: StateFlow<Boolean> = mutable
    val status = MutableStateFlow("Output not opened")
    fun select(enabled: Boolean) {
        DesktopPersistence().saveBoolean(KEY, enabled)
        mutable.value = enabled
        DesktopAudioDevices.systemChanged()
    }
}

/** Same rate/channels first; never silently call a resampled stream an exact-device offer. */
internal fun directPcmCandidates(requested: DesktopPcmFormat): List<DesktopPcmFormat> = listOf(
    requested.copy(bytesPerSample = 4, isFloat = true),
    requested.copy(bytesPerSample = 4, isFloat = false),
    requested.copy(bytesPerSample = 3, isFloat = false),
    requested.copy(bytesPerSample = 2, isFloat = false),
).distinct()

internal data class AcceptedDesktopPcm<T>(val format: DesktopPcmFormat, val endpoint: T)

/** Only an opener's actual success accepts a format; advertised depths are not evidence. */
internal fun <T> negotiateDirectPcm(requested: DesktopPcmFormat, open: (DesktopPcmFormat) -> Result<T>): Result<AcceptedDesktopPcm<T>> {
    var failure: Throwable = IllegalStateException("No exact device format accepted")
    for (format in directPcmCandidates(requested)) {
        val result = open(format)
        if (result.isSuccess) return Result.success(AcceptedDesktopPcm(format, result.getOrThrow()))
        failure = result.exceptionOrNull() ?: failure
    }
    return Result.failure(failure)
}
