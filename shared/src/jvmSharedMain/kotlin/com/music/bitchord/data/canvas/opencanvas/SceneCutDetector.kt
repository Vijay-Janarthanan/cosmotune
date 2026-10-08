package com.opencanvas.core.filter

import kotlin.math.abs

/**
 * Detects hard scene edits / shot transitions between video frames.
 */
class SceneCutDetector(
    private val boxJumpThreshold: Float = 0.28f,
    private val histogramDeltaThreshold: Float = 0.40f,
) {
    private var lastCenterX: Float? = null
    private var lastHistogram: FloatArray? = null

    /**
     * Determines whether a sudden jump in actor center indicates a camera cut.
     */
    fun checkCenterJump(currentCenterX: Float): Boolean {
        val prev = lastCenterX
        lastCenterX = currentCenterX
        if (prev == null) return false

        val jump = abs(currentCenterX - prev)
        return jump >= boxJumpThreshold
    }

    /**
     * Checks if frame visual histograms differ significantly (indicating a shot change).
     */
    fun checkVisualDelta(currentHistogram: FloatArray): Boolean {
        val prev = lastHistogram
        lastHistogram = currentHistogram
        if (prev == null || prev.size != currentHistogram.size) return false

        // Compute Manhattan distance between normalized histograms
        var delta = 0f
        for (i in currentHistogram.indices) {
            delta += abs(currentHistogram[i] - prev[i])
        }
        val normalizedDelta = delta / 2f
        return normalizedDelta >= histogramDeltaThreshold
    }

    fun reset() {
        lastCenterX = null
        lastHistogram = null
    }
}
