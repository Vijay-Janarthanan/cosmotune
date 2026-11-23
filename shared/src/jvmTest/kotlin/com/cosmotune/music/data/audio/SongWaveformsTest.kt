package com.cosmotune.music.data.audio

import com.cosmotune.video.account.TimedComments
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SongWaveformsTest {
    @Test fun aShapeAppearsOnceEnoughOfTheSongHasBeenHeard() {
        val shape = WaveShape()
        shape.record(0.01f, 0.5f)
        assertNull(shape.bars())
        for (bar in 0 until 20) shape.record(bar / WaveShape.BARS.toFloat(), if (bar == 5) 1f else 0.5f)
        val bars = assertNotNull(shape.bars())
        assertEquals(1f, bars[5])
        assertEquals(0.5f, bars[6], 0.001f)
        assertEquals(WaveShape.FLOOR, bars[50])
    }

    @Test fun aBarKeepsTheLoudestItHeard() {
        val shape = WaveShape()
        assertTrue(shape.record(0.5f, 0.8f))
        assertTrue(!shape.record(0.5f, 0.2f))
        assertTrue(!shape.record(1.5f, 0.9f))
    }

    @Test fun shapesSurviveTheirSmallFile() {
        val shape = WaveShape()
        for (bar in 0 until 30) shape.record(bar / WaveShape.BARS.toFloat(), bar / 30f)
        val again = assertNotNull(WaveShape.decode(shape.encode()))
        assertEquals(shape.heard(), again.heard())
        assertContentEquals(shape.encode(), again.encode())
        assertNull(WaveShape.decode(ByteArray(3)))
    }

    @Test fun shapesAreSavedAndReadBack() {
        val folder = kotlin.io.path.createTempDirectory("waves").toFile()
        SongWaveforms.install(folder)
        for (bar in 0 until 40) SongWaveforms.record("song-a", bar / WaveShape.BARS.toFloat(), 0.6f)
        SongWaveforms.save("song-a")
        assertEquals(1, folder.listFiles { file -> file.name.endsWith(".wave") }!!.size)
        assertNotNull(SongWaveforms.bars("song-a"))
        folder.deleteRecursively()
    }

    @Test fun commentsNameTheirFirstMoment() {
        assertEquals(134_000L, TimedComments.firstMoment("2:14 this part gets me every time"))
        assertEquals(3_723_000L, TimedComments.firstMoment("at 1:02:03 wow"))
        assertEquals(65_000L, TimedComments.firstMoment("the bridge (1:05) and 3:00"))
        assertNull(TimedComments.firstMoment("released 2019, 10/10"))
        assertNull(TimedComments.firstMoment("12:345 nope"))
    }

    @Test fun closeMomentsKeepTheBestLikedComment() {
        val picked = TimedComments.pick(listOf(
            Triple("1:00 quiet one", "a", 3L),
            Triple("1:01 loud one", "b", 90L),
            Triple("no moment here", "c", 500L),
            Triple("0:30   the   intro", "d", 1L),
        ))
        assertEquals(listOf(30_000L, 61_000L), picked.map { it.atMs })
        assertEquals("0:30 the intro", picked[0].text)
        assertEquals("b", picked[1].author)
    }
}
