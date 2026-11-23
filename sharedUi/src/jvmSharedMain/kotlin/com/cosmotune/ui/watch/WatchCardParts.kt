package com.cosmotune.ui.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitRoundButton
import com.cosmotune.ui.orbit.WatchPicture

/** One round action of a colour card: its icon in a 44 dp disc of the card's ink and its short name under it. */
internal class WatchCardAction(
    val icon: ImageVector,
    val label: String,
    val on: Boolean = false,
    val enabled: Boolean = true,
    val description: String = label,
    /** A menu that opens from this button, drawn in its box so it opens under it. */
    val anchor: (@Composable () -> Unit)? = null,
    val onClick: () -> Unit,
)

/**
 * A colour card's actions in one row (board `.ractions`): a round button with its name under it for each, the card's [ink] at 12 %
 * (solid when on), kept clear of the bite at the card's end by [biteRoom]. The card's one main action stays Play in the bite.
 */
@Composable
internal fun WatchCardActions(actions: List<WatchCardAction>, ink: Color, fill: Color, fonts: OrbitFonts, modifier: Modifier = Modifier, biteRoom: androidx.compose.ui.unit.Dp = 84.dp) {
    // Each action takes an equal share of the row, at most 64 dp, so five still fit beside the bite on a phone; 8 dp between them
    // keeps two round buttons from touching.
    Row(modifier.fillMaxWidth().padding(end = biteRoom), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.forEach { a ->
            Column(Modifier.weight(1f, fill = false).widthIn(max = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box {
                    OrbitRoundButton(a.icon, a.description, ink, a.onClick, size = 44.dp, on = a.on, onInk = fill, enabled = a.enabled)
                    a.anchor?.invoke()
                }
                Text(a.label, style = fonts.body.copy(fontSize = 11.5.sp, lineHeight = 14.sp), color = ink.copy(alpha = if (a.enabled) 0.88f else 0.45f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 5.dp))
            }
        }
    }
}

/**
 * A wide picture faded into a colour card (board `.banner`): the picture on the card's end half, melting into [fill] towards the
 * words at the start, so the card stays one colour where it is read.
 */
@Composable
internal fun BoxScope.WatchCardBanner(url: Any?, key: String, fill: Color, share: Float = 0.62f) {
    Box(Modifier.align(Alignment.TopEnd).fillMaxHeight().fillMaxWidth(share)) {
        WatchPicture(url, key, Modifier.fillMaxSize(), radius = 0.dp)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to fill, 0.55f to fill.copy(alpha = 0.55f), 1f to fill.copy(alpha = 0.15f))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to fill)))
    }
}

/** A thin progress line along the foot of a poster or still, the watched part in the accent (board `.prog`). */
@Composable
internal fun BoxScope.WatchPictureProgress(fraction: Float, accent: Color) {
    Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = 8.dp, vertical = 7.dp).fillMaxWidth().widthIn(min = 1.dp)
        .clip(CircleShape).background(Color.Black.copy(alpha = 0.45f))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).padding(vertical = 1.5.dp).clip(CircleShape).background(accent))
    }
}
