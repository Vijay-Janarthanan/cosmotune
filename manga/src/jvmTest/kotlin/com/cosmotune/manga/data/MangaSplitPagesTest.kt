package com.cosmotune.manga.data

import org.junit.Test
import kotlin.test.*

class MangaSplitPagesTest {
    @Test fun aWidePageBecomesTwoViewsInReadingOrder() {
        val wide = setOf(1)
        assertEquals(listOf(listOf(0), listOf(5), listOf(6), listOf(8)), mangaSplitViews(3, { it in wide }, rightToLeft = false))
        assertEquals(listOf(listOf(0), listOf(6), listOf(5), listOf(8)), mangaSplitViews(3, { it in wide }, rightToLeft = true), "right half first")
    }

    @Test fun halvesSplitTheContentBounds() {
        val bounds = MangaPageBounds(10, 0, 110, 50)
        assertEquals(MangaPageBounds(10, 0, 60, 50), bounds.half(1))
        assertEquals(MangaPageBounds(60, 0, 110, 50), bounds.half(2))
        assertEquals(bounds, bounds.half(0))
        assertEquals("h2", MangaPageTreatment(half = 2).suffix)
        assertFalse(MangaPageTreatment(half = 1).none)
    }
}
