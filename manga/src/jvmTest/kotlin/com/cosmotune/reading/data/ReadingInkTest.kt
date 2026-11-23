package com.cosmotune.reading.data

import com.cosmotune.reading.ui.historyGroup
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class ReadingInkTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun aDrawnLineKeepsItsShapeWithFewerPointsAndIsFoundUnderAFinger() {
        val points = (0..200).flatMap { listOf(0.1f + it * 0.0001f, 0.5f) } + listOf(0.6f, 0.5f)
        val simple = ReadingInkMath.simplify(points)
        assertTrue(simple.size < points.size, "points closer than the step are dropped")
        assertEquals(0.1f, simple[0]); assertEquals(0.6f, simple[simple.size - 2], "the end is kept")
        val stroke = ReadingInkStroke("s", "b", 0, ReadingInk.RED, ReadingInkMath.simplify(listOf(0.1f, 0.1f, 0.9f, 0.1f)))
        assertTrue(ReadingInkMath.hits(stroke, 0.5f, 0.11f))
        assertFalse(ReadingInkMath.hits(stroke, 0.5f, 0.5f))
        assertEquals(listOf(1f, 0f), ReadingInkMath.simplify(listOf(1.5f, -2f)), "points stay on the page")
    }

    @Test fun drawingsAreKeptByPageAndCleared() = runBlocking<Unit> {
        val repository = ReadingRepository(temporary.newFolder())
        val book = ReadingBook("a".repeat(64), "Paper", format = ReadingFormat.PDF, filePath = temporary.newFile("x.pdf").path)
        repository.store.update { it.copy(books = listOf(book)) }
        val first = assertNotNull(repository.addInk(book.id, 2, ReadingInk.MARKER, listOf(0.1f, 0.1f, 0.4f, 0.1f)))
        repository.addInk(book.id, 3, ReadingInk.BLUE, listOf(0.2f, 0.2f))
        assertEquals(listOf(2, 3), repository.state.value.inks.map { it.page })
        repository.removeInk(first.id)
        assertEquals(listOf(3), repository.state.value.inks.map { it.page })
        repository.clearInk(book.id, 3)
        assertTrue(repository.state.value.inks.isEmpty())
    }
}
