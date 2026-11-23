package com.cosmotune.video.player

import com.cosmotune.video.model.Chapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChapterSkipTest {
    private val chapters = listOf(Chapter("Intro", 0, null), Chapter("Two", 60_000, null), Chapter("Three", 120_000, null))

    @Test fun nextGoesToTheFollowingChapterAndStopsAtTheLast() {
        assertEquals(60_000L, ChapterSkip.next(chapters, 10_000))
        assertEquals(120_000L, ChapterSkip.next(chapters, 60_000))
        assertNull(ChapterSkip.next(chapters, 130_000))
        assertNull(ChapterSkip.next(emptyList(), 0))
    }

    @Test fun previousRestartsTheChapterOrGoesBackOneNearItsStart() {
        assertEquals(60_000L, ChapterSkip.previous(chapters, 90_000))
        assertEquals(0L, ChapterSkip.previous(chapters, 61_000))
        assertEquals(0L, ChapterSkip.previous(chapters, 1_000))
        assertNull(ChapterSkip.previous(emptyList(), 5_000))
    }

    @Test fun shiftArrowsAreTheChapterKeys() {
        assertEquals(PlayerKeyAction.NextChapter, PlayerKeyMap.action("Right", shift = true, ctrlOrCmd = false))
        assertEquals(PlayerKeyAction.PreviousChapter, PlayerKeyMap.action("Left", shift = true, ctrlOrCmd = false))
        assertEquals(PlayerKeyAction.SeekBy(5_000), PlayerKeyMap.action("Right", shift = false, ctrlOrCmd = false))
    }
}
