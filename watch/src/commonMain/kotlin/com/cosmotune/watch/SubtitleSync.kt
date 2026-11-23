package com.cosmotune.watch

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/** One line's time on screen in a subtitle file, as the file gives it. */
data class CueTime(val startMs: Long, val endMs: Long)

/** A stretch of a copy's sound, mixed to one channel: what [SubtitleSync] listens to. */
class PcmClip(val startMs: Long, val sampleRate: Int, val samples: FloatArray)

/** Hears a stretch of a copy's sound: decodes [lengthMs] of [url]'s audio from [fromMs], or null when it cannot. */
fun interface WatchSpeechSampler {
    suspend fun sample(url: String, headers: Map<String, String>, fromMs: Long, lengthMs: Long): PcmClip?
}

/** Where the player's Sync automatically stands. */
sealed interface SubtitleSyncState {
    data object Idle : SubtitleSyncState
    data object Listening : SubtitleSyncState
    /** The subtitles now run [delayMs] late (negative: early), as heard. */
    data class Synced(val delayMs: Long) : SubtitleSyncState
    data class Failed(val reason: String) : SubtitleSyncState
}

/**
 * AutoSync: lines subtitles up with the sound. [cueTimes] reads the times out of an SRT, WebVTT, ASS/SSA or TTML file;
 * [speech] marks which 20 ms frames of a clip hold voices (energy above the clip's own floor, after a pre-emphasis that favours
 * the voice band over bass and score); [align] slides the subtitle lines across the voices and keeps the shift where the
 * most lines fall on speech, when it stands out clearly from every other shift.
 */
object SubtitleSync {
    const val FRAME_MS = 20
    /** How far either way a subtitle file may be off. */
    const val MAX_SHIFT_MS = 60_000L
    /** How much sound is heard. */
    const val LISTEN_MS = 90_000L
    private const val MIN_CUES = 6

