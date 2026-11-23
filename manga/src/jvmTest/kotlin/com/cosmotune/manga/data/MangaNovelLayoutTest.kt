package com.cosmotune.manga.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MangaNovelLayoutTest {
    /** A block of [count] lines, each [height] px tall and [chars] characters long. */
    private fun block(count: Int, height: Int = 10, chars: Int = 5) = (0 until count).map { MangaNovelLine(it * chars, (it + 1) * chars, it * height, (it + 1) * height) }

    @Test fun linesFillEachPageAndALongParagraphCarriesOn() {
        // Page 100 px, a 10 px gap between blocks: 6 lines, gap, then 3 lines of the next block fit (60 + 10 + 30).
        val pages = MangaNovelLayout.paginate(listOf(block(6), block(8), block(2)), pageHeight = 100, blockGap = 10)
        assertEquals(listOf(MangaNovelSlice(0, 0, 30), MangaNovelSlice(1, 0, 15)), pages[0].slices)
        assertEquals(MangaNovelSlice(1, 15, 40), pages[1].slices.first(), "the paragraph goes on where it stopped")
        assertEquals(MangaNovelPosition(1, 15), pages[1].start)
        assertEquals(pages.flatMap { it.slices }.groupBy { it.block }.mapValues { (_, s) -> s.sumOf { it.end - it.start } }, mapOf(0 to 30, 1 to 40, 2 to 10))
    }

    @Test fun aLineTallerThanThePageStillGetsAPage() {
        val pages = MangaNovelLayout.paginate(listOf(listOf(MangaNovelLine(0, 4, 0, 300)), block(1)), pageHeight = 100, blockGap = 0)
        assertEquals(2, pages.size)
        assertEquals(listOf(MangaNovelSlice(0, 0, 4)), pages[0].slices)
    }

    @Test fun theSamePlaceIsFoundAfterTheLayoutChanges() {
        val small = MangaNovelLayout.paginate(listOf(block(20), block(20), block(20)), pageHeight = 100, blockGap = 10)
        val large = MangaNovelLayout.paginate(listOf(block(20, height = 25), block(20, height = 25), block(20, height = 25)), pageHeight = 100, blockGap = 10)
        val place = MangaNovelPosition(1, 52)
        val onSmall = small[MangaNovelLayout.pageOf(small, place)]
        val onLarge = large[MangaNovelLayout.pageOf(large, place)]
        assertTrue(onSmall.contains(place) && onLarge.contains(place))
        assertEquals(0, MangaNovelLayout.pageOf(small, MangaNovelPosition(0, 0)))
        assertEquals(small.lastIndex, MangaNovelLayout.pageOf(small, MangaNovelPosition(9, 0)), "a place past the end is the last page")
        assertEquals(0, MangaNovelLayout.pageOf(emptyList(), place))
    }

    @Test fun scrollingKeepsTheCharacterNotThePixel() {
        // 200 px into a 400 px tall paragraph of 1,000 characters is character 500, at any text size.
        assertEquals(500, MangaNovelLayout.charAt(1_000, offsetPx = 200, heightPx = 400))
        assertEquals(400, MangaNovelLayout.offsetFor(500, 1_000, heightPx = 800))
        assertEquals(0, MangaNovelLayout.charAt(1_000, offsetPx = 50, heightPx = 0))
        assertEquals(999, MangaNovelLayout.charAt(1_000, offsetPx = 900, heightPx = 400))
    }

    @Test fun thePlaceIsKeptInTheReadingProgressAndComesBack() {
        val chapter = MangaNovelChapter("One", List(10) { MangaNovelBlock(MangaNovelBlock.Kind.PARAGRAPH, "x".repeat(100)) })
        val progress = MangaNovelLayout.progress("book", "ch", chapter, MangaNovelPosition(4, 37), finished = false, nowMs = 5)
        assertEquals(4, progress.page)
        assertEquals(10, progress.pageCount)
        assertEquals(37, progress.pageOffsetPx)
        assertFalse(progress.finished)
        assertEquals(MangaNovelPosition(4, 37), MangaNovelLayout.position(progress, chapter))
        assertTrue(MangaNovelLayout.progress("book", "ch", chapter, MangaNovelPosition(9, 0), finished = true).finished)
        // A place saved against a longer version of the chapter lands on its last block.
        assertEquals(MangaNovelPosition(9, 0), MangaNovelLayout.position(progress.copy(page = 40, pageOffsetPx = 3), chapter))
        assertEquals(MangaNovelPosition(0, 0), MangaNovelLayout.position(null, chapter))
    }

    @Test fun howFarThroughCountsCharacters() {
        val chapter = MangaNovelChapter("", listOf(MangaNovelBlock(MangaNovelBlock.Kind.PARAGRAPH, "x".repeat(300)), MangaNovelBlock(MangaNovelBlock.Kind.PARAGRAPH, "x".repeat(100))))
        assertEquals(0f, MangaNovelLayout.fraction(chapter, MangaNovelPosition(0, 0)))
        assertEquals(0.75f, MangaNovelLayout.fraction(chapter, MangaNovelPosition(1, 0)))
        assertEquals(MangaNovelPosition(1, 0), MangaNovelLayout.at(chapter, 0.75f))
        assertEquals(MangaNovelPosition(0, 150), MangaNovelLayout.at(chapter, 0.375f))
    }

    @Test fun theStyleStaysWithinItsLimits() {
        val style = MangaNovelStyle(size = 90, lineSpacing = 9f, margin = -4).clamped()
        assertEquals(MangaNovelStyle.SIZES.last, style.size)
        assertEquals(2.2f, style.lineSpacing)
        assertEquals(0, style.margin)
    }
}
