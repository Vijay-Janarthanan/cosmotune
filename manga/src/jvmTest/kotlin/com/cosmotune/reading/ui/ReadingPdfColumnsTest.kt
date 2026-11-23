package com.cosmotune.reading.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReadingPdfColumnsTest {
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    /** A page [width] by [height] with lines of "text" (short black runs) inside each of [columns] (x ranges), and a title across the top. */
    private fun page(width: Int, height: Int, columns: List<IntRange>, title: IntRange? = null): IntArray {
        val pixels = IntArray(width * height) { white }
        for (y in 40 until height - 40 step 12) for (column in columns) for (x in column) if ((x / 7) % 5 != 4) {
            pixels[y * width + x] = black; pixels[(y + 1) * width + x] = black
        }
        if (title != null) for (y in 20 until 26) for (x in title) pixels[y * width + x] = black
        return pixels
    }

    @Test fun twoColumnsAreFoundEvenUnderATitleAcrossThem() {
        val pixels = page(600, 800, listOf(40..280, 320..560), title = 40..560)
        val columns = pdfColumns(pixels, 600, 800)
        assertEquals(2, columns.size)
        assertTrue(columns[0].left <= 40 && columns[0].right in 280..300, "first column: ${columns[0]}")
        assertTrue(columns[1].left in 300..320 && columns[1].right >= 560, "second column: ${columns[1]}")
    }

    @Test fun oneColumnAndBlankPagesGiveNoColumns() {
        assertEquals(emptyList(), pdfColumns(page(600, 800, listOf(40..560)), 600, 800))
        assertEquals(emptyList(), pdfColumns(IntArray(600 * 800) { white }, 600, 800))
    }

    @Test fun aNarrowMarginNoteIsNotAColumn() {
        assertEquals(emptyList(), pdfColumns(page(600, 800, listOf(40..500, 530..560)), 600, 800))
    }
}
