package com.cosmotune.video.player

/** What a key does to the video (spec 6.6). The page or the player box carries it out. */
sealed interface PlayerKeyAction {
    /** Space or K. */
    data object TogglePlay : PlayerKeyAction

    /** J and L ([PlayerKeyMap.JL_SEEK_MS]), Left and Right ([PlayerKeyMap.ARROW_SEEK_MS]). */
    data class SeekBy(val deltaMs: Long) : PlayerKeyAction

    /** 0 to 9: 0 % to 90 % of the video. */
    data class SeekToFraction(val fraction: Float) : PlayerKeyAction

    /** Up and Down: the volume by [delta] (0..1 scale). */
    data class Volume(val delta: Float) : PlayerKeyAction

    /** M. */
    data object ToggleMute : PlayerKeyAction

    /** F: full window (the desktop) or full screen. */
    data object FullWindow : PlayerKeyAction

    /** T: theatre (the wide watch page). */
    data object Theatre : PlayerKeyAction

    /** C: captions on or off. */
    data object Captions : PlayerKeyAction

    /** Shift+N. */
    data object Next : PlayerKeyAction

    /** Shift+P. */
    data object Previous : PlayerKeyAction

    /** `<` and `>`: the speed by [delta]. */
    data class Speed(val delta: Float) : PlayerKeyAction

    /** Esc: leaves the full window. */
    data object ExitFullWindow : PlayerKeyAction
}

/**
 * The desktop's keys over a video (spec 6.6; pure): Space or K play and pause; J and L back and forward 10 s; Left and Right 5 s; Up
 * and Down volume; M mute; F full window; T theatre; C captions; Shift+N next; Shift+P previous; `<` and `>` speed; 0 to 9 jump to
 * 0 % to 90 %; Esc leaves full window.
 *
 * A key is named as the caller's key event names it: a letter or a digit as itself (letters in either case), or one of `Space`,
 * `Left`, `Right`, `Up`, `Down`, `Escape`, `Comma`, `Period`, or the characters `<` and `>` (which most layouts type as Shift+Comma
 * and Shift+Period). Any key held with Ctrl or Cmd is the app's (Ctrl K focuses search), so the answer is null.
 */
object PlayerKeyMap {
    /** J and L: 10 s. */
    const val JL_SEEK_MS = 10_000L

    /** Left and Right: 5 s (also the scrubber's arrow step, spec 6.5). */
    const val ARROW_SEEK_MS = 5_000L

    /** Up and Down: 5 % of the volume. */
    const val VOLUME_STEP = 0.05f

    /** `<` and `>`: the speed list's step (spec 6.5: 0.25 to 4 in steps of 0.25). */
    const val SPEED_STEP = 0.25f

    /** The action of [key] with the modifiers held, or null when the player has none for it. */
    fun action(key: String, shift: Boolean, ctrlOrCmd: Boolean): PlayerKeyAction? {
        if (ctrlOrCmd) return null
        return when (key) {
            "Space" -> PlayerKeyAction.TogglePlay
            "Left" -> PlayerKeyAction.SeekBy(-ARROW_SEEK_MS)
            "Right" -> PlayerKeyAction.SeekBy(ARROW_SEEK_MS)
            "Up" -> PlayerKeyAction.Volume(VOLUME_STEP)
            "Down" -> PlayerKeyAction.Volume(-VOLUME_STEP)
            "Escape" -> PlayerKeyAction.ExitFullWindow
            "<" -> PlayerKeyAction.Speed(-SPEED_STEP)
            ">" -> PlayerKeyAction.Speed(SPEED_STEP)
            "Comma" -> if (shift) PlayerKeyAction.Speed(-SPEED_STEP) else null
            "Period" -> if (shift) PlayerKeyAction.Speed(SPEED_STEP) else null
            else -> single(key, shift)
        }
    }

    private fun single(key: String, shift: Boolean): PlayerKeyAction? {
        if (key.length != 1) return null
        val c = key[0]
        if (c in '0'..'9') return if (shift) null else PlayerKeyAction.SeekToFraction((c - '0') / 10f)
        return when (c.uppercaseChar()) {
            'K' -> PlayerKeyAction.TogglePlay
            'J' -> PlayerKeyAction.SeekBy(-JL_SEEK_MS)
            'L' -> PlayerKeyAction.SeekBy(JL_SEEK_MS)
            'M' -> PlayerKeyAction.ToggleMute
            'F' -> PlayerKeyAction.FullWindow
            'T' -> PlayerKeyAction.Theatre
            'C' -> PlayerKeyAction.Captions
            'N' -> if (shift) PlayerKeyAction.Next else null
            'P' -> if (shift) PlayerKeyAction.Previous else null
            else -> null
        }
    }
}
