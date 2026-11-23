package com.cosmotune.music.playback.smart

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The mix view (BitChord's Automix overview): what Automix measured in a song and how it means to go into the next one, in words a
 * listener reads. Times are seconds into the song; [fractions] place them on the song's length for the drawing.
 */
data class MixOverview(
    val tempo: String,
    val key: String?,
    val lengthS: Double,
    val introEndS: Double,
    val mixInS: Double,
    val mixOutS: Double,
    val outroStartS: Double,
    val energy: List<Double>,
    val next: String?,
) {
    fun fraction(seconds: Double): Float = if (lengthS <= 0) 0f else (seconds / lengthS).coerceIn(0.0, 1.0).toFloat()

    companion object {
        /** The overview of [song], with [next] (the song after it) when that is analysed too; null while [song] is not analysed. */
        fun of(song: TrackAnalysis?, next: TrackAnalysis? = null): MixOverview? {
            if (song == null || !song.isUsable || song.duration <= 0) return null
            val curve = song.energyCurve.map { it.energy }
            val peak = curve.maxOrNull()?.takeIf { it > 0 } ?: 1.0
            return MixOverview(
                tempo = "${song.bpm.roundToInt()} BPM",
                key = song.key.takeIf { it.isNotBlank() && song.keyConfidence >= 0.25 },
                lengthS = song.duration,
                introEndS = song.introEndTime.coerceIn(0.0, song.duration),
                mixInS = song.mixInTime.coerceIn(0.0, song.duration),
                mixOutS = (song.mixOutTime.takeIf { it > 0 } ?: song.duration).coerceIn(0.0, song.duration),
                outroStartS = (song.outroStartTime.takeIf { it > 0 } ?: song.duration).coerceIn(0.0, song.duration),
                energy = curve.map { (it / peak).coerceIn(0.0, 1.0) },
                next = next?.takeIf { it.isUsable }?.let { describeNext(song, it) },
            )
        }

        private fun describeNext(song: TrackAnalysis, next: TrackAnalysis): String {
            val gap = abs(next.bpm - song.bpm)
            val tempo = when {
                gap < 3 -> "the same tempo"
                gap < 10 -> "a close tempo (${next.bpm.roundToInt()} BPM)"
                else -> "a different tempo (${next.bpm.roundToInt()} BPM)"
            }
            val key = next.key.takeIf { it.isNotBlank() && next.keyConfidence >= 0.25 }?.let { " in $it" }.orEmpty()
            return "The next song is at $tempo$key."
        }

        /** "3:05". */
        fun time(seconds: Double): String { val s = seconds.coerceAtLeast(0.0).roundToInt(); return "%d:%02d".format(s / 60, s % 60) }
    }
}
