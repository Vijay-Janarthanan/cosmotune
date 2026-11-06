package com.cosmotune.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.components.SpacePlayPause
import java.awt.event.KeyEvent.KEY_PRESSED
import java.awt.event.KeyEvent.KEY_RELEASED
import java.awt.event.KeyEvent.KEY_TYPED
import java.awt.event.KeyEvent.VK_SPACE
import java.awt.event.KeyEvent.VK_UNDEFINED
import javax.swing.JPanel
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Space plays and pauses on the desktop as in every player, but only when nothing that has the keyboard wants the key. The
 * events are the ones the window really gets for one press of Space (pressed, typed, released, as AWT sends them, converted
 * the way the window converts them), sent to whatever has focus; [SpacePlayPause] sits where the window's own `onKeyEvent`
 * does, after everything focused has had its turn.
 */
class SpacePlayPauseInteractionTest {
    @get:Rule val compose = createComposeRule()

    private var toggles = 0
    private var clicks = 0
    private var text by mutableStateOf("")

    private fun setUp() {
        val space = SpacePlayPause()
        compose.setContent {
            Box(Modifier.onKeyEvent { space.onKeyEvent(it) { toggles++; true } }) {
                Column {
                    BasicTextField(text, { text = it }, Modifier.testTag("field"))
                    Box(Modifier.size(40.dp).testTag("button").clickable { clicks++ })
                    Box(Modifier.size(40.dp).testTag("page").focusable())
                }
            }
        }
    }

    private val source = JPanel()

    /** The window's own conversion of an AWT key event (internal to Compose, so reached the way the window reaches it). */
    private fun java.awt.event.KeyEvent.toComposeEvent(): androidx.compose.ui.input.key.KeyEvent {
        val internal = Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt")
            .getMethod("toComposeEvent", java.awt.event.KeyEvent::class.java).invoke(null, this)
        return androidx.compose.ui.input.key.KeyEvent(internal)
    }

    private fun SemanticsNodeInteraction.space(repeats: Int = 0) {
        val down = java.awt.event.KeyEvent(source, KEY_PRESSED, 0L, 0, VK_SPACE, ' ')
        val typed = java.awt.event.KeyEvent(source, KEY_TYPED, 0L, 0, VK_UNDEFINED, ' ')
        val up = java.awt.event.KeyEvent(source, KEY_RELEASED, 0L, 0, VK_SPACE, ' ')
        repeat(repeats + 1) {
            performKeyPress(down.toComposeEvent())
            performKeyPress(typed.toComposeEvent())
        }
        performKeyPress(up.toComposeEvent())
        compose.waitForIdle()
    }

    @Test fun spaceOnThePagePlaysAndPauses() {
        setUp()
        val page = compose.onNodeWithTag("page").requestFocus()
        page.space()
        assertEquals(1, toggles, "one press, one toggle")
        page.space()
        assertEquals(2, toggles, "and again")
    }

    @Test fun holdingSpaceTogglesOnce() {
        setUp()
        compose.onNodeWithTag("page").requestFocus().space(repeats = 3)
        assertEquals(1, toggles, "the key's repeats are not more presses")
    }

    @Test fun aTextFieldKeepsItsSpace() {
        setUp()
        compose.onNodeWithTag("field").requestFocus().space()
        assertEquals(" ", text, "the space is typed")
        assertEquals(0, toggles, "and the music is left alone")
    }

    @Test fun aFocusedButtonTakesSpaceItself() {
        setUp()
        compose.onNodeWithTag("button").requestFocus().space()
        assertEquals(1, clicks, "Space presses the focused button")
        assertEquals(0, toggles, "and does not also play or pause")
    }
}
