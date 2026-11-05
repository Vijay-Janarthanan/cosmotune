package com.cosmotune.video.select

import kotlin.jvm.Synchronized

/**
 * The measured bandwidth counts only after the first 5 s of measuring (spec 6.4: Auto fits the measured bandwidth "after the first
 * 5 s; before that, the setting alone"). A meter's first answers are a guess (Android's meter starts from a table of typical
 * speeds) or a single piece; the engines pass what they report through [gate], which answers null until measuring has run for
 * [warmUpMs], so the selector ([StreamSelector], which takes any positive value as known) uses the setting alone until then.
 *
 * Measuring starts at [begin] (the engine's first transfer; later calls do nothing), again at [restart] (a new meter), or at the
 * first [gate] when neither was called. Safe to call from any thread.
 */
class BandwidthWarmUp(private val clock: () -> Long, val warmUpMs: Long = WARM_UP_MS) {
    private var startedAtMs: Long? = null

    /** Measuring starts now, unless it already has. */
    @Synchronized
    fun begin() {
        if (startedAtMs == null) startedAtMs = clock()
    }

    /** Measuring starts again now (a new meter, whose estimate starts from nothing). */
    @Synchronized
    fun restart() {
        startedAtMs = clock()
    }

    /** [kbps] once measuring has run for [warmUpMs], else null; an unknown or empty value is null. */
    @Synchronized
    fun gate(kbps: Int?): Int? {
        if (kbps == null || kbps <= 0) return null
        val started = startedAtMs ?: clock().also { startedAtMs = it }
        return if (clock() - started >= warmUpMs) kbps else null
    }

    companion object {
        /** How long the measuring runs before its answers count (spec 6.4: 5 s). */
        const val WARM_UP_MS = 5_000L
    }
}
