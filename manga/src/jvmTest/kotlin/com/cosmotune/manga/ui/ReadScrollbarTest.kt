package com.cosmotune.manga.ui

import kotlin.test.*

class ReadScrollbarTest {
    @Test fun fittedUnknownAndEmptyListsHaveNoThumb() {
        assertNull(readScrollbarThumb(100, 100, 0, 400f, 24f))
        assertNull(readScrollbarThumb(-1, -1, 0, 400f, 24f))
        assertNull(readScrollbarThumb(1_000, 100, 0, 0f, 24f))
    }
    @Test fun bottomAndOverscrollStayInsideTheTrackWithAMinimumThumb() {
        assertEquals(0f to 24f, readScrollbarThumb(100_000, 400, -10, 400f, 24f))
        assertEquals(376f to 24f, readScrollbarThumb(100_000, 400, 100_000, 400f, 24f))
        assertEquals(0f to 12f, readScrollbarThumb(100_000, 400, 100_000, 12f, 24f))
    }
}
