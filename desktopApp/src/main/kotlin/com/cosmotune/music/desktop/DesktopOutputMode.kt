package com.cosmotune.music.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal enum class DesktopDsdTransport(val label: String) { DOP("DoP"), ALSA_NATIVE("Native DSD") }

/** Opt-in. A failed direct-device negotiation always falls back to ordinary playback. */
internal object DesktopOutputMode {
    private const val KEY = "audio_direct_output"
    private val mutable = MutableStateFlow(DesktopPersistence().boolean(KEY, false))
    val direct: StateFlow<Boolean> = mutable
    val status = MutableStateFlow("Output not opened")
    // Unix hardware indexes can name a different DAC after reboot/hotplug; never persist approval.
    private val dopDeviceMutable = MutableStateFlow(if (DesktopPlatform.isWindows) DesktopPersistence().string("audio_dop_device", "") else "")
    @Volatile private var dopIdentity: String? = null
    val dopDevice: StateFlow<String> = dopDeviceMutable
    private val nativeDsdDeviceMutable = MutableStateFlow("")
    val nativeDsdDevice: StateFlow<String> = nativeDsdDeviceMutable
    @Volatile private var nativeDsdIdentity: String? = null

    fun dsdTransport(device: String = DesktopAudioDevices.selected.value): DesktopDsdTransport? = when {
        !direct.value || device.isBlank() -> null
        DesktopPlatform.isLinux && device == nativeDsdDeviceMutable.value -> DesktopDsdTransport.ALSA_NATIVE
        dopAllowed(device) -> DesktopDsdTransport.DOP
        else -> null
    }

    fun confirmNativeDsd(device: String?) {
        val value = device.orEmpty()
        require(value.isEmpty() || DesktopPlatform.isLinux)
        val identity = value.takeIf(String::isNotEmpty)?.let(::linuxDopIdentity)
        if (value.isNotEmpty() && identity == null) {
            status.value = "The selected ALSA hardware identity could not be checked"
            return
        }
        if (value.isNotEmpty()) confirmDop(null)
        nativeDsdIdentity = identity
        nativeDsdDeviceMutable.value = value
    }

    fun validateDsdDevice(device: String, transport: DesktopDsdTransport): Boolean =
        dsdTransport(device) == transport && when (transport) {
            DesktopDsdTransport.DOP -> validateDopDevice(device)
            DesktopDsdTransport.ALSA_NATIVE -> nativeDsdIdentity != null && nativeDsdIdentity == linuxDopIdentity(device)
        }

    fun dopAllowed(device: String = DesktopAudioDevices.selected.value): Boolean = direct.value &&
        device.isNotBlank() && device == dopDeviceMutable.value && canConfirmDop(device)

    fun canConfirmDop(device: String): Boolean = device.isNotBlank() && when {
        DesktopPlatform.isWindows -> true
        DesktopPlatform.isLinux -> device.startsWith("alsa:hw:")
        DesktopPlatform.isMac -> device.startsWith("coreaudio:")
        else -> false
    }

    private fun dopIdentityFor(device: String): String? = when {
        DesktopPlatform.isLinux -> linuxDopIdentity(device)
        DesktopPlatform.isMac -> CoreAudioDop.deviceIdentity(device)
        else -> null
    }
    /** This explicit, device-specific choice is the user's confirmation of DAC DoP support. */
    fun confirmDop(device: String?) {
        val value = device.orEmpty()
        require(value.isEmpty() || canConfirmDop(value))
        val identity = if (value.isNotEmpty()) dopIdentityFor(value) else null
        if (value.isNotEmpty() && !DesktopPlatform.isWindows && identity == null) {
            status.value = "Select a supported hardware DAC before enabling DoP"
            return
        }
        if (value.isNotEmpty()) confirmNativeDsd(null)
        dopIdentity = identity
        DesktopPersistence().saveString("audio_dop_device", if (DesktopPlatform.isWindows) value else "")
        dopDeviceMutable.value = value
    }
    fun validateDopDevice(device: String): Boolean = dopAllowed(device) &&
        (DesktopPlatform.isWindows || dopIdentity != null && dopIdentity == dopIdentityFor(device))
    fun invalidateEphemeralDop() {
        if (!DesktopPlatform.isWindows) {
            dopIdentity = null
            dopDeviceMutable.value = ""
            nativeDsdIdentity = null
            nativeDsdDeviceMutable.value = ""
        }
    }
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
