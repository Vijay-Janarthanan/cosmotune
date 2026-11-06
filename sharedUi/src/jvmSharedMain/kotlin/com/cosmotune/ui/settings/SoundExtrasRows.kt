@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.cosmotune.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material.icons.rounded.VpnLock
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.cosmotune.audio.SpatialPreset
import com.cosmotune.lyrics.autosync.LyricsAutoOffset
import com.cosmotune.music.data.NetworkPolicy
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.recognition.SongRecognition
import com.cosmotune.settings.Extras
import com.cosmotune.settings.ExtrasSettings
import com.cosmotune.settings.ProxySetting
import com.cosmotune.ui.components.CosmoSwitch
import com.cosmotune.ui.orbit.OrbitPillButton
import com.cosmotune.ui.orbit.OrbitTextField
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/*
 * The options first seen in Mochy Music (see `NOTICE.md`) as rows of the Sound page of Settings, on both hosts: the volume cap and
 * pitch as slider rows, the style of the Spatial audio switch as chips under its row, binaural surround, the automatic lyrics offset
 * and its reset, offline mode, the proxy and song recognition. Each is one composable so a host can put it in its own group and
 * register it with its own search under the titles of [SoundExtrasText]. English only, as the owner wants.
 */

/** The titles and lines of the rows below, for the hosts' search to register them under. */
object SoundExtrasText {
    const val MAX_VOLUME = "Maximum volume"
    const val MAX_VOLUME_LINE = "The loudest Cosmotune plays, whatever the volume is set to. For hearing safety."
    const val PITCH = "Pitch"
    const val PITCH_LINE = "Shifts the key without changing the speed, which stays its own setting."
    const val SPATIAL_STYLE = "Spatial audio style"
    const val SPATIAL_STYLE_LINE = "What the Spatial audio switch does while it is on."
    const val SURROUND = "Binaural surround"
    const val SURROUND_LINE = "With Spatial audio on, a 5.1 or 7.1 stream is rendered to two ears from the usual speaker positions " +
        "instead of being folded down. For headphones; Dolby Atmos keeps Dolby's own rendering."
    const val AUTO_OFFSET = "Automatic lyrics offset"
    const val AUTO_OFFSET_LINE = "Listens to each song once, in the background, and moves timed lyrics onto the singing when the match " +
        "is clear. The manual offset still applies on top."
    const val FORGET_OFFSETS = "Forget measured offsets"
    const val FORGET_OFFSETS_LINE = "Every song is listened to again the next time it plays."
    const val OFFLINE = "Offline mode"
    const val OFFLINE_LINE = "Nothing is fetched from the internet: local files, downloads and your own network only. " +
        "Songs that need streaming will not play."
    const val PROXY = "Use a proxy"
    const val PROXY_LINE = "Send the app's requests through an HTTP or SOCKS proxy of your own."
    const val RECOGNITION = "Identify songs playing nearby"
    const val RECOGNITION_LINE = "Off by default. Records ten seconds from the microphone, makes a fingerprint on this device and sends " +
        "only the fingerprint to Shazam's unofficial endpoint (amp.shazam.com), which is not an official or supported API and may " +
        "stop working. The recording itself never leaves the device."

    /** The group headers the hosts put the rows under, on the Sound page. */
    const val GROUP_LYRICS_TIMING = "Lyrics timing"
    const val GROUP_NETWORK = "Network"
    const val GROUP_RECOGNITION = "Song recognition"
}

/** Where a row's own controls stand under its text: from the text's start to the row's end. */
private val UNDER_ROW = Modifier.fillMaxWidth().padding(start = CosmoSettingsInsets.textStart, end = 16.dp, bottom = 12.dp)

/** The loudest the app plays, 5 % at a time; 100 % reads "No cap". */
@Composable
fun MaxVolumeRow(settings: ExtrasSettings = Extras.settings) {
    val maxVolume by settings.maxVolumePercent.collectAsState()
    CosmoSliderRow(
        icon = Icons.AutoMirrored.Rounded.VolumeUp,
        title = SoundExtrasText.MAX_VOLUME,
        subtitle = SoundExtrasText.MAX_VOLUME_LINE,
        value = if (maxVolume >= 100) "No cap" else "$maxVolume%",
        sliderValue = maxVolume.toFloat(),
        onSliderValue = { settings.setMaxVolumePercent((it / 5f).roundToInt() * 5) },
        valueRange = ExtrasSettings.MIN_VOLUME_PERCENT.toFloat()..100f,
        steps = (100 - ExtrasSettings.MIN_VOLUME_PERCENT) / 5 - 1,
    )
}

