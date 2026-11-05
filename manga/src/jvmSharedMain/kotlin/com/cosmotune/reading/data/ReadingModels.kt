package com.cosmotune.reading.data

import java.io.File
import kotlinx.serialization.Serializable

@Serializable enum class ReadingFormat { EPUB, PDF }
@Serializable enum class ReadingTheme { SYSTEM, LIGHT, SEPIA, DARK }
@Serializable data class ReadingBook(val id: String, val title: String, val authors: List<String> = emptyList(),
    val format: ReadingFormat, val filePath: String, val coverPath: String? = null, val language: String = "",
    val description: String = "", val sourceUrl: String? = null, val addedAtMs: Long = System.currentTimeMillis())
@Serializable data class ReadingLocator(val resourceHref: String? = null, val progression: Double = 0.0,
    val page: Int = 0, val scrollOffset: Double = 0.0)
@Serializable data class ReadingProgress(val bookId: String, val locator: ReadingLocator, val updatedAtMs: Long = System.currentTimeMillis())
@Serializable data class ReadingBookmark(val id: String, val bookId: String, val name: String, val locator: ReadingLocator,
    val createdAtMs: Long = System.currentTimeMillis())
@Serializable data class ReadingPreferences(val fontScale: Float = 1f, val lineHeight: Float = 1.5f,
    val theme: ReadingTheme = ReadingTheme.SYSTEM, val scrollMode: Boolean = true,
    val fontFamily: String = "serif", val marginDp: Int = 24)
@Serializable data class ReadingOpdsSource(val id: String, val name: String, val url: String,
    val username: String = "", val password: String = "", val allowInsecureHttp: Boolean = false)
@Serializable data class ReadingCollection(val id: String, val name: String, val bookIds: Set<String> = emptySet())
@Serializable data class ReadingState(val version: Int = 1, val books: List<ReadingBook> = emptyList(),
    val progress: Map<String, ReadingProgress> = emptyMap(), val bookmarks: List<ReadingBookmark> = emptyList(),
    val preferences: ReadingPreferences = ReadingPreferences(), val collections: List<ReadingCollection> = emptyList(),
    val saved: List<ReadingPublication> = emptyList(), val finished: Set<String> = emptySet(),
    val sources: List<ReadingOpdsSource> = emptyList())

data class ReadingEpubSection(val href: String, val file: File, val mediaType: String, val title: String = "")
data class ReadingNavigation(val title: String, val href: String, val children: List<ReadingNavigation> = emptyList())
data class ReadingResource(val href: String, val file: File, val mediaType: String)
sealed interface ReadingDocument {
    val book: ReadingBook
    data class Epub(override val book: ReadingBook, val root: File, val spine: List<ReadingEpubSection>,
        val navigation: List<ReadingNavigation>, val resources: Map<String, ReadingResource>) : ReadingDocument
    data class Pdf(override val book: ReadingBook, val file: File) : ReadingDocument
}
