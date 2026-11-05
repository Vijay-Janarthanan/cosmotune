package com.cosmotune.ui.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalReducedMotion
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.cosmo_np_controls_expand
import com.cosmotune.music.sharedui.resources.cosmo_np_controls_expanded_state
import com.cosmotune.music.sharedui.resources.cosmo_np_controls_minimize
import com.cosmotune.music.sharedui.resources.cosmo_np_controls_minimized_state
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * The controls panel at the foot of the portrait page (NP-1): [stack] (today's [NowPlayingStack], called exactly as before) and,
 * while [enabled] (the video shows, no panel is open: see `rememberControlsSheet`), a drag handle above it and the slim bar
 * ([dock], a [NowPlayingDock]) that takes its place when it is minimized. The video and the top bar are not touched.
 *
 * - **One number.** [ControlsSheetState.progress], 0 = expanded, 1 = minimized. The stack and its handle slide down by it and
 *   fade out, the bar fades in and rises 16 dp into place; all of it in `graphicsLayer` and `layout` lambdas, so a drag or a spring
 *   recomposes nothing. The scrim behind the controls follows the same number (`NowPlayingBackdrop`).
 * - **Expanded is today's layout.** The stack keeps its own frame and numbers and is measured as before (the page reads its
 *   height for the lyrics and the queue). The handle is a 48 dp strip above it, so the stack does not move; it is there only while
 *   [enabled], fading in with [reveal]. Not enabled, this is a [Box] around the stack and nothing else. The stack stays at one place
 *   in the composition either way, so its state (the title's arrival) survives the handle coming and going.
 * - **Touch.** The handle drags (down minimizes) and a tap on it toggles; the bar drags (up expands) and a tap on its body expands.
 *   A side that is all the way out is not placed (no drawing, no touch, no semantics), except while a finger is on the panel. On
 *   release the panel settles by velocity or by half way ([ControlsSheetRules.settleTarget]) on the move spring; reduced motion snaps.
 *   Nothing claims the system gesture areas: the bar sits above the navigation bar, and the screen edges stay the system's.
 * - **TalkBack.** The handle and the bar are buttons with a state description ("Controls expanded", "Controls minimized") and the
 *   custom action that changes it ("Minimize controls", "Expand controls"), so nobody needs to drag.
 */
@Composable
internal fun NowPlayingControlsSheet(
    sheet: ControlsSheetState,
    enabled: Boolean,
    reveal: State<Float>,
    stackHeightPx: State<Int>,
    modifier: Modifier = Modifier,
    stack: @Composable (Modifier) -> Unit,
    dock: @Composable (modifier: Modifier, onExpand: () -> Unit) -> Unit,
) {
    val reduced = LocalReducedMotion.current
    val density = LocalDensity.current
    val navigationBars = WindowInsets.navigationBars
    val scope = rememberCoroutineScope()
    // The distance between the two ends: the stack's height less the bar's block above the navigation bar. Read when a finger
    // moves and in the layer below; the stack's height is a State the page already keeps.
    val travelPx: () -> Float = {
        ControlsSheetRules.travelPx(
            stackHeightPx.value.toFloat(),
            navigationBars.getBottom(density) + with(density) { ControlsSheetRules.DOCK_BLOCK_DP.dp.toPx() },
        )
    }
    // Each step of a drag is applied before the next, and before the release (as the player's own drag does).
    val dragState = rememberDraggableState { dy ->
        scope.launch(start = CoroutineStart.UNDISPATCHED) { sheet.dragBy(dy, travelPx()) }
    }
    val drag = Modifier.draggable(
        dragState,
        Orientation.Vertical,
        onDragStarted = { sheet.startDrag() },
        onDragStopped = { velocity -> sheet.release(velocity / density.density, reduced) },
    )
    val onToggle: () -> Unit = { scope.launch { sheet.toggle(reduced) } }
    val onExpand: () -> Unit = { scope.launch { sheet.expand(reduced) } }

    Box(modifier.fillMaxWidth()) {
        val sliding = if (enabled) {
            Modifier
                .placedUnless(sheet.stackHidden)
                .graphicsLayer {
                    val p = sheet.progress.value
                    translationY = ControlsSheetRules.clamp(p) * travelPx()
                    alpha = ControlsSheetRules.stackAlpha(p)
                }
        } else {
            Modifier
        }
        Column(Modifier.fillMaxWidth().then(sliding)) {
            if (enabled) ControlsHandle(sheet, reveal, onToggle, drag)
            stack(Modifier)
        }
        if (enabled) {
            dock(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = ControlsSheetRules.DOCK_MARGIN_DP.dp, end = ControlsSheetRules.DOCK_MARGIN_DP.dp, bottom = ControlsSheetRules.DOCK_MARGIN_DP.dp)
                    .placedUnless(sheet.dockHidden)
                    .graphicsLayer {
                        val shown = ControlsSheetRules.dockAlpha(sheet.progress.value)
                        alpha = shown
                        translationY = (1f - shown) * ControlsSheetRules.DOCK_RISE_DP.dp.toPx()
                    }
                    .then(drag),
                onExpand,
            )
        }
    }
}

/**
 * Measures the content as usual and places it only while [hidden] is false: read in the placement block, so it changes without a
 * recomposition. A node that is not placed is not drawn, takes no touch and is left out of the semantics.
 */
private fun Modifier.placedUnless(hidden: State<Boolean>): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) { if (!hidden.value) placeable.placeRelative(0, 0) }
}

/**
 * The handle above the title: a 44 x 5 dp pill in a 120 x 48 dp target, centred. A tap toggles, a drag moves the panel ([drag]).
 * It fades in with [reveal], as the video does.
 */
@Composable
private fun ColumnScope.ControlsHandle(sheet: ControlsSheetState, reveal: State<Float>, onToggle: () -> Unit, drag: Modifier) {
    val minimized by sheet.minimized
    val minimize = stringResource(Res.string.cosmo_np_controls_minimize)
    val expand = stringResource(Res.string.cosmo_np_controls_expand)
    val state = stringResource(if (minimized) Res.string.cosmo_np_controls_minimized_state else Res.string.cosmo_np_controls_expanded_state)
    val action = if (minimized) expand else minimize
    val ink = Color(LocalCosmoColors.current.palette.textPrimary).copy(alpha = ControlsSheetRules.PILL_ALPHA)
    Box(
        Modifier
            .align(Alignment.CenterHorizontally)
            .size(ControlsSheetRules.HANDLE_TARGET_WIDTH_DP.dp, ControlsSheetRules.HANDLE_TARGET_DP.dp)
            .graphicsLayer { alpha = reveal.value.coerceIn(0f, 1f) }
            .clickable(onClickLabel = action, role = Role.Button, onClick = onToggle)
            .then(drag)
            .semantics {
                role = Role.Button
                contentDescription = action
                stateDescription = state
                customActions = listOf(CustomAccessibilityAction(action) { onToggle(); true })
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(ControlsSheetRules.PILL_WIDTH_DP.dp, ControlsSheetRules.PILL_HEIGHT_DP.dp).background(ink, CircleShape))
    }
}
