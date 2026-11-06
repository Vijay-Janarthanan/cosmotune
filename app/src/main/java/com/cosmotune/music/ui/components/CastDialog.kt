package com.cosmotune.music.ui.components

import androidx.compose.foundation.layout.size
import com.cosmotune.ui.bridge.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cosmotune.music.R
import com.cosmotune.music.playback.cast.CastController
import com.cosmotune.music.ui.haptics.Haptic
import com.cosmotune.music.ui.haptics.rememberHaptics
import com.cosmotune.music.ui.player.CastReceiverList
import com.cosmotune.music.ui.player.CastReceiverUi
import com.cosmotune.ui.orbit.orbitLook
import dev.chrisbanes.haze.HazeState

/**
 * Where to send the music: the receivers on this network, opened from the
 * output drawer's Cast row.
 *
 * Drawn in [AudioPopupCard], the card the audio pipeline readout uses, so the
 * two popups off the same drawer are visibly one family; the rows are the
 * kit's option rows ([CastReceiverList]). The list is live — receivers are
 * found, and lost, while it is open — and the router is asked to scan for as
 * long as it is, which is the only time that is worth its cost.
 */
@Composable
fun CastDialog(
    hazeState: HazeState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cast by CastController.state.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    val ink = orbitLook().ink
    // Set by tapping a receiver, so the popup closes itself when that receiver
    // connects — but not when somebody opens it while already casting.
    var picked by remember { mutableStateOf(false) }

    DisposableEffect(cast.supported) {
        val stop = CastController.discover(active = true)
        onDispose(stop)
    }
    LaunchedEffect(cast.connectedName, picked) {
        if (picked && cast.connectedName != null) onDismiss()
    }

    AudioPopupCard(
        hazeState = hazeState,
        title = stringResource(R.string.cast_dialog_title),
        subtitle = stringResource(R.string.cast_dialog_subtitle),
        doneLabel = stringResource(R.string.done),
        onDismiss = onDismiss,
        modifier = modifier,
    ) {
        CastReceiverList(
            receivers = cast.devices.map { CastReceiverUi(it.id, it.name, it.connecting, it.connected) },
            casting = cast.casting,
            searching = stringResource(R.string.cast_searching),
            searchingHint = stringResource(R.string.cast_searching_hint),
            connectingLabel = stringResource(R.string.cast_device_connecting),
            connectedLabel = stringResource(R.string.cast_device_connected),
            stopLabel = stringResource(R.string.cast_stop),
            onPick = { receiver ->
                haptics.play(Haptic.Select)
                picked = true
                CastController.connect(receiver.id)
            },
            onStop = {
                haptics.play(Haptic.Select)
                CastController.disconnect(resumeHere = true)
                onDismiss()
            },
            spinner = { size -> CircularProgressIndicator(modifier = Modifier.size(size), color = ink, strokeWidth = 2.dp) },
        )
    }
}
