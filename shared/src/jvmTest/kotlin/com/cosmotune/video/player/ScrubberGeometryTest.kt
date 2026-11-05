package com.cosmotune.video.player

import com.cosmotune.video.model.Chapter
import com.cosmotune.video.model.Storyboard
import com.cosmotune.video.segments.SegmentAction
import com.cosmotune.video.segments.SegmentCategory
import com.cosmotune.video.segments.SkipSegment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The scrubber's arithmetic (spec 6.5): segment ranges, chapter gaps, the storyboard frame of a time, the time label. */
class ScrubberGeometryTest {

    private fun seg(category: SegmentCategory, start: Long, end: Long, action: SegmentAction = SegmentAction.SKIP) =
        SkipSegment("u$start", category, action, start, end, null, 0, false, null)

    @Test
    fun fractionIsClampedAndZeroWithoutADuration() {
        assertEquals(0.25f, ScrubberGeometry.fraction(25_000, 100_000))
        assertEquals(0f, ScrubberGeometry.fraction(-5, 100_000))
        assertEquals(1f, ScrubberGeometry.fraction(200_000, 100_000))
        assertEquals(0f, ScrubberGeometry.fraction(5_000, 0))
    }

    @Test
    fun rangesAreFractionsSortedAndCarryTheCategoryColour() {
        val ranges = ScrubberGeometry.ranges(
            listOf(seg(SegmentCategory.SPONSOR, 30_000, 60_000), seg(SegmentCategory.INTRO, 0, 8_000)),
            100_000,
        )
        assertEquals(2, ranges.size)
        assertEquals(Triple(0f, 0.08f, SegmentCategory.INTRO.colorArgb), ranges[0], "sorted by start")
        assertEquals(Triple(0.3f, 0.6f, SegmentCategory.SPONSOR.colorArgb), ranges[1])
    }

    @Test
    fun rangesAreClampedToTheVideo() {
        val ranges = ScrubberGeometry.ranges(listOf(seg(SegmentCategory.OUTRO, 90_000, 130_000), seg(SegmentCategory.HOOK, -2_000, 1_000)), 100_000)
        assertEquals(0f, ranges[0].first)
        assertEquals(0.01f, ranges[0].second)
        assertEquals(0.9f, ranges[1].first)
        assertEquals(1f, ranges[1].second)
    }

    @Test
    fun aHighlightIsAMarkAndWholeVideoLabelsAreNotOnTheBar() {
        val ranges = ScrubberGeometry.ranges(
            listOf(
                seg(SegmentCategory.POI_HIGHLIGHT, 50_000, 50_000, SegmentAction.POI),
                seg(SegmentCategory.EXCLUSIVE_ACCESS, 0, 100_000, SegmentAction.FULL),
                seg(SegmentCategory.SPONSOR, 0, 100_000, SegmentAction.CHAPTER),
            ),
            100_000,
        )
        assertEquals(listOf(Triple(0.5f, 0.5f, SegmentCategory.POI_HIGHLIGHT.colorArgb)), ranges)
    }

    @Test
    fun noRangesWithoutADuration() {
        assertTrue(ScrubberGeometry.ranges(listOf(seg(SegmentCategory.SPONSOR, 0, 10)), 0).isEmpty())
    }

    @Test
    fun anEndBeforeItsStartIsAMark() {
        val r = ScrubberGeometry.ranges(listOf(seg(SegmentCategory.SPONSOR, 40_000, 30_000)), 100_000).single()
        assertEquals(0.4f, r.first)
        assertEquals(0.4f, r.second)
    }

    @Test
    fun chapterStartsLeaveOutZeroAndTheEndAndAreSorted() {
        val chapters = listOf(
            Chapter("Outro", 90_000, null), Chapter("Intro", 0, null), Chapter("Middle", 25_000, null),
            Chapter("Twice", 25_000, null), Chapter("After the end", 120_000, null),
        )
        assertEquals(listOf(0.25f, 0.9f), ScrubberGeometry.chapterStarts(chapters, 100_000))
        assertTrue(ScrubberGeometry.chapterStarts(chapters, 0).isEmpty())
    }

    @Test
    fun theChapterAtATimeIsTheLastThatStarted() {
        val chapters = listOf(Chapter("B", 30_000, null), Chapter("A", 0, null), Chapter("C", 60_000, null))
        assertEquals("A", chapters[ScrubberGeometry.chapterIndexAt(chapters, 10_000)].title)
        assertEquals("B", chapters[ScrubberGeometry.chapterIndexAt(chapters, 30_000)].title)
        assertEquals("C", chapters[ScrubberGeometry.chapterIndexAt(chapters, 99_000)].title)
        assertEquals(-1, ScrubberGeometry.chapterIndexAt(listOf(Chapter("Late", 5_000, null)), 1_000))
        assertEquals(-1, ScrubberGeometry.chapterIndexAt(emptyList(), 1_000))
    }

    private val board = Storyboard(
        urls = listOf("s0", "s1", "s2"), frameWidth = 160, frameHeight = 90, columns = 5, rows = 5, intervalMs = 2_000, totalFrames = 60,
    )

    @Test
    fun theStoryboardFrameOfATimeIsItsSheetColumnAndRow() {
        assertEquals(StoryboardFrame(sheet = 0, column = 0, row = 0, index = 0), ScrubberGeometry.storyboardFrame(board, 0))
        assertEquals(StoryboardFrame(0, 3, 1, 8), ScrubberGeometry.storyboardFrame(board, 17_999))
        assertEquals(StoryboardFrame(1, 0, 0, 25), ScrubberGeometry.storyboardFrame(board, 50_000))
        assertEquals(StoryboardFrame(2, 4, 1, 59), ScrubberGeometry.storyboardFrame(board, 118_000))
    }

    @Test
    fun theStoryboardFrameIsClampedToTheFramesThereAre() {
        assertEquals(59, ScrubberGeometry.storyboardFrame(board, 10_000_000).index)
        assertEquals(0, ScrubberGeometry.storyboardFrame(board, -1_000).index)
        val short = board.copy(urls = listOf("only"))
        assertEquals(0, ScrubberGeometry.storyboardFrame(short, 118_000).sheet, "never a sheet that is not there")
        val broken = board.copy(intervalMs = 0, columns = 0, rows = 0, totalFrames = 0)
        assertEquals(StoryboardFrame(0, 0, 0, 0), ScrubberGeometry.storyboardFrame(broken, 30_000))
    }

    @Test
    fun theTimeLabelHasMinutesAndSecondsAndHoursWhenNeeded() {
        assertEquals("0:00", ScrubberGeometry.timeLabel(0))
        assertEquals("0:05", ScrubberGeometry.timeLabel(5_999))
        assertEquals("1:21", ScrubberGeometry.timeLabel(81_000))
        assertEquals("4:08", ScrubberGeometry.timeLabel(248_000))
        assertEquals("1:02:03", ScrubberGeometry.timeLabel(3_723_000))
        assertEquals("0:00", ScrubberGeometry.timeLabel(-4_000))
    }
}
