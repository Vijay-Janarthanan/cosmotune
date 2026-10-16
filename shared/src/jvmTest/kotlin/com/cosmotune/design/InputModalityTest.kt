package com.cosmotune.design

import com.cosmotune.design.InputModality.Event.NAVIGATION_KEY
import com.cosmotune.design.InputModality.Event.OTHER_KEY
import com.cosmotune.design.InputModality.Event.POINTER_DOWN
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The focus ring is for keyboard focus only, like `:focus-visible`: a control that took focus because it was clicked or touched
 * shows no ring; one that took it from Tab or the arrows (or has it while Space or Enter are pressed on it) does. The question is
 * "was the last thing the person did a key of navigation?", answered by this state machine and nothing else.
 */
class InputModalityTest {

    private fun run(vararg events: InputModality.Event, from: Boolean = false): Boolean = events.fold(from) { state, event -> InputModality.next(state, event) }

    @Test
    fun theAppStartsInPointerModalitySoNothingThatTakesFocusByItselfShowsARing() {
        assertFalse(InputModality.INITIAL)
        assertFalse(InputModality.ringShown(focused = true, keyboard = InputModality.INITIAL), "a field focused at launch has no ring")
    }

    @Test
    fun aKeyOfNavigationMakesItKeyboardModality() {
        assertTrue(run(NAVIGATION_KEY))
        assertTrue(run(NAVIGATION_KEY, NAVIGATION_KEY))
    }

    @Test
    fun aPressOfAPointerMakesItPointerModalityWhetherItWasAMouseOrAFinger() {
        assertFalse(run(POINTER_DOWN))
        assertFalse(run(POINTER_DOWN, from = true))
    }

    @Test
    fun aKeyThatDoesNotNavigateChangesNothing() {
        assertFalse(run(OTHER_KEY), "a letter typed, a media key or a lone Shift does not bring the ring")
        assertTrue(run(OTHER_KEY, from = true), "and does not take it away")
    }

    @Test
    fun theLastThingThePersonDidDecides() {
        assertFalse(run(NAVIGATION_KEY, POINTER_DOWN), "Tab, then a click: no ring")
        assertTrue(run(POINTER_DOWN, NAVIGATION_KEY), "a click, then Tab: the ring")
        assertTrue(run(POINTER_DOWN, NAVIGATION_KEY, OTHER_KEY), "a letter after Tab keeps it")
        assertFalse(run(NAVIGATION_KEY, OTHER_KEY, POINTER_DOWN, OTHER_KEY), "a letter after a click does not bring it back")
    }

    @Test
    fun aClickedSidebarItemShowsNoRingAndATabbedOneDoes() {
        // the desktop: a mouse click gives the item focus and used to draw the 3 dp ring around it
        assertFalse(InputModality.ringShown(focused = true, keyboard = run(POINTER_DOWN)))
        assertTrue(InputModality.ringShown(focused = true, keyboard = run(POINTER_DOWN, NAVIGATION_KEY)))
    }

    @Test
    fun aControlWithoutFocusNeverShowsARing() {
        assertFalse(InputModality.ringShown(focused = false, keyboard = true))
        assertFalse(InputModality.ringShown(focused = false, keyboard = false))
    }

    @Test
    fun everyEventIsHandled() {
        for (event in InputModality.Event.entries) for (state in listOf(false, true)) {
            val next = InputModality.next(state, event)
            assertEquals(next, InputModality.next(state, event), "$event from $state is a function of its arguments")
        }
    }
}