/** The key, a semitone at a time from an octave down to an octave up, apart from the speed. */
@Composable
fun PitchRow(settings: ExtrasSettings = Extras.settings) {
    val pitch by settings.pitchSemitones.collectAsState()
    CosmoSliderRow(
        icon = Icons.Rounded.MusicNote,
        title = SoundExtrasText.PITCH,
        subtitle = SoundExtrasText.PITCH_LINE,
        value = when {
            pitch == 0 -> "Unchanged"
            pitch > 0 -> "+$pitch semitones"
            else -> "$pitch semitones"
        },
        sliderValue = pitch.toFloat(),
        onSliderValue = { settings.setPitchSemitones(it.roundToInt()) },
        valueRange = -12f..12f,
        steps = 23,
    )
}

/** What the Spatial audio switch renders, as Orbit's chips under the row; the chosen style's line says what it does. */
@Composable
fun SpatialStyleRow(settings: ExtrasSettings = Extras.settings) {
    val preset by settings.spatialPreset.collectAsState()
    Column(Modifier.fillMaxWidth()) {
        CosmoSettingsRow(
            icon = Icons.Rounded.SurroundSound,
            title = SoundExtrasText.SPATIAL_STYLE,
            subtitle = "${SoundExtrasText.SPATIAL_STYLE_LINE} ${preset.detail}.",
        )
        FlowRow(UNDER_ROW, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SpatialPreset.entries.forEach { option ->
                CosmoSettingsChip(label = option.label, selected = option == preset, onClick = { settings.setSpatialPreset(option) })
            }
        }
    }
}

/** A 5.1 or 7.1 stream to two ears while Spatial audio is on. */
@Composable
fun BinauralSurroundRow(settings: ExtrasSettings = Extras.settings) {
    val surround by settings.spatialSurround.collectAsState()
    SwitchRow(Icons.Rounded.Hearing, SoundExtrasText.SURROUND, SoundExtrasText.SURROUND_LINE, surround, settings::setSpatialSurround)
}

/** The background listening that moves timed lyrics onto the singing, with how the last song went. */
@Composable
fun AutoLyricsOffsetRow(settings: ExtrasSettings = Extras.settings) {
    val on by settings.autoLyricsOffset.collectAsState()
    val state by LyricsAutoOffset.state.collectAsState()
    val last = when (val shown = state) {
        is LyricsAutoOffset.State.Listening -> " Listening to the current song…"
        is LyricsAutoOffset.State.Synced ->
            " Last song: moved ${shown.offsetMs} ms" + (shown.driftMs?.let { ", drifting $it ms over the song" } ?: "") + "."
        is LyricsAutoOffset.State.Failed -> " Last song: ${shown.reason}"
        null -> ""
    }
    SwitchRow(Icons.Rounded.Lyrics, SoundExtrasText.AUTO_OFFSET, SoundExtrasText.AUTO_OFFSET_LINE + last, on, settings::setAutoLyricsOffset)
}

/** Throws away the offsets measured so far. */
@Composable
fun ForgetLyricsOffsetsRow() {
    CosmoSettingsRow(
        icon = Icons.Rounded.Restore,
        title = SoundExtrasText.FORGET_OFFSETS,
        subtitle = SoundExtrasText.FORGET_OFFSETS_LINE,
        onClick = { LyricsAutoOffset.forgetAll() },
    )
}

/** Nothing from the internet while it is on. */
@Composable
fun OfflineModeRow(settings: ExtrasSettings = Extras.settings) {
    val offline by settings.offline.collectAsState()
    SwitchRow(Icons.Rounded.CloudOff, SoundExtrasText.OFFLINE, SoundExtrasText.OFFLINE_LINE, offline, settings::setOffline)
}

/**
 * The listener's own proxy: the switch (its line says where the requests go), then under it the kind as chips, the address, the
 * optional user and password as Orbit's fields, and Save once something changed and the address reads as host:port.
 */
