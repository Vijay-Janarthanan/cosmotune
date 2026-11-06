package com.cosmotune.music.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Earbuds
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cosmotune.music.sharedui.resources.*
import com.cosmotune.music.ui.haptics.Haptic
import com.cosmotune.music.ui.haptics.rememberHaptics
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitOptionRow
import com.cosmotune.ui.orbit.OrbitProgressLine
import com.cosmotune.ui.orbit.orbitLook
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.delay

/**
 * Where the music is coming out, and how loud.
 *
 * Replaces two things that did not work. On Android 14 and up the headphones
 * glyph opened the *system* output switcher — a panel this app does not control,
 * styled like nothing else here, and one that routes the whole phone rather than
 * this player. Below that it opened a stock [android.app.AlertDialog] driven by
 * [android.media.MediaRouter], whose `selectRoute` is a request the framework is
 * free to ignore, and usually did.
 *
 * This one switches by telling our own player which sink to render into, which
 * is the only routing decision the app owns and the one that actually takes
 * effect — see [AudioRouting].
 *
 * A [PlayerDrawer] off the bottom edge (an Orbit sheet), as the rest of the
 * app's sheets are, its rows the kit's option rows ([OrbitOptionRow]). Outputs
 * come first and volume follows. Every available device stays visible: on a
 * phone there are usually two, so a disclosure would cost a tap merely to
 * reveal a single row.
 */
@Composable
internal fun AudioOutputSheet(
    hazeState: HazeState,
    accountName: String?,
    onDismiss: () -> Unit,
    onOpenPipeline: () -> Unit,
    onOpenCast: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val outputs = rememberAudioOutputs()
    val cast by PlayerPlatform.host.castState.collectAsStateWithLifecycle()
    val watch by PlayerPlatform.host.watchOutput.collectAsStateWithLifecycle()
    // A watch comes and goes; ask again each time the drawer opens.
    LaunchedEffect(Unit) { PlayerPlatform.host.refreshWatchOutput() }

    PlayerDrawer(
        hazeState = hazeState,
        title = stringResource(Res.string.audio_output),
        onDismiss = onDismiss,
        modifier = modifier,
    ) {
        // Flat, with the playing one marked, rather than folded behind a
        // chevron. There are two outputs on a phone most of the time; one
        // of them is the answer and the other is the only alternative, so a
        // disclosure control costs a tap to reveal a single row.
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PlayerPlatform.host.ListeningDeviceEntry()
            outputs.forEach { device ->
                OutputRow(
                    device = device,
                    accountName = accountName,
                    onSelect = { PlayerPlatform.host.selectAudioOutput(device.id) },
                )
            }
            // One more place to send the music, listed with the others. The
            // receivers behind it are chosen in a popup of their own — there
            // can be any number of them, and they come and go while it is open.
            // The watch, when one with Cosmotune on it is in reach: picked
            // directly, since there is only ever the one.
            if (watch.available || watch.active) {
                WatchRow(watch = watch, onClick = { PlayerPlatform.host.selectWatchOutput() })
            }
            if (cast.supported) CastRow(cast = cast, onClick = onOpenCast)
        }

        Spacer(Modifier.height(12.dp))
        VolumeRow(routeKey = outputs)

        Spacer(Modifier.height(8.dp))
        AudioPipelineRow(onClick = onOpenPipeline)
    }
}

/**
 * Drills into [com.cosmotune.music.ui.components.AudioPipelineDialog] — the
 * subtitle is the negotiated output itself, read live off
 * [AudioOutputStatus], so the row states what's actually leaving the phone
 * before anyone taps in for the rest of the chain.
 */
@Composable
private fun AudioPipelineRow(onClick: () -> Unit) {
    val haptics = rememberHaptics()
    val outputFormat by PlayerPlatform.host.outputFormat.collectAsStateWithLifecycle()
    OrbitOptionRow(
        title = stringResource(Res.string.audio_pipeline),
        subtitle = outputFormat.summary,
        icon = Icons.Rounded.GraphicEq,
        onClick = {
            haptics.play(Haptic.Select)
            onClick()
        },
        chevron = true,
    )
}

/**
 * Opens the receiver picker. Lit like the playing output while the music is on
 * a receiver, with that receiver's name in place of the generic title — at that
 * point it *is* the output, and the rows above it are the way back.
 */
