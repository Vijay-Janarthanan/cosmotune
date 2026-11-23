package com.cosmotune.manga.ui

import com.cosmotune.manga.data.*
import org.junit.Test
import kotlin.test.*

class MangaGenreFilterTest {
    private val a = MangaBook("a", "s", "A", tags = listOf("Action", "Manhwa"))
    private val b = MangaBook("b", "s", "B", tags = listOf("action", "Romance"), language = "ja")
    private val c = MangaBook("c", "s", "C", tags = listOf("Romance"), language = "zh")

    @Test fun typesComeFromGenresElseTheLanguage() {
        assertEquals("Manhwa", mangaType(a)); assertEquals("Manga", mangaType(b)); assertEquals("Manhua", mangaType(c))
        assertNull(mangaType(MangaBook("d", "s", "D")))
    }

    @Test fun genreAndTypeFiltersCombine() {
        val all = listOf(a, b, c)
        assertEquals(listOf("Action", "Romance", "Manhwa"), mangaLibraryGenres(all), "the most common genres first, each once")
        assertEquals(listOf("a", "b"), mangaGenreFilter(all, setOf("Action"), emptySet()).map { it.id })
        assertEquals(listOf("b"), mangaGenreFilter(all, setOf("Action"), setOf("Manga")).map { it.id })
        assertEquals(all, mangaGenreFilter(all, emptySet(), emptySet()))
    }
}
