package com.cosmotune.music.desktop

import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import com.cosmotune.audio.*
import com.cosmotune.ui.nowplaying.ListeningDeviceSheet
import java.net.URI

/** A desktop profile never claims knowledge of the Bluetooth codec behind a PCM endpoint. */
internal object DesktopListeningDevices {
    private val persistence = DesktopPersistence()
    val store = ListeningDeviceProfiles({ persistence.string("listening_device_profiles", "") }, {
        persistence.saveString("listening_device_profiles", it)
    })
    private val reconnect = ListeningReconnect()
    fun key(selection: String): String? = selection.takeUnless { it.isBlank() || it == DesktopAudioDevices.SYSTEM_DEFAULT }
        ?.let { listeningRouteKey("desktop", "output", it) }
    fun routeChanged(selection: String?) { reconnect.changed(selection?.let(::key), store)?.let(::applyEq) }
    fun captureEq() = ListeningEq(DesktopEqualizerSettings.enabled.value,
        DesktopEqualizerSettings.mode.value == DesktopEqualizerMode.MANUAL, DesktopEqualizerSettings.toneX.value,
        DesktopEqualizerSettings.toneY.value, DesktopEqualizerSettings.focused.value, DesktopEqualizerSettings.balance.value,
        DesktopEqualizerSettings.bands.value, DesktopEqualizerSettings.fullHeadroom.value)
    fun applyEq(eq: ListeningEq) {
        eq.checked()
        DesktopEqualizerSettings.setEnabled(false)
        DesktopEqualizerSettings.setMode(if (eq.manual) DesktopEqualizerMode.MANUAL else DesktopEqualizerMode.DYNAMIC)
        DesktopEqualizerSettings.setTone(eq.toneX, eq.toneY); DesktopEqualizerSettings.setFocused(eq.focused)
        DesktopEqualizerSettings.setBalance(eq.balance); DesktopEqualizerSettings.setBands(eq.bands)
        DesktopEqualizerSettings.setFullHeadroom(eq.fullHeadroom); DesktopEqualizerSettings.setEnabled(eq.enabled)
    }
}

/** App-level host: follows confirmed selection/reconnects without downloading device data. */
@Composable
internal fun DesktopListeningDevicesHost() {
    val selected by DesktopAudioDevices.selected.collectAsState()
    val devices = rememberDesktopAudioDevices()
    LaunchedEffect(selected, devices) {
        DesktopListeningDevices.routeChanged(selected.takeIf { id -> devices.any { it.id == id } })
    }
}

@Composable
internal fun DesktopListeningDeviceEntry(pipeline: DesktopAudioPipeline? = null) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text("Listening device · profile and connection") }
    if (open) DesktopListeningDeviceDialog(pipeline) { open = false }
}

@Composable
private fun DesktopListeningDeviceDialog(pipeline: DesktopAudioPipeline?, onDismiss: () -> Unit) {
    val selected by DesktopAudioDevices.selected.collectAsState()
    val devices = rememberDesktopAudioDevices()
    val device = devices.firstOrNull { it.id == selected }
    val facts = ListeningDeviceFacts(
        routeKey = device?.let { DesktopListeningDevices.key(selected) },
        name = pipeline?.deviceName ?: device?.name ?: "System output",
        identityDetail = if (device == null) "Choose a specific output in Audio output to save a device profile. System default may change devices."
            else "Local mapping to the selected output identity. Reused ALSA indexes or identical mixer names may identify another device after reconnect; confirm the model before restoring tuning.",
        measured = buildList {
            pipeline?.outputApi?.let { add("Output API" to it) }
            pipeline?.outputSampleRateHz?.let { add("Accepted app rate" to "$it Hz") }
            pipeline?.outputBytesPerSample?.let { add("Accepted app PCM" to if (pipeline.outputIsFloat == true) "32-bit float" else "${it * 8}-bit integer") }
            if (pipeline?.dsdOverPcm == true) add("Transport" to "DoP · software DSP bypassed")
            if (pipeline?.nativeDsd == true) add("Transport" to "Native DSD · software DSP bypassed")
        },
        unavailable = "Bluetooth codec / air bitrate, headset internal DAC and ANC are not exposed by this desktop backend",
        spatial = "System spatial processing and head tracking are not reported by this backend. The optional app effect is stereo widening.",
        tuningAvailable = pipeline?.dsdOverPcm != true && pipeline?.nativeDsd != true,
    )
    ListeningDeviceSheet(facts, DesktopListeningDevices.store, DesktopListeningDevices::captureEq, DesktopListeningDevices::applyEq,
        onOpenSystem = null, onOpenGuide = { url -> runCatching { java.awt.Desktop.getDesktop().browse(URI(url)) } }, onDismiss = onDismiss)
}
