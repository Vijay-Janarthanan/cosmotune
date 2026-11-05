package com.cosmotune.ui.nowplaying

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.cosmotune.design.Springs
import com.cosmotune.ui.theme.CosmoShellState
import kotlinx.coroutines.delay
import kotlin.math.max

/**
 * The rules of Now Playing's controls panel over a video (NP-1), as numbers: one progress, 0 = expanded (today's stack, unchanged)
 * and 1 = minimized (the slim bar, [NowPlayingDock]). Pure, so they are tested without a window (`ControlsSheetRulesTest`); the
 * sizes of the handle and the bar are here too, so the structure test pins one place.
 *
 * It applies only while the video is showing on the phone's portrait page with no lyrics or queue open; otherwise the screen is
 * exactly today's and the target is expanded whatever was chosen. The choice itself is remembered (`CosmoLookSettings`).
 */
internal object ControlsSheetRules {
    const val EXPANDED = 0f
    const val MINIMIZED = 1f

    /** Past this the panel settles minimized when the finger lifts slowly; the bar's state description flips here too. */
    const val HALF = 0.5f

    /** A release at least this fast (dp per second) settles by its direction, wherever the finger lifts. */
    const val FLING_DP_PER_S = 300f

    /** Minimized, the dark scrim behind the controls keeps this share of today's strength (the top bar's scrim is unchanged). */
    const val MINIMIZED_SCRIM_SHARE = 0.25f

    /**
     * When the video stops showing but a clip is still wanted (the next song's clip is mounting), the bar waits this long before
     * the panel goes back to expanded: a song change from one video to the next does not open and close the controls.
     */
    const val HOLD_FOR_VIDEO_MS = 600L

    /**
     * The stack fades out between [STACK_FADE_START] and [STACK_FADE_END] as it slides down, so it stays readable for the first
     * part of a drag; the bar fades in from [DOCK_FADE_START] to the end. They cross a little past half way.
     */
    const val STACK_FADE_START = 0.2f
    const val STACK_FADE_END = 0.7f
    const val DOCK_FADE_START = 0.5f

    // ---- the handle -------------------------------------------------------------------------------------------------------
    const val PILL_WIDTH_DP = 44
    const val PILL_HEIGHT_DP = 5
    const val HANDLE_TARGET_DP = 48

    /** The handle's touch target is this wide, centred: the rest of the strip above the title still closes the player on a drag. */
    const val HANDLE_TARGET_WIDTH_DP = 120

    /** The pill: `textPrimary` at this alpha (a little stronger than a sheet's grabber, it sits over the video). */
    const val PILL_ALPHA = 0.55f

    // ---- the bar ----------------------------------------------------------------------------------------------------------
    const val DOCK_HEIGHT_DP = 72
    const val DOCK_MARGIN_DP = 12
    const val DOCK_RADIUS_DP = 24
    const val DOCK_THUMB_DP = 44
    const val DOCK_THUMB_RADIUS_DP = 11
    const val DOCK_TILE_DP = 38
    const val DOCK_PLAY_DP = 46
    const val DOCK_PROGRESS_DP = 4

    /** The pill's top inside the bar. */
    const val DOCK_PILL_TOP_DP = 7

    /** The bar rises this far into place as it fades in. */
    const val DOCK_RISE_DP = 16

    /** From the bottom of the safe area to the top of the bar: the margin under it and its height. */
    const val DOCK_BLOCK_DP = DOCK_MARGIN_DP + DOCK_HEIGHT_DP

    /** [p] held to 0..1; a value not yet measured (NaN) is expanded. */
    fun clamp(p: Float): Float = if (p.isNaN()) EXPANDED else p.coerceIn(EXPANDED, MINIMIZED)

    /** Where a drag of [deltaPx] (down is positive, toward minimized) puts [progress], over a travel of [travelPx]. */
    fun dragged(progress: Float, deltaPx: Float, travelPx: Float): Float =
        if (travelPx <= 0f) progress else clamp(progress + deltaPx / travelPx)

    /** How far the stack moves between expanded and minimized: its height less the bar's block, and at least a pixel. */
    fun travelPx(stackHeightPx: Float, dockBlockPx: Float): Float = max(stackHeightPx - dockBlockPx, 1f)

    /** Where a release at [velocityDpPerS] (down is positive) settles: by its direction when it is a fling, else by half way. */
    fun settleTarget(progress: Float, velocityDpPerS: Float): Float = when {
        velocityDpPerS >= FLING_DP_PER_S -> MINIMIZED
        velocityDpPerS <= -FLING_DP_PER_S -> EXPANDED
        clamp(progress) >= HALF -> MINIMIZED
        else -> EXPANDED
    }

    /** Minimized only while it [applies] and the person chose it; otherwise expanded, exactly today's screen. */
    fun target(applies: Boolean, preferMinimized: Boolean): Float = if (applies && preferMinimized) MINIMIZED else EXPANDED

    /** How long to keep the bar once it stops applying: a little while when a clip is still on its way, else not at all. */
    fun holdBeforeExpandingMs(videoComing: Boolean): Long = if (videoComing) HOLD_FOR_VIDEO_MS else 0L

    /**
     * The choice after the lyrics or the queue open: a panel opened over the bar (the "Playing from" caption can open the queue)
     * expands the controls for good, as an explicit expand would; a panel that was already open when the screen came up
     * ([shownMinimized] false) leaves the choice alone.
     */
    fun preferenceAfterPanel(preferMinimized: Boolean, panelOpen: Boolean, shownMinimized: Boolean): Boolean =
        if (panelOpen && shownMinimized) false else preferMinimized

