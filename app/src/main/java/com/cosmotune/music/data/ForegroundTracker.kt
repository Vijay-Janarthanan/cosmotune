package com.cosmotune.music.data

/**
 * Counts the app's started screens: [started] is true only for a comeback (the first screen after every screen had stopped, not
 * the launch's own first one), and [inFront] says whether any screen is showing now. Called on the main thread.
 */
class ForegroundTracker {
    private var count = 0
    private var launched = false

    val inFront: Boolean get() = count > 0

    /** A screen started; true when this brings the app back to the front after it had gone. */
    fun started(): Boolean {
        count++
        if (count != 1) return false
        if (!launched) {
            launched = true
            return false
        }
        return true
    }

    fun stopped() {
        if (count > 0) count--
    }
}
