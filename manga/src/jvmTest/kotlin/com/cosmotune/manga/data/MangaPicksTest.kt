package com.cosmotune.manga.data

import kotlin.test.Test
import kotlin.test.assertEquals

class MangaPicksTest {
    private fun book(id: String) = MangaBook(id = id, sourceId = "s", title = id)

    @Test fun choosingTwiceLetsGo() {
        val a = book("a"); val b = book("b")
        assertEquals(listOf(a, b), MangaPicks.toggle(MangaPicks.toggle(emptyList(), a), b))
        assertEquals(listOf(b), MangaPicks.toggle(listOf(a, b), a))
    }

    @Test fun chosenSeriesSplitIntoThoseToAddAndThoseToRemove() {
        val a = book("a"); val b = book("b"); val c = book("c")
        val split = MangaPicks.split(listOf(a, b, c), setOf("b"))
        assertEquals(listOf(a, c), split.toAdd)
        assertEquals(listOf(b), split.toRemove)
    }
}
