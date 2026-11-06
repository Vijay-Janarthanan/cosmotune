@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cosmotune.ui.extras

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cosmotune.music.data.NetworkPolicy
import com.cosmotune.settings.Extras
import com.cosmotune.settings.ExtrasSettings
import com.cosmotune.settings.ProxySetting
import kotlin.math.roundToInt

/**
 * The "Extras" options both apps share, in a plain dialog: one settings row opens it on each platform. Deliberately
 * unstyled beyond Material defaults; the settings redesign is expected to restyle or fold these rows into its own pages.
 * The strings are English for now.
 */
@Composable
fun ExtrasSettingsDialog(onDismiss: () -> Unit, extraRows: @Composable ColumnScope.() -> Unit = {}) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.padding(16.dp).widthIn(max = 560.dp).heightIn(max = 720.dp),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Extras", style = MaterialTheme.typography.headlineSmall)
                ExtrasSettingsPanel(Extras.settings, extraRows)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Done") }
                }
            }
        }
    }
}

/** What [ExtrasSettingsDialog] shows; [extraRows] is where a platform adds rows only it has. */
@Composable
fun ColumnScope.ExtrasSettingsPanel(settings: ExtrasSettings, extraRows: @Composable ColumnScope.() -> Unit = {}) {
    val maxVolume by settings.maxVolumePercent.collectAsState()
    val pitch by settings.pitchSemitones.collectAsState()
    val offline by settings.offline.collectAsState()

    ExtrasHeader("Sound")
    ExtrasSlider(
        title = "Maximum volume",
        detail = "The loudest Cosmotune plays, whatever the volume is set to. For hearing safety.",
        value = if (maxVolume >= 100) "No cap" else "$maxVolume%",
        position = maxVolume.toFloat(),
        range = ExtrasSettings.MIN_VOLUME_PERCENT.toFloat()..100f,
        steps = (100 - ExtrasSettings.MIN_VOLUME_PERCENT) / 5 - 1,
        onChange = { settings.setMaxVolumePercent((it / 5f).roundToInt() * 5) },
    )
    ExtrasSlider(
        title = "Pitch",
        detail = "Shifts the key without changing the speed, which stays its own setting.",
        value = when {
            pitch == 0 -> "Unchanged"
            pitch > 0 -> "+$pitch semitones"
            else -> "$pitch semitones"
        },
        position = pitch.toFloat(),
        range = -12f..12f,
        steps = 23,
        onChange = { settings.setPitchSemitones(it.roundToInt()) },
    )
    val spatialPreset by settings.spatialPreset.collectAsState()
    val spatialSurround by settings.spatialSurround.collectAsState()
    ExtrasChoice(
        title = "Spatial audio style",
        detail = "What the stereo widening switch in Settings does while it is on. ${spatialPreset.detail}.",
        options = com.cosmotune.audio.SpatialPreset.entries,
        selected = spatialPreset,
        label = { it.label },
        onSelect = settings::setSpatialPreset,
    )
    ExtrasSwitch(
        title = "Binaural surround",
        detail = "With that switch on, a 5.1 or 7.1 stream is rendered to two ears from the usual speaker positions " +
            "instead of being folded down. For headphones; Dolby Atmos keeps Dolby's own rendering.",
        checked = spatialSurround,
        onChange = settings::setSpatialSurround,
    )
    extraRows()

    ExtrasHeader("Lyrics")
    val autoOffset by settings.autoLyricsOffset.collectAsState()
    val autoState by com.cosmotune.lyrics.autosync.LyricsAutoOffset.state.collectAsState()
    ExtrasSwitch(
        title = "Automatic lyrics offset",
        detail = "Listens to each song once, in the background, and moves timed lyrics onto the singing when the match is " +
            "clear. The manual offset still applies on top." + when (val shown = autoState) {
                is com.cosmotune.lyrics.autosync.LyricsAutoOffset.State.Listening -> " Listening to the current song…"
                is com.cosmotune.lyrics.autosync.LyricsAutoOffset.State.Synced ->
                    " Last song: moved ${shown.offsetMs} ms" + (shown.driftMs?.let { ", drifting $it ms over the song" } ?: "") + "."
                is com.cosmotune.lyrics.autosync.LyricsAutoOffset.State.Failed -> " Last song: ${shown.reason}"
                null -> ""
            },
        checked = autoOffset,
        onChange = settings::setAutoLyricsOffset,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = { com.cosmotune.lyrics.autosync.LyricsAutoOffset.forgetAll() }) { Text("Forget measured offsets") }
    }

    ExtrasHeader("Network")
    ExtrasSwitch(
        title = "Offline mode",
        detail = "Nothing is fetched from the internet: local files, downloads and your own network only. " +
            "Songs that need streaming will not play.",
        checked = offline,
        onChange = settings::setOffline,
    )
    ProxyRows(settings)
}

@Composable
private fun ProxyRows(settings: ExtrasSettings) {
    val saved by settings.proxy.collectAsState()
    var type by remember(saved) { mutableStateOf(saved.type) }
    var address by remember(saved) { mutableStateOf(saved.address) }
    var username by remember(saved) { mutableStateOf(saved.username) }
    var password by remember(saved) { mutableStateOf(saved.password) }
    val parsed = NetworkPolicy.parseAddress(address)
    val active = NetworkPolicy.proxy
    ExtrasSwitch(
        title = "Use a proxy",
        detail = when {
            NetworkPolicy.lockedBy != null -> "Not used while ${NetworkPolicy.lockedBy} carries the traffic."
            saved.enabled && active != null -> "Requests go through ${active.label}. Local addresses go direct."
            saved.enabled -> "On, but the address is not host:port yet."
            else -> "Send the app's requests through an HTTP or SOCKS proxy of your own."
        },
        checked = saved.enabled,
        onChange = { settings.setProxy(saved.copy(enabled = it)) },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ProxySetting.Type.entries.forEach { option ->
            FilterChip(selected = type == option, onClick = { type = option }, label = { Text(option.name) })
        }
    }
    OutlinedTextField(
        value = address,
        onValueChange = { address = it },
        label = { Text("Address (host:port)") },
        singleLine = true,
        isError = address.isNotBlank() && parsed == null,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("User (optional)") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.weight(1f),
        )
    }
    val edited = ProxySetting(saved.enabled, type, address.trim(), username, password)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = { settings.setProxy(edited) }, enabled = edited != saved && (address.isBlank() || parsed != null)) {
            Text("Save proxy")
        }
    }
}

@Composable
fun ExtrasHeader(title: String) {
    Spacer(Modifier.padding(top = 4.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
fun ExtrasSwitch(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
fun ExtrasSlider(
    title: String,
    detail: String,
    value: String,
    position: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = position, onValueChange = onChange, valueRange = range, steps = steps)
    }
}

/** One choice among a few, as chips; used by the platform rows that pick a mode. */
@Composable
fun <T> ExtrasChoice(title: String, detail: String, options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(label(option)) })
            }
        }
    }
}
