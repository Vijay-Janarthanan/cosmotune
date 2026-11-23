package com.cosmotune.ui.orbit

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.cosmotune.music.ui.haptics.Haptic
import com.cosmotune.music.ui.haptics.rememberHaptics
import com.cosmotune.ui.components.LocalFocusModality
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/*
 * The one tooltip of the app (Orbit): a small ink pill with the ground's colour for its words, 8 dp from its control, kept on
 * screen. With a pointer it shows after the pointer rests on the control for half a second and while the keyboard's focus is on
 * it; with a finger, under a long press (a light tick), until the finger lifts and a moment after. The press that showed it does
 * not also click. Screen readers keep reading the control's own label: the pill says nothing to them.
 */

/** How long a pointer rests on a control before its tooltip shows. */
internal const val TOOLTIP_HOVER_DELAY_MS = 500L

/** How long a tooltip a finger asked for stays after the finger lifts. */
internal const val TOOLTIP_TOUCH_LINGER_MS = 1500L

/** The room between a control and its tooltip. */
internal val TOOLTIP_GAP = 8.dp

/**
 * What [OrbitTooltipText] says for a control: its [label], and on the desktop its [shortcut] after it in brackets
 * ("Play (Space)"). A phone has no keyboard to name.
 */
fun orbitTooltipText(label: String, shortcut: String?, desktop: Boolean = orbitIsDesktop): String =
    if (shortcut.isNullOrBlank() || !desktop) label else "$label ($shortcut)"

/** True on the desktop app (macOS, Windows, Linux), false on Android: what the JVM's name says. */
val orbitIsDesktop: Boolean = !System.getProperty("java.vm.name").orEmpty().contains("Dalvik", ignoreCase = true) &&
    !System.getProperty("java.vendor").orEmpty().contains("Android", ignoreCase = true)

/** True on macOS, for the shortcuts a control names ("⌘1" there, "Ctrl+1" elsewhere). */
val orbitIsMac: Boolean = orbitIsDesktop && System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)

/** A shortcut with the platform's modifier key: "⌘[key]" on macOS, "Ctrl+[key]" elsewhere. */
fun orbitShortcut(key: String, mac: Boolean = orbitIsMac): String = if (mac) "⌘$key" else "Ctrl+$key"

/**
 * What a tooltip follows: the pointer resting on its control, the keyboard's focus in it, a finger holding it, and the moment it
 * stays after the finger lifts. Made by [rememberOrbitTooltipState]; [Modifier.orbitTooltip] feeds it and [OrbitTooltipPill] shows it.
 */
@Stable
class OrbitTooltipState internal constructor() {
    internal var hovered by mutableStateOf(false)
    internal var focused by mutableStateOf(false)
    internal var held by mutableStateOf(false)
    internal var pressed by mutableStateOf(false)
    internal var hoverShown by mutableStateOf(false)
    internal var lingering by mutableStateOf(false)
}

@Composable
fun rememberOrbitTooltipState(): OrbitTooltipState {
    val state = remember { OrbitTooltipState() }
    LaunchedEffect(state.hovered, state.pressed) {
        state.hoverShown = false
        if (state.hovered && !state.pressed) { delay(TOOLTIP_HOVER_DELAY_MS); state.hoverShown = true }
    }
    LaunchedEffect(state.held) {
        if (state.held) state.lingering = true
        else if (state.lingering) { delay(TOOLTIP_TOUCH_LINGER_MS); state.lingering = false }
    }
    return state
}

/**
 * Feeds [state] from the control this is on: put it before the control's click (so its focus is seen) and [OrbitTooltipPill] in
 * the control's content (so the pill is anchored to the control). A long press of a finger shows the name and does not click.
 */
