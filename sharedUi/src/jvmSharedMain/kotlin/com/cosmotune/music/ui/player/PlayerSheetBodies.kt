package com.cosmotune.music.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitOptionRow
import com.cosmotune.ui.orbit.OrbitSheetButton
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import dev.chrisbanes.haze.HazeState

/*
 * The insides of two of the player's platform sheets, drawn here in Orbit so the phone's sheets and their render proofs are one
 * piece of code: the Share lyrics sheet ([LyricsShareDrawer]) and the cast picker's list of receivers ([CastReceiverList]). The
 * platform keeps what only it can do (rendering the card, writing files, talking to the receivers) and hands in what to show.
 */

/** The corners of the lyric card's preview: the Orbit card's 22. */
private val PREVIEW_SHAPE = RoundedCornerShape(22.dp)

/**
 * One action of the Share lyrics sheet: [label] with [icon] before it; [primary] is the one peach action (Share). Once [done] (the
 * picture is in the gallery) the button says so itself, a check and [doneLabel], rather than offering to do it again.
 */
class LyricsShareAction(
    val label: String,
    val icon: ImageVector,
    val enabled: Boolean = true,
    val primary: Boolean = false,
    val done: Boolean = false,
    val doneLabel: String? = null,
    val onClick: () -> Unit,
)

/**
 * The picked lines, drawn, with what can be done with the picture: a [PlayerDrawer] (an Orbit sheet) with the [description], the
 * [preview] in a frame of the card's own [ratio] (the platform draws the picture, or its spinner while it is being drawn), the
 * [error] when the card could not be drawn, [pair] side by side under it (Save and Share, the peach one), and the [extras] one
 * under the other after them (the story cut, the .lrc, the video).
 */
@Composable
fun LyricsShareDrawer(
    hazeState: HazeState,
    title: String,
    description: String,
    ratio: Float,
    extras: List<LyricsShareAction>,
    pair: List<LyricsShareAction>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
    preview: @Composable (Modifier) -> Unit,
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    PlayerDrawer(hazeState = hazeState, title = title, onDismiss = onDismiss, modifier = modifier, titleGap = 4.dp) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = description,
                style = fonts.body,
                color = look.dim,
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 4.dp),
            )
            // The card is only as tall as its lines, so the frame follows it:
            // a strip comes out wide, a full verse narrow, both about the same
            // height on screen rather than a fixed box with bands to spare.
            preview(
                Modifier
                    .fillMaxWidth((0.72f * ratio).coerceIn(0.36f, 0.92f))
                    .aspectRatio(ratio)
                    .clip(PREVIEW_SHAPE)
                    .background(look.row),
            )
            if (error != null) {
                Text(text = error, style = fonts.body, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(2.dp))
            // Save and Share first, under the card, so the one peach action is in reach without scrolling; the rest follow.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { ShareButton(it, Modifier.weight(1f)) }
            }
            extras.forEach { ShareButton(it, Modifier.fillMaxWidth()) }
        }
    }
}

@Composable
private fun ShareButton(action: LyricsShareAction, modifier: Modifier) {
    OrbitSheetButton(
        label = if (action.done) action.doneLabel ?: action.label else action.label,
        onClick = action.onClick,
        modifier = modifier,
        primary = action.primary,
        enabled = action.enabled,
        icon = if (action.done) CosmoIcons.Check else action.icon,
    )
}

/** A receiver on this network, as the cast picker lists it. */
class CastReceiverUi(val id: String, val name: String, val connecting: Boolean, val connected: Boolean)

/**
 * The cast picker's list (inside the output drawer's popup card): until a receiver turns up, a spinner disc and what it is doing
 * ([searching], [searchingHint]); then a row per receiver, the one the music is on lit as playing now with its check, one being
 * connected to with the spinner at its end. [stopLabel] (while [casting]) brings the music back. [spinner] is the platform's own
 * spinner at the size given.
 */
@Composable
fun CastReceiverList(
    receivers: List<CastReceiverUi>,
    casting: Boolean,
    searching: String,
    searchingHint: String,
    connectingLabel: String,
    connectedLabel: String,
    stopLabel: String,
    onPick: (CastReceiverUi) -> Unit,
    onStop: () -> Unit,
    spinner: @Composable (Dp) -> Unit,
    modifier: Modifier = Modifier,
) {
    val look = orbitLook()
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (receivers.isEmpty()) {
            OrbitOptionRow(
                title = searching,
                subtitle = searchingHint,
                icon = null,
                onClick = null,
                discContent = {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(look.chip), contentAlignment = Alignment.Center) { spinner(20.dp) }
                },
            )
        }
        receivers.forEach { receiver ->
            val on = casting && receiver.connected
            OrbitOptionRow(
                title = receiver.name,
                subtitle = when {
                    receiver.connecting -> connectingLabel
                    on -> connectedLabel
                    else -> null
                },
                icon = if (on) Icons.Rounded.CastConnected else Icons.Rounded.Cast,
                onClick = if (receiver.connected) null else ({ onPick(receiver) }),
                now = on,
                checked = on,
                trailing = if (receiver.connecting) ({ Box(Modifier.padding(start = 10.dp)) { spinner(18.dp) } }) else null,
            )
        }
        if (casting) {
            OrbitOptionRow(title = stopLabel, subtitle = null, icon = Icons.Rounded.Close, onClick = onStop)
        }
    }
}
