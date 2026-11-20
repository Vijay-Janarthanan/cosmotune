package com.cosmotune.ui.orbit

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.ui.theme.CosmoMotion
import com.cosmotune.ui.theme.LocalReducedMotion
import kotlinx.coroutines.delay

/*
 * "Removed · Undo" (the shared board's A2 and A4): the one snackbar every removal shows, from the queue, a playlist, the downloads or
 * the library. It stands 8 dp above the mini player (or the window's foot) for 10 s, and Undo puts back what was removed.
 */

/** How long the snackbar stays: 10 s. */
const val ORBIT_UNDO_MS = 10_000L

/** One offer to undo: what it says, and what puts the thing back. */
class OrbitUndoOffer internal constructor(val message: String, internal val onUndo: () -> Unit, internal val id: Long)

/**
 * The window's undo snackbar: one offer at a time, the newest replacing the last. A screen that removes something calls [removed]
 * with the thing's name and what puts it back; the shell draws [OrbitUndoHost].
 */
@Stable
class OrbitUndoState {
    var offer by mutableStateOf<OrbitUndoOffer?>(null)
        private set
    private var next = 0L

    /** "Removed “[what]”", with Undo running [restore]. */
    fun removed(what: String, restore: () -> Unit) = show(if (what.isBlank()) "Removed" else "Removed “$what”", restore)

    /** [message] with Undo running [restore]. */
    fun show(message: String, restore: () -> Unit) {
        offer = OrbitUndoOffer(message, restore, ++next)
    }

    /** Puts back what the shown offer removed, and takes the snackbar away. */
    fun undo() {
        val shown = offer ?: return
        offer = null
        shown.onUndo()
    }

    /** Takes [shown] away, unless a newer offer has replaced it. */
    fun dismiss(shown: OrbitUndoOffer) {
        if (offer === shown) offer = null
    }
}

/** The window's [OrbitUndoState]; null where no shell draws one (a preview), and a removal there offers no undo. */
val LocalOrbitUndo = staticCompositionLocalOf<OrbitUndoState?> { null }

/** One [OrbitUndoState] for the window, kept across recompositions. */
@Composable
fun rememberOrbitUndo(): OrbitUndoState = remember { OrbitUndoState() }

/** Draws [state]'s offer, if there is one, for [ORBIT_UNDO_MS]; it rises into place (a fade with reduced motion). */
@Composable
fun OrbitUndoHost(state: OrbitUndoState?, modifier: Modifier = Modifier) {
    val offer = state?.offer ?: return
    key(offer) {
        LaunchedEffect(Unit) {
            delay(ORBIT_UNDO_MS)
            state.dismiss(offer)
        }
        val reduced = LocalReducedMotion.current
        val arrival = remember { Animatable(0f) }
        LaunchedEffect(Unit) { arrival.animateTo(1f, CosmoMotion.move(reduced)) }
        OrbitUndoSnackbar(
            offer.message, onUndo = state::undo,
            modifier = modifier.padding(bottom = 8.dp).graphicsLayer {
                alpha = arrival.value.coerceIn(0f, 1f)
                translationY = if (reduced) 0f else (1f - arrival.value) * 16.dp.toPx()
            },
        )
    }
}

/**
 * The snackbar itself: 48 dp, corners of 14, the inverse ground (the page's ink) with the words in the page's ground, and Undo at its
 * end in peach (a deep orange on the light ink of the dark theme). Read out politely as soon as it appears.
 */
@Composable
fun OrbitUndoSnackbar(message: String, onUndo: () -> Unit, modifier: Modifier = Modifier) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val undoInk = if (look.dark) Color(0xFFB4572A) else PEACH
    Row(
        modifier.fillMaxWidth().height(48.dp).shadow(16.dp, UNDO_SHAPE, clip = false).clip(UNDO_SHAPE).background(look.ink)
            .semantics { liveRegion = LiveRegionMode.Polite }.testTag("orbit:undo")
            .padding(start = 18.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, style = fonts.chip.copy(fontWeight = FontWeight(600)), color = look.ground, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Box(
            Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).clickable(role = Role.Button, onClick = onUndo).padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) { Text("Undo", style = fonts.chip.copy(fontWeight = FontWeight(800), letterSpacing = 0.3.sp), color = undoInk) }
    }
}

private val UNDO_SHAPE = RoundedCornerShape(14.dp)
