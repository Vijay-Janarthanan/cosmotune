package com.cosmotune.video.player.controller

import kotlin.jvm.Synchronized

/**
 * How often the controller may ask for fresh streams after the engine reports an expired address (spec 6.1: "at most twice in
 * five minutes, then the error state"): [tryUse] says yes for at most [max] uses in any [windowMs]. A refusal uses nothing up.
 * [clock] is milliseconds (any origin; only differences count).
 */
class RefreshBudget(
    val max: Int = 2,
    val windowMs: Long = 5 * 60_000L,
    private val clock: () -> Long,
) {
    private val uses = ArrayDeque<Long>()

    /** Uses one refresh and returns true, or returns false when [max] were already used within the last [windowMs]. */
    @Synchronized
    fun tryUse(): Boolean {
        val now = clock()
        while (uses.isNotEmpty() && now - uses.first() >= windowMs) uses.removeFirst()
        if (uses.size >= max) return false
        uses.addLast(now)
        return true
    }
}
