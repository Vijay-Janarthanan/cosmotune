package com.cosmotune.reading.ui

import com.cosmotune.reading.data.*
import kotlin.test.Test
import kotlin.test.assertEquals

class BookLibraryGroupsTest {
    private fun book(id: String, author: String = "", series: String = "", index: Double? = null, subjects: List<String> = emptyList()) =
        ReadingBook(id, id.uppercase(), listOfNotNull(author.takeIf { it.isNotEmpty() }), ReadingFormat.EPUB, "$id.epub", series = series, seriesIndex = index, subjects = subjects)

    @Test fun booksGroupBySeriesInOrderAuthorAndSubjectWithOtherLast() {
        val books = listOf(book("c", "Ursula Le Guin", "Earthsea", 2.0, listOf("Fantasy")), book("a", "Ursula Le Guin", "Earthsea", 1.0, listOf("Fantasy", "Classics")),
            book("x", "", subjects = emptyList()), book("b", "Ann Leckie", "Imperial Radch", 1.0, listOf("Science fiction")))
        assertEquals(listOf("Earthsea" to listOf("a", "c"), "Imperial Radch" to listOf("b"), "Other" to listOf("x")),
            books.grouped(BookGroup.SERIES).map { (heading, members) -> heading to members.map { it.id } })
        assertEquals(listOf("Ann Leckie", "Ursula Le Guin", "Other"), books.grouped(BookGroup.AUTHOR).map { it.first })
        assertEquals(listOf("Classics", "Fantasy", "Science fiction", "Other"), books.grouped(BookGroup.SUBJECT).map { it.first })
        assertEquals(listOf(null to 4), books.grouped(BookGroup.NONE).map { it.first to it.second.size })
        assertEquals(listOf("a", "c", "b", "x"), books.ordered(BookOrder.AUTHOR, ReadingState()).map { it.id })
    }
}
