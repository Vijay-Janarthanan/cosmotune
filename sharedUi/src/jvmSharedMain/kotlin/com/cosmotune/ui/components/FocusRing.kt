package com.cosmotune.ui.components

import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.design.InputModality
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.theme.LocalCosmoColors

/**
 * Visible keyboard focus: while [interaction] is focused, draws the content, then a 2 dp gap in `backgroundBottom` and a
 * ring around it, outside the bounds, with the corner radius [radius] grown by the gap and half the ring.
 *
 * The ring is the B recipe of the B+C system (`--focus-ring` in `tokens-v3bc.css`): `accentStrong`, 3 dp thick, for
 * controls that are part of the page. Controls that float over content or video ([floating]) take the C recipe: `textPrimary`
 * ink, 2 dp thick. Both rails are the engine's, so the ring keeps its contrast on every cover.
 *
 * It shows for keyboard focus only, as `:focus-visible` does ([InputModality]): a control that took focus from a click or a touch has
 * none (the desktop gave a clicked sidebar item a heavy ring). The answer is [LocalFocusModality], read while drawing.
 */
@Composable
fun Modifier.cosmoFocusRing(interaction: InteractionSource, radius: Dp, floating: Boolean = false): Modifier {
    val focused = interaction.collectIsFocusedAsState()
    val colors = LocalCosmoColors.current
    val modality = LocalFocusModality.current
    return drawWithContent {
        drawContent()
        if (InputModality.ringShown(focused.value, modality.keyboard)) {
            val gap = FOCUS_GAP.toPx()
            val thickness = (if (floating) FOCUS_THICKNESS_C else FOCUS_THICKNESS_B).toPx()
            val ink = colors.live(if (floating) CoverField.TEXT_PRIMARY else CoverField.ACCENT_STRONG)
            drawRingOutside(0f, gap, radius.toPx(), colors.live(CoverField.BACKGROUND_BOTTOM))
            drawRingOutside(gap, gap + thickness, radius.toPx(), ink)
        }
    }
}

internal val FOCUS_GAP = 2.dp
internal val FOCUS_THICKNESS_B = 3.dp
internal val FOCUS_THICKNESS_C = 2.dp

/** A rounded ring that spans [from] to [to] pixels outside the bounds of the drawn box. */
private fun DrawScope.drawRingOutside(from: Float, to: Float, radius: Float, color: Color) {
    val centre = (from + to) / 2f
    drawRoundRect(
        color = color,
        topLeft = Offset(-centre, -centre),
        size = Size(size.width + 2 * centre, size.height + 2 * centre),
        cornerRadius = CornerRadius(radius + centre),
        style = Stroke(width = to - from),
    )
}

/**
 * Whether the person is moving about by keyboard (so a focused control draws its ring): one answer for the whole app, however many
 * windows it has, kept by [InputModality]. The state is read while drawing, so a change redraws the rings and recomposes nothing.
 */
@Stable
class FocusModality {
    private var state by mutableStateOf(InputModality.INITIAL)

    /** True after a key of navigation, false after a press of a mouse button or a finger. */
    val keyboard: Boolean get() = state

    internal fun on(event: InputModality.Event) {
        val next = InputModality.next(state, event)
        if (next != state) state = next
    }

    companion object {
        /** The app's one: what [LocalFocusModality] holds unless a screen is given its own (a preview). */
        val Shared = FocusModality()
    }
}

val LocalFocusModality = staticCompositionLocalOf { FocusModality.Shared }

/**
 * Keeps [LocalFocusModality] up to date from what happens inside this box: a key of navigation going down or up (up as well, because
 * the first Tab of all, pressed while nothing has focus, only reaches a handler on its release) is keyboard use, a press of a pointer
 * is not. Nothing is consumed, so no control notices. A window of its own (the drawer, a sheet, a dialog) does not see the main
 * window's keys: it puts this on its own root.
 */
@Composable
fun Modifier.trackFocusModality(): Modifier {
    val modality = LocalFocusModality.current
    return this
        .onPreviewKeyEvent { event ->
            if ((event.type == KeyEventType.KeyDown || event.type == KeyEventType.KeyUp) && event.key.isFocusNavigationKey()) {
                modality.on(InputModality.Event.NAVIGATION_KEY)
            }
            false
        }
        .pointerInput(modality) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Press) modality.on(InputModality.Event.POINTER_DOWN)
                }
            }
        }
}

/**
 * The root of the app's content: [content] in a box that tracks [LocalFocusModality] while [enabled] (the new look is on; the old
 * look has no rings and gets a plain box). The box passes its constraints on as they are, so the layout is the same with or without it.
 */
@Composable
fun FocusModalityRoot(enabled: Boolean, content: @Composable () -> Unit) {
    Box(if (enabled) Modifier.trackFocusModality() else Modifier, propagateMinConstraints = true) { content() }
}

/** The keys that move focus or act on it: Tab, the arrows and the d-pad's centre, Space, Enter, and the keys that page through a list. */
internal fun Key.isFocusNavigationKey(): Boolean = this in FOCUS_NAVIGATION_KEYS

private val FOCUS_NAVIGATION_KEYS = setOf(
    Key.Tab, Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight, Key.DirectionCenter,
    Key.Enter, Key.NumPadEnter, Key.Spacebar, Key.MoveHome, Key.MoveEnd, Key.PageUp, Key.PageDown,
)
