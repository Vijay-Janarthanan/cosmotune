package com.cosmotune.music.playback.smart

import com.cosmotune.music.data.model.Song
import kotlin.math.abs
import kotlin.random.Random

/**
 * Songs by tempo, from the beats and keys Automix already measured for the songs that have played: sort by tempo, and a smooth
 * shuffle that is still random but steps from each song to one of the few closest in tempo and in a key that sits well next to
 * it, so the energy drifts instead of jumping. Songs not measured yet are spread through at random. Idea from Namida.
 */
object TempoOrder {

    /** What is known of a song's beat: its [bpm] and its [key] ("A minor"), blank when the analyzer was not sure. */
    data class Beat(val bpm: Double, val key: String = "")

    /** [songs] slowest first (or fastest first when [descending]); songs not measured yet keep their order at the end. */
    fun byTempo(songs: List<Song>, beatOf: (Song) -> Beat?, descending: Boolean = false): List<Song> {
        val (known, unknown) = songs.partition { beatOf(it) != null }
        val sorted = known.sortedBy { beatOf(it)!!.bpm }
        return (if (descending) sorted.reversed() else sorted) + unknown
    }

    /** A shuffle whose neighbours are close in tempo and key; see the class. */
    fun smoothShuffle(songs: List<Song>, beatOf: (Song) -> Beat?, random: Random = Random.Default): List<Song> {
        val (known, unknown) = songs.partition { beatOf(it) != null }
        val left = known.toMutableList()
        val path = ArrayList<Song>(songs.size)
        if (left.isNotEmpty()) {
            var at = left.removeAt(random.nextInt(left.size))
            path += at
            while (left.isNotEmpty()) {
                val here = beatOf(at)!!
                val nearest = left.sortedBy { distance(here, beatOf(it)!!) }.take(CHOICES)
                at = nearest[random.nextInt(nearest.size)]
                left.remove(at)
                path += at
            }
        }
        unknown.shuffled(random).forEach { song -> path.add(random.nextInt(path.size + 1), song) }
        return path
    }

    /** How far apart two beats feel: the tempo step as a share (half and double time count as the same), plus a step for keys that clash. */
    internal fun distance(a: Beat, b: Beat): Double {
        val ratio = b.bpm / a.bpm
        val step = listOf(ratio, ratio * 2, ratio / 2).minOf { abs(1 - it) }
        val clash = if (a.key.isBlank() || b.key.isBlank() || harmonicallyCompatible(a.key, b.key)) 0.0 else KEY_CLASH
        return step + clash
    }

    /** Each step picks among this many closest songs, so the order is not the same every time. */
    private const val CHOICES = 3

    /** A key clash weighs as much as a 6 % tempo jump. */
    private const val KEY_CLASH = 0.06
}