@Composable
fun ProxyRows(settings: ExtrasSettings = Extras.settings) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val saved by settings.proxy.collectAsState()
    var type by remember(saved) { mutableStateOf(saved.type) }
    var address by remember(saved) { mutableStateOf(saved.address) }
    var username by remember(saved) { mutableStateOf(saved.username) }
    var password by remember(saved) { mutableStateOf(saved.password) }
    val parsed = NetworkPolicy.parseAddress(address)
    val active = NetworkPolicy.proxy
    Column(Modifier.fillMaxWidth()) {
        SwitchRow(
            icon = Icons.Rounded.VpnLock,
            title = SoundExtrasText.PROXY,
            subtitle = when {
                NetworkPolicy.lockedBy != null -> "Not used while ${NetworkPolicy.lockedBy} carries the traffic."
                saved.enabled && active != null -> "Requests go through ${active.label}. Local addresses go direct."
                saved.enabled -> "On, but the address is not host:port yet."
                else -> SoundExtrasText.PROXY_LINE
            },
            checked = saved.enabled,
            onChange = { settings.setProxy(saved.copy(enabled = it)) },
        )
        Column(UNDER_ROW, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProxySetting.Type.entries.forEach { option ->
                    CosmoSettingsChip(label = option.name, selected = type == option, onClick = { type = option })
                }
            }
            OrbitTextField(
                value = address,
                onValueChange = { address = it },
                placeholder = "Address (host:port)",
                fonts = fonts,
                look = look,
                error = address.isNotBlank() && parsed == null,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OrbitTextField(username, { username = it }, "User (optional)", fonts, look, Modifier.weight(1f))
                OrbitTextField(password, { password = it }, "Password", fonts, look, Modifier.weight(1f), secret = true)
            }
            val edited = ProxySetting(saved.enabled, type, address.trim(), username, password)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OrbitPillButton(
                    label = "Save proxy",
                    onClick = { settings.setProxy(edited) },
                    fonts = fonts,
                    look = look,
                    enabled = edited != saved && (address.isBlank() || parsed != null),
                )
            }
        }
    }
}

/**
 * Song recognition: the switch with the note on Shazam's unofficial endpoint, and while it is on, Listen (the one peach action), what
 * the last try found, and Open in Shazam and Play for a match. [onPlay] null hides Play; [ensureMicrophone] asks for the microphone
 * where the platform needs a runtime permission and answers whether it may be used.
 */
@Composable
fun SongRecognitionRows(
    settings: ExtrasSettings = Extras.settings,
    onPlay: ((List<Song>, Int) -> Unit)? = null,
    ensureMicrophone: ((Boolean) -> Unit) -> Unit = { it(true) },
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val enabled by settings.songRecognition.collectAsState()
    val state by SongRecognition.state.collectAsState()
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxWidth()) {
        SwitchRow(Icons.Rounded.Mic, SoundExtrasText.RECOGNITION, SoundExtrasText.RECOGNITION_LINE, enabled, settings::setSongRecognition)
        if (!enabled) return@Column
        val busy = state is SongRecognition.State.Listening || state is SongRecognition.State.Asking
        Column(UNDER_ROW, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OrbitPillButton(
                    label = if (busy) "Listening…" else "Listen",
                    onClick = {
                        ensureMicrophone { allowed ->
                            if (allowed) scope.launch { SongRecognition.identify() }
                        }
                    },
                    fonts = fonts,
                    look = look,
                    primary = true,
                    enabled = !busy,
                    icon = Icons.Rounded.GraphicEq,
                )
                Text(
                    when (val shown = state) {
                        SongRecognition.State.Idle -> "Hold the device near the music."
                        SongRecognition.State.Listening -> "Listening for ten seconds…"
                        SongRecognition.State.Asking -> "Asking Shazam…"
                        SongRecognition.State.NoMatch -> "No match. Try again closer to the music."
                        is SongRecognition.State.Failed -> shown.reason
                        is SongRecognition.State.Found ->
                            "${shown.match.title} · ${shown.match.artist}" + (shown.match.album?.let { " ($it)" } ?: "")
                    },
                    style = fonts.body,
                    color = look.ink,
                    modifier = Modifier.weight(1f),
                )
            }
            (state as? SongRecognition.State.Found)?.let { found ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    found.match.shazamUrl?.let { link ->
                        OrbitPillButton("Open in Shazam", { runCatching { uri.openUri(link) } }, fonts, look)
                    }
                    val song = found.song
                    if (song != null && onPlay != null) {
                        OrbitPillButton("Play", { onPlay(listOf(song), 0) }, fonts, look)
                    } else if (song == null) {
                        Text("Not found on YouTube Music as this exact recording.", style = fonts.body, color = look.dim, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** A row with its switch at the end; a tap anywhere on it toggles. */
@Composable
private fun SwitchRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    CosmoSettingsRow(
        icon = icon,
        title = title,
        subtitle = subtitle,
        onClick = { onChange(!checked) },
        trailing = { CosmoSwitch(checked = checked, onCheckedChange = onChange) },
    )
}
