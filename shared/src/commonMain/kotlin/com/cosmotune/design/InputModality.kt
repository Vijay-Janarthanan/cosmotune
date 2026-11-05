package com.cosmotune.design

/**
 * Whether the ring of a focused control is drawn, decided like the web's `:focus-visible`: only while the last thing the person
 * did was press a key of navigation (Tab, the arrows, Space, Enter, ...). A control that took focus because it was clicked or touched
 * shows no ring; pressing a pointer takes the ring away again. A pure state machine, so the rule is tested without a window.
 */
object InputModality {
    /** What the person just did. */
    enum class Event {
        /** A key that moves or activates focus went down or up. */
        NAVIGATION_KEY,

        /** Any other key: a letter, a media key, a lone Shift. */
        OTHER_KEY,

        /** A mouse button or a finger went down. */
        POINTER_DOWN,
    }

    /** Before the first thing the person does, no ring: a field that takes focus at launch must not show one. */
    const val INITIAL = false

    /** True while the keyboard is the way the person is moving about (so a ring is due), given the [keyboard] state before [event]. */
    fun next(keyboard: Boolean, event: Event): Boolean = when (event) {
        Event.NAVIGATION_KEY -> true
        Event.OTHER_KEY -> keyboard
        Event.POINTER_DOWN -> false
    }

    /** Whether a control draws its ring: it has focus and the person is on the keyboard. */
    fun ringShown(focused: Boolean, keyboard: Boolean): Boolean = focused && keyboard
}