    private val arrow = Regex("""((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})\s*-->\s*((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})""")
    private val dialogue = Regex("""^Dialogue:\s*[^,]*,\s*(\d+:\d{2}:\d{2}[.,]\d{1,3})\s*,\s*(\d+:\d{2}:\d{2}[.,]\d{1,3})""", RegexOption.MULTILINE)
    private val ttml = Regex("""begin\s*=\s*"([^"]+)"[^>]*?end\s*=\s*"([^"]+)"""")

    /** The lines' times in [text], sorted; empty when it holds none. */
    fun cueTimes(text: String): List<CueTime> {
        val found = mutableListOf<CueTime>()
        arrow.findAll(text).forEach { m -> clock(m.groupValues[1])?.let { s -> clock(m.groupValues[2])?.let { e -> found += CueTime(s, e) } } }
        if (found.isEmpty()) dialogue.findAll(text).forEach { m -> clock(m.groupValues[1])?.let { s -> clock(m.groupValues[2])?.let { e -> found += CueTime(s, e) } } }
        if (found.isEmpty()) ttml.findAll(text).forEach { m -> ttmlTime(m.groupValues[1])?.let { s -> ttmlTime(m.groupValues[2])?.let { e -> found += CueTime(s, e) } } }
        return found.filter { it.endMs > it.startMs }.sortedBy { it.startMs }
    }

    private fun clock(value: String): Long? {
        val parts = value.replace(',', '.').split(':')
        val seconds = parts.last().toDoubleOrNull() ?: return null
        val minutes = parts.getOrNull(parts.size - 2)?.toLongOrNull() ?: return null
        val hours = if (parts.size >= 3) parts[parts.size - 3].toLongOrNull() ?: return null else 0
        return hours * 3_600_000 + minutes * 60_000 + (seconds * 1000).toLong()
    }

    private fun ttmlTime(value: String): Long? = when {
        value.endsWith("ms") -> value.dropLast(2).toDoubleOrNull()?.toLong()
        value.endsWith("s") -> value.dropLast(1).toDoubleOrNull()?.let { (it * 1000).toLong() }
        else -> clock(value)
    }

    /** Which [FRAME_MS] frames of [clip] hold voices, or null when the sound is too even to tell voices apart. */
    fun speech(clip: PcmClip): BooleanArray? {
        val perFrame = clip.sampleRate * FRAME_MS / 1000
        if (perFrame <= 0) return null
        val frames = clip.samples.size / perFrame
        if (frames < 50) return null
        val levels = DoubleArray(frames)
        var previous = 0f
        for (f in 0 until frames) {
            var energy = 0.0
            for (i in f * perFrame until (f + 1) * perFrame) {
                val x = clip.samples[i]
                val y = x - 0.97f * previous
                previous = x
                energy += (y * y).toDouble()
            }
            levels[f] = 10 * log10(energy / perFrame + 1e-10)
        }
        val sorted = levels.sorted()
        val floor = sorted[(frames * 0.2).toInt()]
        val loud = sorted[(frames * 0.95).toInt().coerceAtMost(frames - 1)]
        if (loud - floor < 6.0) return null
        val threshold = floor + 0.45 * (loud - floor)
        val raw = BooleanArray(frames) { levels[it] > threshold }
        // Smooths it: a voice holds for more than one frame, and a breath inside a line is still the line.
        val smoothed = BooleanArray(frames) { i ->
            var on = 0
            for (j in (i - 2).coerceAtLeast(0)..(i + 2).coerceAtMost(frames - 1)) if (raw[j]) on++
            on >= 3
        }
        var lastOn = -100
        for (i in 0 until frames) {
            if (smoothed[i]) {
                if (i - lastOn in 2..(300 / FRAME_MS)) for (j in lastOn + 1 until i) smoothed[j] = true
                lastOn = i
            }
        }
        return smoothed
    }

    /**
     * The delay (ms, positive: show the lines later) that lines [cues] up with [speech], heard from [startMs]; null when no shift
     * within [maxShiftMs] stands out, or too few lines fall near the clip.
     */
    fun align(speech: BooleanArray, startMs: Long, cues: List<CueTime>, maxShiftMs: Long = MAX_SHIFT_MS): Long? {
        val n = speech.size
        val shift = (maxShiftMs / FRAME_MS).toInt()
        val from = startMs - maxShiftMs
        val total = n + 2 * shift
        val near = cues.filter { it.endMs > from && it.startMs < from + total.toLong() * FRAME_MS }
        if (near.size < MIN_CUES) return null
        val mask = IntArray(total) { -1 }
        near.forEach { cue ->
            val a = ((cue.startMs - from) / FRAME_MS).toInt().coerceAtLeast(0)
            val b = ((cue.endMs - from) / FRAME_MS).toInt().coerceAtMost(total)
            for (j in a until b) mask[j] = 1
        }
        val s = IntArray(n) { if (speech[it]) 1 else -1 }
        // A frame i of the clip (time startMs + i*F) meets the line shown there after a delay of d frames: the file's time startMs + i*F - d*F.
        val scores = IntArray(2 * shift + 1)
        for (k in 0..2 * shift) {
            val d = k - shift
            var score = 0
            val offset = shift - d
            for (i in 0 until n) score += s[i] * mask[i + offset]
            scores[k] = score
        }
        var best = 0
        for (k in scores.indices) if (scores[k] > scores[best]) best = k
        // How many lines would sit inside the clip at that shift.
        val bestDelay = (best - shift).toLong() * FRAME_MS
        val inside = near.count { it.startMs + bestDelay >= startMs && it.endMs + bestDelay <= startMs + n.toLong() * FRAME_MS }
        if (inside < MIN_CUES) return null
        val mean = scores.average()
        val sd = sqrt(scores.sumOf { (it - mean) * (it - mean) } / scores.size)
        if (sd <= 0.0 || (scores[best] - mean) / sd < 3.5) return null
        // A second shift, well away from the best, almost as good: the voices do not tell them apart.
        val apart = 1_500 / FRAME_MS
        val second = scores.indices.filter { abs(it - best) > apart }.maxOfOrNull { scores[it] } ?: Int.MIN_VALUE
        if (second != Int.MIN_VALUE && (scores[best] - second).toDouble() / n < 0.02) return null
        return kotlin.math.round(bestDelay / 50.0).toLong() * 50
    }
}

/** The Sync automatically row's second line. */
fun SubtitleSyncState.detail(): String = when (this) {
    SubtitleSyncState.Idle -> "Listens to the sound here and lines the subtitles up"
    SubtitleSyncState.Listening -> "Listening to the sound…"
    is SubtitleSyncState.Synced -> when {
        kotlin.math.abs(delayMs) < 100 -> "They were already in time"
        delayMs > 0 -> "Moved ${tenths(delayMs)} s later"
        else -> "Moved ${tenths(-delayMs)} s earlier"
    }
    is SubtitleSyncState.Failed -> reason
}

private fun tenths(ms: Long): String = "${ms / 1000}.${(ms % 1000) / 100}"
