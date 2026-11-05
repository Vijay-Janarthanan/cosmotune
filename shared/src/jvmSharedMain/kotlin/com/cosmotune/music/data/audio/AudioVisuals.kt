package com.cosmotune.music.data.audio

import com.cosmotune.music.data.MonotonicClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

data class VisualAudioFrame(val wave: FloatArray = FloatArray(128), val energy: Float = 0f, val atMs: Long = 0L)

/** A read-only PCM tap shared by the phone and desktop. No work when the view is closed. */
object AudioVisuals {
    private val frames = MutableStateFlow(VisualAudioFrame())
    val frame = frames.asStateFlow()
    private val last = AtomicLong(0)

    fun publish(samples: FloatArray, count: Int, gain: Float = 1f) {
        if (frames.subscriptionCount.value == 0 || count <= 0) return
        val now = MonotonicClock.nowMs()
        val before = last.get()
        if (before != 0L && now - before < 40 || !last.compareAndSet(before, now)) return
        frames.value = sample(samples, count, gain, now)
    }

    fun sample(samples: FloatArray, count: Int, gain: Float, atMs: Long = 0L): VisualAudioFrame {
        val n = count.coerceIn(0, samples.size)
        if (n == 0) return VisualAudioFrame(atMs = atMs)
        val wave = FloatArray(128) { i ->
            (samples[(i.toLong() * n / 128).toInt().coerceAtMost(n - 1)] * gain)
                .takeIf(Float::isFinite)?.coerceIn(-1f, 1f) ?: 0f
        }
        return VisualAudioFrame(wave, sqrt(wave.sumOf { it.toDouble() * it } / wave.size).toFloat(), atMs)
    }
}
