package com.cosmotune.music.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Opt-in. A failed direct-device negotiation always falls back to ordinary playback. */
internal object DesktopOutputMode {
    private const val KEY = "audio_direct_output"
    private val mutable = MutableStateFlow(DesktopPersistence().boolean(KEY, false))
    val direct: StateFlow<Boolean> = mutable
    val status = MutableStateFlow("Output not opened")
    // ALSA hw indexes can name a different DAC after reboot/hotplug; never persist their approval.
    private val dopDeviceMutable = MutableStateFlow(if (DesktopPlatform.isWindows) DesktopPersistence().string("audio_dop_device", "") else "")
    @Volatile private var dopIdentity: String? = null
    val dopDevice: StateFlow<String> = dopDeviceMutable
    fun dopAllowed(device: String = DesktopAudioDevices.selected.value): Boolean = direct.value &&
        device.isNotBlank() && device == dopDeviceMutable.value && (DesktopPlatform.isWindows || device.startsWith("alsa:hw:"))
    /** This explicit, device-specific choice is the user's confirmation of DAC DoP support. */
    fun confirmDop(device: String?) {
        val value = device.orEmpty()
        require(value.isEmpty() || value != DesktopAudioDevices.SYSTEM_DEFAULT && (DesktopPlatform.isWindows || value.startsWith("alsa:hw:")))
        val identity = if (value.isNotEmpty() && DesktopPlatform.isLinux) linuxDopIdentity(value) else null
        if (value.isNotEmpty() && DesktopPlatform.isLinux && identity == null) {
            status.value = "The selected ALSA hardware identity could not be checked; DoP remains off"
            return
        }
        dopIdentity = identity
        DesktopPersistence().saveString("audio_dop_device", if (DesktopPlatform.isWindows) value else "")
        dopDeviceMutable.value = value
    }
    fun validateDopDevice(device: String): Boolean = dopAllowed(device) &&
        (!DesktopPlatform.isLinux || dopIdentity != null && dopIdentity == linuxDopIdentity(device))
    fun invalidateEphemeralDop() { if (DesktopPlatform.isLinux) { dopIdentity = null; dopDeviceMutable.value = "" } }
    private fun linuxDopIdentity(device: String): String? = runCatching {
        val card = Regex("alsa:hw:(\\d+),\\d+").matchEntire(device)?.groupValues?.get(1) ?: return null
        val node = java.io.File("/sys/class/sound/card$card/device").canonicalFile
        require(node.isDirectory)
        val attributes = generateSequence(node) { it.parentFile }.take(6).flatMap { parent ->
            sequenceOf("idVendor", "idProduct", "serial", "product", "manufacturer", "vendor", "device").mapNotNull { key ->
                java.io.File(parent, key).takeIf { it.isFile }?.let { file ->
                    "$key=${file.inputStream().use { it.readNBytes(512).toString(Charsets.UTF_8).trim() }}"
                }
            }
        }.toList()
        require(attributes.isNotEmpty())
        node.path + "|" + attributes.joinToString("|")
    }.getOrNull()
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
