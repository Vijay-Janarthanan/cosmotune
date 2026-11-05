package com.opencanvas.core.filter

import com.opencanvas.core.models.DetectionBox
import kotlin.math.*

@kotlinx.serialization.Serializable
data class FramingDecision(val cx: Float = .5f, val cy: Float = .5f, val fitFullFrame: Boolean = true, val sceneCut: Boolean = false)

/** Shot-local subject continuity, group containment and bounded camera movement. */
class SubjectCamera(private val sourceAspect: Float, private val targetAspect: Float) {
    private var previous: DetectionBox? = null
    private var lastTime = Double.NaN
    private var keepWideUntil = Double.NEGATIVE_INFINITY
    private var last = FramingDecision()

    fun update(faces: List<DetectionBox>, people: List<DetectionBox>, time: Double, sceneCut: Boolean = false): FramingDecision {
        require(time.isFinite() && sourceAspect > 0 && targetAspect > 0)
        val reset = sceneCut || !lastTime.isFinite() || time < lastTime || time - lastTime > 1.5
        if (reset) { previous = null; keepWideUntil = Double.NEGATIVE_INFINITY }
        val detections = faces.ifEmpty { people }.filter { it.confidence >= .5 && it.area > 0 }
        val main = detections.maxByOrNull { box ->
            val continuity = previous?.let { 1 - abs(it.centerX - box.centerX) + 2 * it.iou(box) } ?: 0f
            sqrt(box.area) * box.confidence + continuity
        }
        val dt = if (reset) 0f else (time - lastTime).toFloat().coerceIn(0f, .5f)
        lastTime = time
        if (main == null) {
            previous = null
            keepWideUntil = time + .6
            return FramingDecision(sceneCut = reset).also { last = it }
        }
        previous = main
        val bodies = people.filter { person -> person.confidence >= .5f && person.area >= (people.maxOfOrNull { it.area } ?: 0f) * .25f }
        val significant = if (bodies.isNotEmpty()) bodies else detections.filter { it.area >= main.area * .35f }
        val left = significant.minOf { it.xMin }
        val right = significant.maxOf { it.xMax }
        val top = significant.minOf { it.yMin }
        val bottom = significant.maxOf { it.yMax }
        val cropWidth = min(1f, targetAspect / sourceAspect)
        val cropHeight = min(1f, sourceAspect / targetAspect)
        if (right - left > cropWidth * .9f || bottom - top > cropHeight * .92f) {
            keepWideUntil = time + 1.0
            return FramingDecision(fitFullFrame = true, sceneCut = reset).also { last = it }
        }
        // A body detector can flicker as an actor turns. Keep the wider composition briefly
        // instead of alternating fit/crop every sample; a real cut or seek clears the hold.
        if (time < keepWideUntil) return FramingDecision(sceneCut = reset).also { last = it }
        val desiredX = ((left + right) / 2).coerceIn(cropWidth / 2, 1 - cropWidth / 2)
        val desiredY = ((top + bottom) / 2 - (bottom - top) * .08f).coerceIn(cropHeight / 2, 1 - cropHeight / 2)
        fun move(from: Float, to: Float): Float = when {
            reset || last.fitFullFrame -> to
            abs(to - from) < .015f -> from
            else -> from + (to - from).coerceIn(-.65f * dt, .65f * dt)
        }
        return FramingDecision(move(last.cx, desiredX), move(last.cy, desiredY), false, reset).also { last = it }
    }
}