@Composable
fun Modifier.orbitTooltip(state: OrbitTooltipState, enabled: Boolean = true): Modifier {
    if (!enabled) return this
    val haptics = rememberHaptics()
    val currentHaptics by rememberUpdatedState(haptics)
    val longPress = LocalViewConfiguration.current.longPressTimeoutMillis
    return this
        .onFocusChanged { state.focused = it.hasFocus }
        .pointerInput(state) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val mouse = event.changes.firstOrNull()?.type != PointerType.Touch
                    when (event.type) {
                        PointerEventType.Enter -> if (mouse) state.hovered = true
                        PointerEventType.Exit -> state.hovered = false
                        PointerEventType.Press -> state.pressed = true
                        PointerEventType.Release -> state.pressed = false
                        else -> Unit
                    }
                }
            }
        }
        .pointerInput(state, longPress) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (down.type != PointerType.Touch) return@awaitEachGesture
                val early = withTimeoutOrNull(longPress) { waitForUpOrCancellation(PointerEventPass.Initial) }
                if (early != null) return@awaitEachGesture
                state.held = true
                currentHaptics.play(Haptic.Tick)
                // The finger that asked for the name lifts without pressing the control.
                waitForUpOrCancellation(PointerEventPass.Initial)?.consume()
                state.held = false
            }
        }
}

/**
 * [content] (an icon-only control) with the tooltip that names it ([label], and [shortcut] on the desktop), in a box round it. For
 * a control whose own box cannot hold the pill; the shared buttons use [Modifier.orbitTooltip] and [OrbitTooltipPill] directly.
 */
@Composable
fun OrbitTooltip(label: String, modifier: Modifier = Modifier, shortcut: String? = null, enabled: Boolean = true, content: @Composable () -> Unit) {
    val state = rememberOrbitTooltipState()
    Box(modifier.orbitTooltip(state, enabled && label.isNotBlank()), propagateMinConstraints = true) {
        content()
        if (enabled) OrbitTooltipPill(state, label, shortcut)
    }
}

/** The pill of [state], when it should show: placed inside the control it names (its anchor). */
@Composable
fun OrbitTooltipPill(state: OrbitTooltipState, label: String, shortcut: String? = null) {
    if (label.isBlank()) return
    val keyboardFocus = state.focused && LocalFocusModality.current.keyboard
    val touch = state.held || state.lingering
    if (state.hoverShown || keyboardFocus || touch) OrbitTooltipPopup(orbitTooltipText(label, shortcut), above = touch)
}

/** The pill itself, in a popup by its anchor: under it ([above] false, the pointer's case) or over it (a finger covers what is under). */
@Composable
private fun OrbitTooltipPopup(text: String, above: Boolean) {
    val look = orbitLook()
    val fonts = orbitFonts()
    val gap = with(LocalDensity.current) { TOOLTIP_GAP.roundToPx() }
    val margin = with(LocalDensity.current) { 8.dp.roundToPx() }
    val position = remember(gap, margin, above) { OrbitTooltipPosition(gap, margin, above) }
    Popup(popupPositionProvider = position, properties = PopupProperties(focusable = false)) {
        Box(
            Modifier.clearAndSetSemantics { }
                .widthIn(max = 280.dp)
                .background(look.ink, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(text, style = fonts.body.copy(fontSize = 12.sp, lineHeight = 16.sp), color = tooltipInk(look), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The pill's words: the ground's colour, so they read on the ink in both themes. */
private fun tooltipInk(look: OrbitLook): Color = look.ground.copy(alpha = 1f)

/**
 * Centred on its anchor, [gap] under it (or over it when [above], or when there is no room under it), and at least [margin] in
 * from the window's sides.
 */
internal class OrbitTooltipPosition(private val gap: Int, private val margin: Int, private val above: Boolean) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width - margin).coerceAtLeast(0)
        val x = (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(margin.coerceAtMost(maxX), maxX)
        val under = anchorBounds.bottom + gap
        val over = anchorBounds.top - gap - popupContentSize.height
        val fitsUnder = under + popupContentSize.height <= windowSize.height - margin
        val fitsOver = over >= margin
        val y = when {
            above && fitsOver -> over
            !above && fitsUnder -> under
            fitsOver -> over
            else -> under.coerceAtMost((windowSize.height - popupContentSize.height).coerceAtLeast(0))
        }
        return IntOffset(x, y)
    }
}
