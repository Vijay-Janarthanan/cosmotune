package com.cosmotune.ui.kit

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint

/**
 * Space as play / pause for a whole window, as every player has it, without taking Space from whatever has the keyboard.
 *
 * Fed the key events that reached the window unclaimed (a window's `onKeyEvent`, which runs after the focused element has
 * had them), it never sees a Space that something else wanted:
 *  - a focused button, switch or row takes Space itself (it presses on the key's down and clicks on its up), so its press
 *    never arrives here and this does nothing;
 *  - a focused text field lets the key's down and up pass but keeps the character Space types, so the character never
 *    arrives here and this does nothing either.
 * So the press counts only when its down and the character it types both reach the window: then [onKeyEvent] calls its toggle
 * once (holding Space down repeats the character, not the toggle) and claims the character and the key's up.
 *
 * One instance per window: it remembers the press in flight between the events of one key.
 */
class SpacePlayPause {
    /** Space is down: the key repeats while held, and a repeat is not a second press. */
    private var held = false

    /** This press's down reached the window and nothing has toggled for it yet. */
    private var armed = false

    /** This press toggled, so its up is claimed too. */
    private var toggled = false

    /**
     * Takes one unclaimed key event of the window. [toggle] plays or pauses whatever the window plays and says whether there
     * was anything to toggle. True when the event was used.
     */
    fun onKeyEvent(event: KeyEvent, toggle: () -> Boolean): Boolean {
        val plain = !event.isCtrlPressed && !event.isMetaPressed && !event.isAltPressed
        return when {
            event.type == KeyEventType.KeyDown && event.key == Key.Spacebar -> {
                if (!held) {
                    held = true
                    armed = plain
                    toggled = false
                }
                false
            }
            // The character Space typed (the platform sends it between the key's down and up as an event of no key type).
            event.type == KeyEventType.Unknown && event.utf16CodePoint == SPACE -> {
                if (!armed || !plain) return false
                armed = false
                toggled = toggle()
                toggled
            }
            event.type == KeyEventType.KeyUp && event.key == Key.Spacebar -> {
                val claim = toggled
                held = false
                armed = false
                toggled = false
                claim
            }
            else -> false
        }
    }

    private companion object {
        const val SPACE = ' '.code
    }
}
