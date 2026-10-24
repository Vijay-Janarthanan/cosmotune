package com.cosmotune.manga.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class MangaBook(
    val id: String,
    val sourceId: String,
    val title: String,
    val url: String = "",
    val coverUrl: String? = null,
    val authors: List<String> = emptyList(),
    val description: String = "",
    val tags: List<String> = emptyList(),
    val language: String = "",
    val chapters: List<MangaChapter> = emptyList(),
    val addedAtMs: Long = System.currentTimeMillis(),
    val parserData: MangaParserBookData? = null,
    val sourceMemo: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class MangaParserBookData(
    val publicUrl: String,
    val alternateTitles: Set<String> = emptySet(),
    val rating: Float = -1f,
    val contentRating: String? = null,
    val state: String? = null,
    val tagKeys: Map<String, String> = emptyMap(),
)

@Serializable
data class MangaChapter(
    val id: String,
    val bookId: String,
    val sourceId: String,
    val title: String,
    val url: String,
    val number: Float = 0f,
    val language: String = "",
    val archiveFile: String? = null,
    val volume: Int = 0,
    val scanlator: String? = null,
    val uploadDate: Long = 0,
    val branch: String? = null,
    val sourceMemo: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class MangaPage(
    val id: String,
    val url: String? = null,
    val localFile: String? = null,
    val archiveFile: String? = null,
    val archiveEntry: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val requestMethod: String = "GET",
)

@Serializable
enum class MangaReaderMode { PAGES_LTR, PAGES_RTL, WEBTOON }

@Serializable
data class MangaProgress(
    val bookId: String,
    val chapterId: String,
    val page: Int,
    val pageCount: Int,
    val mode: MangaReaderMode = MangaReaderMode.PAGES_RTL,
    val updatedAtMs: Long = System.currentTimeMillis(),
    val reachedEnd: Boolean = mode != MangaReaderMode.WEBTOON && pageCount > 0 && page >= pageCount - 1,
    val pageOffsetPx: Int = 0,
) {
    val finished: Boolean get() = pageCount > 0 && reachedEnd
}

@Serializable
data class MangaSourceConfig(
    val id: String,
    val kind: String = "kotatsu",
    val name: String,
    val language: String = "",
    val baseUrl: String = "",
    val settings: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
)

@Serializable
data class MangaLibraryState(
    val version: Int = 1,
    val books: List<MangaBook> = emptyList(),
    val progress: Map<String, MangaProgress> = emptyMap(),
    val sources: List<MangaSourceConfig> = emptyList(),
    val downloads: Map<String, List<MangaPage>> = emptyMap(),
    val cacheLimitBytes: Long = 256L * 1024 * 1024,
    val readerMode: MangaReaderMode = MangaReaderMode.PAGES_RTL,
    val extensionStores: List<MangaExtensionStore> = emptyList(),
    val readChapters: Set<String> = emptySet(),
    val activeBookId: String? = null,
    val activeChapterId: String? = null,
)

/** Continue the same edition without jumping to another translation or restarting a missing chapter. */
fun MangaBook.nextChapter(current: MangaChapter): MangaChapter? {
    val edition = chapters.filter {
        it.language == current.language && it.branch == current.branch && it.scanlator == current.scanlator
    }.sortedWith(compareBy({ it.volume }, { it.number }))
    val index = edition.indexOfFirst { it.id == current.id }
    return if (index < 0) null else edition.getOrNull(index + 1)
}

@Serializable
data class MangaExtensionStore(val indexUrl: String, val name: String, val signingKey: String, val website: String = "")

data class MangaSourceDescriptor(val id: String, val title: String, val language: String, val kind: String = "kotatsu")
data class MangaBrowsePage(val books: List<MangaBook>, val nextOffset: Int?)

interface MangaSource {
    val config: MangaSourceConfig
    suspend fun browse(query: String = "", offset: Int = 0): MangaBrowsePage
    suspend fun details(book: MangaBook): MangaBook
    suspend fun pages(chapter: MangaChapter): List<MangaPage>
}
