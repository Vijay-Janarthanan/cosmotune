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
    /** Persisted even after the last subscription is removed, so discovery never reinstalls an unwanted default. */
    val sourceSetupConsidered: Boolean = false,
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
    val collections: List<MangaCollection> = emptyList(),
    /** Zero disables automatic updates; supported choices are daily or weekly. */
    val updateIntervalHours: Int = 0,
    /** Signed built-in parser fixes, independent of scheduled chapter/library checks. */
    val parserAutoUpdates: Boolean = true,
    val lastUpdateAttemptMs: Long = 0,
    val lastUpdateCompletedMs: Long = 0,
    val lastUpdateNewChapters: Int = 0,
    val lastUpdateFailures: Int = 0,
    /** The reader's page brightness, 0.3 to 1 (a dimming over the pages, under the system's own). */
    val readerBrightness: Float = 1f,
    /** Keep the screen on while a chapter is open. */
    val readerKeepScreenOn: Boolean = true,
    /** Show "6 / 22" at the foot of the page while the reader's card is hidden. */
    val readerPageNumber: Boolean = true,
    /** Cut the plain white or black margins off each page. */
    val readerCrop: Boolean = false,
    /** Turn a wide page sideways so it fills a tall screen. */
    val readerRotateWide: Boolean = false,
    /** Two pages side by side in the paged modes when the window is wider than tall; [readerCoverAlone] keeps the first page alone. */
    val readerSpread: Boolean = false,
    val readerCoverAlone: Boolean = true,
    /** A colour filter over the pages: none, grey, inverted or sepia. */
    val readerFilter: MangaReaderFilter = MangaReaderFilter.NONE,
    /** What shows around a page that does not fill the screen. */
    val readerBackground: MangaReaderBackground = MangaReaderBackground.BLACK,
    /** Where a tap on a page turns it, and whether back and on are swapped. */
    val readerTapZones: MangaTapZones = MangaTapZones.THIRDS,
    val readerTapInvert: Boolean = false,
    /** The volume keys turn pages while reading. */
    val readerVolumeKeys: Boolean = false,
    /** Incognito: reading saves no place, no history and no read marks, and nothing is sent to reading lists. */
    val incognito: Boolean = false,
    /** Library updates skip series that are finished and fully read. */
    val updateSkipFinished: Boolean = true,
    /** Reading lists signed in to, by service ([MangaTrackers]). */
    val trackerAccounts: Map<String, MangaTrackerAccount> = emptyMap(),
    /** The reading-list entries each series is linked to, by series id. */
    val tracks: Map<String, List<MangaTrack>> = emptyMap(),
    /** Series whose last finished chapter could not be sent to a reading list or to Komga. */
    val trackFailures: Set<String> = emptySet(),
    /** Browse's feed: the sources' order, and the sources whose shelf is hidden. */
    val feedOrder: List<String> = emptyList(),
    val feedHidden: Set<String> = emptySet(),
    /** Series marked "Not interested": never suggested again in For you. */
    val notInterested: Set<String> = emptySet(),
    /** Read chapters that came from another device for a series whose chapter list this device has not loaded yet. */
    val syncedRead: Map<String, Set<String>> = emptyMap(),
    /** New chapters found by an update are downloaded at once. */
    val autoDownloadNew: Boolean = false,
    /** How many chapters ahead of the one being read are downloaded (0 is off). */
    val downloadAhead: Int = 0,
    /** A downloaded chapter is removed from the device once it is read. */
    val deleteAfterRead: Boolean = false,
    /** Finished Komga chapters are marked read on the Komga server too. */
    val serverWriteBack: Boolean = true,
)

@Serializable data class MangaCollection(val id: String, val name: String, val bookIds: Set<String> = emptySet())

fun MangaLibraryState.collectionBooks(selection: String): List<MangaBook> = when (selection) {
    "" -> books
    "unassigned" -> books.filter { book -> collections.none { book.id in it.bookIds } }
    else -> books.filter { it.id in collections.find { collection -> collection.id == selection }?.bookIds.orEmpty() }
}

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
@Serializable data class MangaBrowsePage(val books: List<MangaBook>, val nextOffset: Int?)

interface MangaSource {
    val config: MangaSourceConfig
    val verificationWebsite: String? get() = config.baseUrl.takeIf { it.isNotBlank() }
    suspend fun browse(query: String = "", offset: Int = 0): MangaBrowsePage
    suspend fun details(book: MangaBook): MangaBook
    suspend fun pages(chapter: MangaChapter): List<MangaPage>
    /** Catalogue languages a multi-language source can filter by (BCP 47 tags); the choice is [MANGA_LANGUAGE_SETTING]. */
    suspend fun languages(): List<String> = emptyList()
    /** Series the source itself suggests next to [book] ("Related"); empty where it has no such list. */
    suspend fun related(book: MangaBook): List<MangaBook> = emptyList()
    /** The orders this source can list its catalogue in besides its default; see [catalogue]. */
    val orders: Set<MangaListOrder> get() = emptySet()
    /** The catalogue in [order] (newest updates, most popular, top of the day, week or month); [browse] is the default order. */
    suspend fun catalogue(order: MangaListOrder, offset: Int = 0): MangaBrowsePage = browse("", offset)
}

/** The orders a catalogue can be listed in for Browse's feed and its top lists. */
enum class MangaListOrder { LATEST, POPULAR, TOP_DAY, TOP_WEEK, TOP_MONTH }

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
