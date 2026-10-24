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

/** How a page fills the reader in the paged modes: whole page on screen, or full width with the rest scrolled. Webtoon always fits width. */
@Serializable
enum class MangaReaderFit { PAGE, WIDTH }

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
    val readerFit: MangaReaderFit = MangaReaderFit.PAGE,
    /** Chapters found by the last library update checks and not yet opened or read. */
    val newChapters: Set<String> = emptySet(),
)

/** The result of a library update check so far: [failures] maps a series id to the reason its source could not be checked. */
data class MangaUpdateReport(val checked: Int, val total: Int, val newChapters: Int, val updatedSeries: Int, val failures: Map<String, String>)

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
    /** Catalogue languages a multi-language source can filter by (BCP 47 tags); the choice is [MANGA_LANGUAGE_SETTING]. */
    suspend fun languages(): List<String> = emptyList()
}

/** Source setting holding the chosen catalogue language of a multi-language source; blank means the source's default. */
const val MANGA_LANGUAGE_SETTING = "cosmotune.locale"

/** The edition a chapter belongs to: its translation branch, else its language. Blank when the source names none. */
val MangaChapter.edition: String get() = branch?.takeIf { it.isNotBlank() } ?: language

/** The editions of a series in first-seen order; a series with fewer than two needs no edition filter. */
fun MangaBook.editions(): List<String> = chapters.map { it.edition }.filter { it.isNotBlank() }.distinct()

/**
 * The chapters of a series as the series page lists them: one [edition] (blank for all), oldest or newest first by volume and number
 * (ties keep the source's order), optionally without the chapters in [read].
 */
fun MangaBook.listedChapters(edition: String = "", newestFirst: Boolean = false, unreadOnly: Boolean = false, read: Set<String> = emptySet()): List<MangaChapter> {
    val ordered = chapters.withIndex().sortedWith(compareBy({ it.value.volume }, { it.value.number }, { it.index })).map { it.value }
    return (if (newestFirst) ordered.reversed() else ordered)
        .filter { edition.isBlank() || it.edition == edition }
        .filter { !unreadOnly || it.id !in read }
}
