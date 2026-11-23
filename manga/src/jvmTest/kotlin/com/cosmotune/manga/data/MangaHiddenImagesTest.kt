package com.cosmotune.manga.data

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class MangaHiddenImagesTest {
    @Test fun theSameImageHasTheSameMarkWhateverItsName() {
        val dir = kotlin.io.path.createTempDirectory("hidden").toFile()
        try {
            val a = File(dir, "a.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val b = File(dir, "b.webp").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val c = File(dir, "c.png").apply { writeBytes(byteArrayOf(1, 2, 4)) }
            assertEquals(MangaHiddenImages.mark(a), MangaHiddenImages.mark(b))
            assertTrue(MangaHiddenImages.mark(a) != MangaHiddenImages.mark(c))
            assertEquals(64, MangaHiddenImages.mark(a).length)
        } finally { dir.deleteRecursively() }
    }

    @Test fun hidingKeepsWhereItCameFromAndShowingTakesItOut() {
        val hidden = MangaHiddenImages.hide(MangaLibraryState(), "abc", "One Piece", 5L)
        assertEquals(MangaHiddenImage("One Piece", 5L), hidden.hiddenImages["abc"])
        assertFalse("abc" in MangaHiddenImages.show(hidden, "abc").hiddenImages)
    }

    @Test fun theListStaysBounded() {
        var state = MangaLibraryState()
        repeat(MangaHiddenImages.LIMIT + 5) { state = MangaHiddenImages.hide(state, "m$it", "S", it.toLong()) }
        assertEquals(MangaHiddenImages.LIMIT, state.hiddenImages.size)
        assertFalse("m0" in state.hiddenImages, "the oldest go first")
    }
}