    /** The bottom scrim's strength: today's at 0, [MINIMIZED_SCRIM_SHARE] of it at 1. */
    fun bottomScrimStrength(p: Float): Float = 1f - (1f - MINIMIZED_SCRIM_SHARE) * clamp(p)

    fun stackAlpha(p: Float): Float = 1f - smooth(STACK_FADE_START, STACK_FADE_END, p)
    fun dockAlpha(p: Float): Float = smooth(DOCK_FADE_START, MINIMIZED, p)

    /** The stack is taken out of drawing and touch only once it is all the way down, and never in the middle of a drag. */
    fun stackHidden(p: Float, dragging: Boolean): Boolean = !dragging && p >= 0.999f

    /** The bar likewise, once the panel is all the way up. */
    fun dockHidden(p: Float, dragging: Boolean): Boolean = !dragging && p <= 0.001f

    private fun smooth(from: Float, to: Float, p: Float): Float {
        val t = ((clamp(p) - from) / (to - from)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}

/**
 * The panel's state: [progress] (an [Animatable] bounded to 0..1, read only in layer, layout and draw lambdas and in the derived
 * states below), the remembered choice [preferMinimized] (written through [keep] only when it changes), and whether a finger is
 * on it ([dragging]). Every change of the progress is a snap (a drag, reduced motion, the way back when the video goes) or the
 * move spring, which ends exactly on its target because of the bounds. Main thread only.
 */
@Stable
internal class ControlsSheetState(preferMinimized: Boolean, private val keep: (Boolean) -> Unit) {
    val progress = Animatable(ControlsSheetRules.EXPANDED).also { it.updateBounds(ControlsSheetRules.EXPANDED, ControlsSheetRules.MINIMIZED) }

    /** The progress as a [State], for the scrims' draw lambda. */
    val progressState: State<Float> get() = progress.asState()

    var preferMinimized by mutableStateOf(preferMinimized)
        private set

    var dragging by mutableStateOf(false)
        private set

    val stackHidden: State<Boolean> = derivedStateOf { ControlsSheetRules.stackHidden(progress.value, dragging) }
    val dockHidden: State<Boolean> = derivedStateOf { ControlsSheetRules.dockHidden(progress.value, dragging) }

    /** Past half way: what the handle and the bar say about themselves. Changes once per crossing, not per frame. */
    val minimized: State<Boolean> = derivedStateOf { progress.value >= ControlsSheetRules.HALF }

    /**
     * Follows the screen: minimized when it [applies] and was chosen; when it stops applying, back to expanded with a snap (after
     * a short hold while [videoComing]); a panel opened over the bar forgets the choice ([ControlsSheetRules.preferenceAfterPanel]).
     */
    suspend fun follow(applies: Boolean, panelOpen: Boolean, videoComing: Boolean, reduced: Boolean) {
        choose(ControlsSheetRules.preferenceAfterPanel(preferMinimized, panelOpen, progress.value >= ControlsSheetRules.HALF))
        if (!applies) {
            // The handle and the bar leave composition with it: a drag they had cannot finish.
            dragging = false
            delay(ControlsSheetRules.holdBeforeExpandingMs(videoComing && !panelOpen))
            progress.snapTo(ControlsSheetRules.EXPANDED)
        } else {
            settleTo(ControlsSheetRules.target(applies = true, preferMinimized = preferMinimized), reduced)
        }
    }

    suspend fun toggle(reduced: Boolean) = if (minimized.value) expand(reduced) else minimize(reduced)

    suspend fun expand(reduced: Boolean) {
        choose(false)
        settleTo(ControlsSheetRules.EXPANDED, reduced)
    }

    suspend fun minimize(reduced: Boolean) {
        choose(true)
        settleTo(ControlsSheetRules.MINIMIZED, reduced)
    }

    fun startDrag() {
        dragging = true
    }

    suspend fun dragBy(deltaPx: Float, travelPx: Float) {
        progress.snapTo(ControlsSheetRules.dragged(progress.value, deltaPx, travelPx))
    }

    suspend fun release(velocityDpPerS: Float, reduced: Boolean) {
        dragging = false
        val target = ControlsSheetRules.settleTarget(progress.value, velocityDpPerS)
        choose(target == ControlsSheetRules.MINIMIZED)
        settleTo(target, reduced)
    }

    private suspend fun settleTo(target: Float, reduced: Boolean) {
        if (reduced) {
            progress.snapTo(target)
        } else {
            progress.animateTo(target, spring(Springs.MOVE.dampingRatio, Springs.MOVE.stiffness, visibilityThreshold = 0.001f))
        }
    }

    private fun choose(minimized: Boolean) {
        if (preferMinimized == minimized) return
        preferMinimized = minimized
        keep(minimized)
    }
}

/**
 * The panel's state for this screen, its choice read from and written to the look settings of [shell] (none: kept for this
 * screen only), following the screen: [applies] (the video shows on the portrait page, no panel open), [panelOpen],
 * [videoComing] (a clip is wanted on the portrait page) and [reduced].
 */
@Composable
internal fun rememberControlsSheet(shell: CosmoShellState?, applies: Boolean, panelOpen: Boolean, videoComing: Boolean, reduced: Boolean): ControlsSheetState {
    val sheet = remember(shell) {
        val settings = shell?.settings
        ControlsSheetState(settings?.nowPlayingControlsMinimized?.value ?: false) { settings?.setNowPlayingControlsMinimized(it) }
    }
    LaunchedEffect(sheet, applies, panelOpen, videoComing, reduced) { sheet.follow(applies, panelOpen, videoComing, reduced) }
    return sheet
}
