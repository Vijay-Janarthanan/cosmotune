package com.cosmotune.video.player

import com.cosmotune.video.design.VideoMetrics

/**
 * Whether the chrome over the video shows (spec 6.5 "Hiding"): [shown], and [hiddenAt], the moment (on the caller's clock, in
 * milliseconds) at which it hides by itself, or null when it stays as it is. [playing], [scrubbing] and [sheetOpen] are what keep it
 * up: it is always shown while the video is paused, while the scrubber is dragged and while the settings sheet or menu is open
 * ([pinned]).
 */
data class ChromeState(
    val shown: Boolean = true,
    val hiddenAt: Long? = null,
    val playing: Boolean = false,
    val scrubbing: Boolean = false,
    val sheetOpen: Boolean = false,
) {
    /** True while the chrome must stay shown: paused, scrubbing or a sheet open. */
    val pinned: Boolean get() = !playing || scrubbing || sheetOpen
}

/**
 * What happens to the chrome (spec 6.5): a [TAP] on the video, any other [INPUT] (a control used, a key, the pointer moving over the
 * box), the video starting to [PLAY] or [PAUSE], a scrub starting and ending, the settings sheet or menu opening and closing, and
 * the clock's [TICK].
 */
enum class ChromeEvent { TAP, INPUT, PLAY, PAUSE, SCRUB_START, SCRUB_END, SHEET_OPEN, SHEET_CLOSE, TICK }

/**
 * The chrome's state machine (spec 6.5; pure): shown on a tap or any input; hidden [VideoMetrics.HIDE_AFTER_MS] after the last input
 * while playing; always shown while paused, scrubbing or a sheet is open; a tap while shown hides it, unless one of those keeps it.
 */
object ChromeVisibility {
    /** The state after [event] happened at [nowMs]. */
    fun next(state: ChromeState, event: ChromeEvent, nowMs: Long): ChromeState = when (event) {
        ChromeEvent.TAP ->
            if (state.shown && !state.pinned) state.copy(shown = false, hiddenAt = null)
            else state.showFrom(nowMs)
        ChromeEvent.INPUT -> state.showFrom(nowMs)
        ChromeEvent.PLAY -> state.copy(playing = true).let { if (it.shown) it.copy(hiddenAt = it.deadline(nowMs)) else it.copy(hiddenAt = null) }
        ChromeEvent.PAUSE -> state.copy(playing = false, shown = true, hiddenAt = null)
        ChromeEvent.SCRUB_START -> state.copy(scrubbing = true, shown = true, hiddenAt = null)
        ChromeEvent.SCRUB_END -> state.copy(scrubbing = false).showFrom(nowMs)
        ChromeEvent.SHEET_OPEN -> state.copy(sheetOpen = true, shown = true, hiddenAt = null)
        ChromeEvent.SHEET_CLOSE -> state.copy(sheetOpen = false).showFrom(nowMs)
        ChromeEvent.TICK -> {
            val at = state.hiddenAt
            if (at != null && nowMs >= at && !state.pinned) state.copy(shown = false, hiddenAt = null) else state
        }
    }

    /** Shown, hiding [VideoMetrics.HIDE_AFTER_MS] from [nowMs] unless something keeps it up. */
    private fun ChromeState.showFrom(nowMs: Long): ChromeState = copy(shown = true, hiddenAt = deadline(nowMs))

    private fun ChromeState.deadline(nowMs: Long): Long? = if (pinned) null else nowMs + VideoMetrics.HIDE_AFTER_MS
}
