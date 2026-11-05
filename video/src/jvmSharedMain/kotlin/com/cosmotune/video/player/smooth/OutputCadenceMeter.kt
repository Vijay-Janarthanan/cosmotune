package com.cosmotune.video.player.smooth

import kotlin.math.roundToInt

/** Measures changed render submissions, including slow gaps; repeated held pictures do not become higher FPS. */
class OutputCadenceMeter {
    private val cadence = FpsMeter(window = 60, staleNanos = 1_000_000_000L, rejectOutliers = false)
    private var initialized = false
    private var older = Long.MIN_VALUE
    private var newer = Long.MIN_VALUE
    private var blend = -1

    @Synchronized fun tick(nowNanos: Long, olderId: Long, newerId: Long, phase: Float) {
        val step = (phase.coerceIn(0f, 1f) * 1024).roundToInt()
        val a = when (step) { 0 -> olderId; 1024 -> newerId; else -> olderId }
        val b = if (step == 0 || step == 1024) a else newerId
        val weight = if (a == b) 0 else step
        if (initialized && a == older && b == newer && weight == blend) return
        initialized = true
        older = a; newer = b; blend = weight
        cadence.tick(nowNanos)
    }

    fun fps(nowNanos: Long): Float? = cadence.fps(nowNanos)

    @Synchronized fun reset() {
        initialized = false
        cadence.reset()
    }
}