@Composable
private fun CastRow(cast: CastUi, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    val casting = cast.connectedName != null
    OrbitOptionRow(
        title = cast.connectedName ?: stringResource(Res.string.cast),
        subtitle = stringResource(
            when {
                cast.connecting -> Res.string.cast_connecting
                casting -> Res.string.cast_casting
                else -> Res.string.cast_row_subtitle
            },
        ),
        icon = if (casting) Icons.Rounded.CastConnected else Icons.Rounded.Cast,
        onClick = {
            haptics.play(Haptic.Select)
            onClick()
        },
        now = casting,
        chevron = true,
    )
}

/**
 * The Cosmotune watch app as the speaker. Drawn like an output of this phone —
 * lit, with a check, while it is the one playing — because to the listener that
 * is what it is.
 */
@Composable
private fun WatchRow(watch: WatchOutputUi, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    val active = watch.active
    OrbitOptionRow(
        title = watch.name?.takeIf(String::isNotBlank) ?: stringResource(Res.string.watch_output),
        subtitle = stringResource(if (active) Res.string.audio_output_playing else Res.string.watch_output_subtitle),
        icon = Icons.Rounded.Watch,
        onClick = if (active) null else ({
            haptics.play(Haptic.Select)
            onClick()
        }),
        now = active,
        checked = active,
    )
}

/**
 * One output. The playing one is lit (the peach of what is playing now) and
 * says so; the rest are there to be tapped.
 */
@Composable
private fun OutputRow(
    device: AudioOutputDevice,
    accountName: String?,
    onSelect: () -> Unit,
) {
    val haptics = rememberHaptics()
    val active = device.isActive
    OrbitOptionRow(
        title = outputLabel(device, accountName),
        subtitle = if (active) stringResource(Res.string.audio_output_playing) else null,
        icon = audioOutputIcon(device),
        onClick = if (active) null else ({
            haptics.play(Haptic.Select)
            onSelect()
        }),
        now = active,
        checked = active,
    )
}

/**
 * The phone's media volume, on the Orbit seek line with its moon.
 *
 * Two things move it, and only one of them announces itself. The hardware keys
 * broadcast `VOLUME_CHANGED_ACTION`, which is easy. A *route* change does not:
 * Android keeps one media volume index per output and silently swaps which one
 * is in force, so the number behind this bar changes with no event at all. Read
 * once at composition, as this was, the bar went on showing the level of the
 * device the listener had just moved away from.
 *
 * So [routeKey] — the outputs and the current choice — re-reads it, and re-reads
 * it again a beat later, because the framework swaps the index a moment after
 * the device list changes rather than in the same breath. A finger on the line
 * is never fought: the line draws where it is being dragged until it is let go,
 * whatever the system level says meanwhile.
 */
@Composable
private fun VolumeRow(routeKey: Any) {
    val system = PlayerPlatform.host.volume
    val systemLevel by system.level.collectAsStateWithLifecycle()
    var level by remember(system) { mutableFloatStateOf(systemLevel) }
    val look = orbitLook()

    // The hardware keys move the system level; follow it.
    LaunchedEffect(systemLevel) { level = systemLevel }

    LaunchedEffect(routeKey) {
        repeat(VOLUME_REREADS) {
            system.refresh()
            delay(VOLUME_REREAD_GAP_MS)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(look.row)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (level < 0.4f) CosmoIcons.VolumeLow else CosmoIcons.Volume,
            contentDescription = null,
            tint = look.ink,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(14.dp))
        OrbitProgressLine(
            progress = { level },
            ink = look.ink,
            modifier = Modifier.weight(1f),
            onSeek = {
                level = it
                system.set(it)
            },
            live = true,
        )
    }
}

internal fun audioOutputIcon(device: AudioOutputDevice): ImageVector = when (device.kind) {
    AudioOutputKind.PHONE -> Icons.Rounded.PhoneAndroid
    AudioOutputKind.WIRED -> Icons.Rounded.Headphones
    AudioOutputKind.USB -> Icons.Rounded.Usb
    AudioOutputKind.BLUETOOTH -> when (device.form) {
        AudioOutputForm.BUDS -> Icons.Rounded.Earbuds
        AudioOutputForm.HEADPHONES -> Icons.Rounded.Headphones
        AudioOutputForm.SPEAKER -> Icons.Rounded.Speaker
        AudioOutputForm.CAR -> Icons.Rounded.DirectionsCar
        AudioOutputForm.UNKNOWN -> Icons.Rounded.Bluetooth
    }
    AudioOutputKind.HDMI -> Icons.Rounded.Tv
    AudioOutputKind.OTHER -> Icons.Rounded.Speaker
}

/** How many times the level is re-read after a route change, and how far apart. */
private const val VOLUME_REREADS = 4
private const val VOLUME_REREAD_GAP_MS = 250L
