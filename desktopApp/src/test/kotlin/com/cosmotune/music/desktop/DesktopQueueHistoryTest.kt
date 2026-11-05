package com.cosmotune.music.desktop

import com.cosmotune.music.data.model.Song
import org.junit.Test
import kotlin.test.assertEquals

/** Edits made from the "Previously played" rows above Now playing, on the desktop's queue model. */
class DesktopQueueHistoryTest {
    private fun queue(index: Int) = DesktopQueue(listOf("a", "b", "c", "d", "e").map { Song(it, it, "Artist", null) }, index)
    private fun DesktopQueue.ids() = songs.map { it.videoId }
    /** What the queue shows above Now playing: everything before the current index. */
    private fun DesktopQueue.history() = songs.take(index).map { it.videoId }

    @Test fun removingAHistoryRowKeepsThePlayingSongCurrent() {
        val after = queue(3).removeAt(1)
        assertEquals(listOf("a", "c", "d", "e"), after.ids())
        assertEquals("d", after.current?.videoId)
        assertEquals(listOf("a", "c"), after.history())
    }

    @Test fun choosingAHistoryRowGoesBackWithoutDroppingWhatFollows() {
        val after = queue(3).jumpTo(0)
        assertEquals(listOf("a", "b", "c", "d", "e"), after.ids())
        assertEquals("a", after.current?.videoId)
        assertEquals(emptyList(), after.history())
    }

    @Test fun reorderingWithinHistoryNeverMovesThePlayingSong() {
        val after = queue(3).move(0, 2)
        assertEquals(listOf("b", "c", "a", "d", "e"), after.ids())
        assertEquals("d", after.current?.videoId)
        assertEquals(listOf("b", "c", "a"), after.history())
    }

    @Test fun thePlayingRowCannotBeRemovedFromTheQueue() {
        assertEquals(queue(2), queue(2).removeAt(2))
    }
}
