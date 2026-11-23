package com.cosmotune.canvas

import com.opencanvas.core.stream.PreviewFrames
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A poster uploaded with the song, its colours pulsing or flashing to the beat (a zoom, particles or visualiser bars beside
 * them), changes its preview frames as much as a video does, so "preview frames: moving" let one through. Its shapes stay:
 * the frames are one picture. Measured on the owner's log: posters at frame motion 0.07 (still) and 0.08 (with effects),
 * video songs at 0.38, 0.47 and 0.67.
 */
class PosterWithEffectsTest {
    private val poster = DoubleArray(64) { cell -> 40.0 + 170.0 * (((cell % 8) * 3 + (cell / 8) * 5) % 11) / 10.0 }

    /** The poster with its brightness and contrast pulsing, a little noise, and bars along its bottom row. */
    private fun pulsingPoster(frames: Int, seed: Int = 3): List<DoubleArray> {
        val random = Random(seed)
        return List(frames) { i ->
            if (i == 0) DoubleArray(64) { 8.0 } // the opening card, left out
            else {
                val gain = 0.55 + 0.45 * sin(i * PI / 3)
                val lift = 60.0 * (i % 4) / 3
                DoubleArray(64) { cell ->
                    val bars = if (cell >= 56) random.nextDouble() * 60 else 0.0
                    (poster[cell] * gain + lift + bars + random.nextDouble() * 6 - 3).coerceIn(0.0, 255.0)
                }
            }
        }
    }

    /** A video song: a new scene every few frames, each a different picture. */
    private fun videoSong(frames: Int, seed: Int = 5): List<DoubleArray> {
        val random = Random(seed)
        var scene = DoubleArray(64) { random.nextDouble() * 255 }
        return List(frames) { i ->
            if (i % 3 == 0) scene = DoubleArray(64) { random.nextDouble() * 255 }
            scene.map { (it + random.nextDouble() * 20 - 10).coerceIn(0.0, 255.0) }.toDoubleArray()
        }
    }

    @Test fun aPulsingPosterChangesLikeAVideoButIsOnePicture() {
        val frames = pulsingPoster(60)
        assertEquals(false, PreviewFrames.isStill(frames), "its brightness changes too much to read as still")
        val sameness = PreviewFrames.sameness(frames)!!
        assertTrue(sameness >= PreviewFrames.POSTER_SAMENESS, "one picture: $sameness")
    }

    @Test fun aVideoSongIsNotOnePicture() {
        val sameness = PreviewFrames.sameness(videoSong(60))!!
        assertTrue(sameness < PreviewFrames.POSTER_SAMENESS, "scenes change: $sameness")
    }

    @Test fun thePosterIsRejectedAtTheMotionsMeasuredAndTheVideosAreNot() {
        val poster = PreviewFrames.sameness(pulsingPoster(60))
        val video = PreviewFrames.sameness(videoSong(60))
        assertTrue(PreviewFrames.isPosterWithEffects(poster, videoMotion = 0.07))
        assertTrue(PreviewFrames.isPosterWithEffects(poster, videoMotion = 0.08), "the reported poster with flashing colours")
        for (motion in listOf(0.38, 0.47, 0.67)) {
            assertFalse(PreviewFrames.isPosterWithEffects(video, motion), "a video song at motion $motion")
            assertFalse(PreviewFrames.isPosterWithEffects(poster, motion), "a picture whose file moves this much is not judged a poster")
        }
        assertFalse(PreviewFrames.isPosterWithEffects(null, 0.08), "frames not read: the frame sizes decide, as before")
    }
}
