package com.cosmotune.ui.sleep

import java.util.concurrent.CopyOnWriteArraySet

/**
 * What else the sleep timer pauses when it runs out, besides Music's own player: each mode with a player of its own (Video's,
 * which Watch also plays on) adds its pause here when that player is built and takes it away when it is released. Music's
 * timer only calls [pauseAll], so Music never names another mode and a mode left out of the build simply adds nothing.
 */
object SleepPausers {
    private val pausers = CopyOnWriteArraySet<() -> Unit>()

    fun add(pause: () -> Unit) { pausers += pause }

    fun remove(pause: () -> Unit) { pausers -= pause }

    /** Pauses every registered player; one that fails does not stop the others. Returns the failures' messages. */
    fun pauseAll(): List<String> = pausers.mapNotNull { pause -> runCatching(pause).exceptionOrNull()?.let { it.message ?: it.toString() } }
}
