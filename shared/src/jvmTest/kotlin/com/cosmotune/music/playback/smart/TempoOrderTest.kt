package com.cosmotune.music.playback.smart

import com.cosmotune.music.data.model.Song
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TempoOrderTest {

    private fun song(id: String) = Song(videoId = id, title = id, artist = "", thumbnailUrl = null)

    @Test fun sortsByTempoWithUnmeasuredSongsLast() {
        val beats = mapOf("a" to TempoOrder.Beat(128.0), "b" to TempoOrder.Beat(90.0), "c" to TempoOrder.Beat(110.0))
        val songs = listOf(song("a"), song("x"), song("b"), song("c"))
        assertEquals(listOf("b", "c", "a", "x"), TempoOrder.byTempo(songs, { beats[it.videoId] }).map { it.videoId })
        assertEquals(listOf("a", "c", "b", "x"), TempoOrder.byTempo(songs, { beats[it.videoId] }, descending = true).map { it.videoId })
    }

    @Test fun smoothShuffleKeepsEverySongAndStepsGently() {
        val bpms = (0 until 40).map { 90.0 + it }.shuffled(Random(3))
        val songs = bpms.indices.map { song("s$it") }
        val beats = songs.zip(bpms).associate { (s, bpm) -> s.videoId to TempoOrder.Beat(bpm) }
        val order = TempoOrder.smoothShuffle(songs + song("unknown"), { beats[it.videoId] }, Random(7))
        assertEquals((songs + song("unknown")).map { it.videoId }.toSet(), order.map { it.videoId }.toSet())
        val steps = order.mapNotNull { beats[it.videoId]?.bpm }.zipWithNext { a, b -> abs(a - b) }
        val plainSteps = songs.shuffled(Random(7)).map { beats.getValue(it.videoId).bpm }.zipWithNext { a, b -> abs(a - b) }
        assertTrue(steps.average() < plainSteps.average() / 2, "smooth ${steps.average()} vs plain ${plainSteps.average()}")
    }

    @Test fun halfAndDoubleTimeCountAsClose() {
        assertTrue(TempoOrder.distance(TempoOrder.Beat(70.0), TempoOrder.Beat(140.0)) < 0.01)
        assertTrue(TempoOrder.distance(TempoOrder.Beat(120.0, "C major"), TempoOrder.Beat(120.0, "F# major")) > 0.05)
    }
}
