package com.cosmotune.music.playback

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.os.Build
import com.cosmotune.audio.*
import com.cosmotune.music.data.settings.AppSettings
import com.cosmotune.music.data.settings.EqualizerMode
import kotlinx.coroutines.flow.MutableStateFlow

/** Local, explicit profiles. No Bluetooth permissions, account access or network requests. */
object AndroidListeningDevices {
    @Volatile private var saved: ListeningDeviceProfiles? = null
    private val reconnect = ListeningReconnect()
    val route = MutableStateFlow<Pair<String?, String>>(null to "No active output")
    val spatial = MutableStateFlow(PlatformSpatialState())
    fun store(context: Context): ListeningDeviceProfiles = saved ?: synchronized(this) {
        saved ?: context.applicationContext.getSharedPreferences("listening_devices", Context.MODE_PRIVATE).let { prefs ->
            ListeningDeviceProfiles({ prefs.getString("profiles", "").orEmpty() }, {
                check(prefs.edit().putString("profiles", it).commit()) { "Device profiles could not be persisted" }
            })
        }.also { saved = it }
    }
    fun routeChanged(context: Context, device: AudioDeviceInfo?) {
        val kind = device?.let { AudioRouting.kindOf(it.type) }
        val name = device?.productName?.toString()?.trim().orEmpty().ifBlank { "Android output" }
        // IDs change on reconnect. A Bluetooth address, when Android exposes it, is stable;
        // otherwise require the user to acknowledge that this is a name-based mapping.
        val address = if (Build.VERSION.SDK_INT >= 28 && kind == AudioRouting.Kind.BLUETOOTH)
            runCatching { device?.address }.getOrNull()?.takeIf { it.isNotBlank() } else null
        val identity = address ?: name
        val key = if (kind in setOf(AudioRouting.Kind.BLUETOOTH, AudioRouting.Kind.USB, AudioRouting.Kind.WIRED))
            listeningRouteKey("android", kind.toString(), identity) else null
        route.value = key to name
        reconnect.changed(key, store(context))?.let(::applyEq)
    }
    fun captureEq() = ListeningEq(AppSettings.equalizerEnabled.value,
        AppSettings.equalizerMode.value == EqualizerMode.MANUAL, AppSettings.equalizerToneX.value,
        AppSettings.equalizerToneY.value, AppSettings.equalizerFocused.value, AppSettings.equalizerBalance.value,
        AppSettings.equalizerBands.value, AppSettings.equalizerFullHeadroom.value)
    fun applyEq(eq: ListeningEq) {
        eq.checked()
        AppSettings.setEqualizerEnabled(false)
        AppSettings.setEqualizerMode(if (eq.manual) EqualizerMode.MANUAL else EqualizerMode.DYNAMIC)
        AppSettings.setEqualizerTone(eq.toneX, eq.toneY); AppSettings.setEqualizerFocused(eq.focused)
        AppSettings.setEqualizerBalance(eq.balance); AppSettings.setEqualizerBands(eq.bands)
        AppSettings.setEqualizerFullHeadroom(eq.fullHeadroom); AppSettings.setEqualizerEnabled(eq.enabled)
    }
    fun facts(output: AudioOutputStatus.Snapshot): ListeningDeviceFacts {
        val spatial = spatial.value
        val bt = output.bluetoothTelemetry
        val facts = buildList {
            add("Output route" to output.sink)
            output.actualEncoding?.let { add("Accepted app PCM" to when (it) {
                AudioFormat.ENCODING_PCM_FLOAT -> "32-bit float"
                AudioFormat.ENCODING_PCM_24BIT_PACKED -> "24-bit integer"
                AudioFormat.ENCODING_PCM_32BIT -> "32-bit integer"
                AudioFormat.ENCODING_PCM_16BIT -> "16-bit integer"
                else -> "Encoding $it"
            }) }
            output.actualSampleRateHz?.let { add("Accepted app rate" to "$it Hz") }
            if (output.routeKind == AudioRouting.Kind.BLUETOOTH) {
                add("Bluetooth codec" to if (bt?.hasNamedCodec == true) bt.codecName else "Not exposed by Android")
                if (bt?.isAuthoritative == true) {
                    bt.sampleRateHz?.let { add("Reported Bluetooth rate" to "$it Hz") }
                    bt.bitDepth?.let { add("Reported Bluetooth depth" to "$it-bit") }
                    if (bt.bitrateLabel != "Not exposed by Android") add("Reported Bluetooth bitrate" to bt.bitrateLabel)
                }
            }
            output.fallbackDetail?.let { add("Output fallback" to it) }
        }
        return ListeningDeviceFacts(route.value.first, output.deviceName.ifBlank { route.value.second },
            "Device-local mapping. When Android hides the hardware address, this uses the output name; identically named devices share that mapping. Confirm the model before saving.", facts,
            spatial = when {
                Build.VERSION.SDK_INT < 32 -> "Requires Android 12L or newer; stereo widening is separate."
                !spatial.reported -> "Android did not expose platform spatial audio state. Cosmotune's Spatial audio switch is a separate app effect."
                !spatial.supported -> "This Android audio platform reports no spatializer support."
                else -> "System spatial audio: ${if (spatial.enabled) "enabled" else "off"}.\n" +
                    "Current route: ${if (spatial.available) "available" else "unavailable"}.\n" +
                    "Current content: ${when (spatial.eligible) { true -> "eligible"; false -> "not eligible"; null -> "not reported yet" }}.\n" +
                    "Head tracker: ${when (spatial.headTracker) { true -> "available"; false -> "unavailable in the current state"; null -> "not exposed" }}.\n" +
                    "Eligibility does not confirm active Atmos rendering."
            },
            tuningAvailable = output.dspAvailable)
    }
}
