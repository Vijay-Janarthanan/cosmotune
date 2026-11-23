package com.cosmotune.reading.data

import java.io.File
import kotlinx.serialization.Serializable

@Serializable enum class ReadingFormat { EPUB, PDF }
@Serializable enum class ReadingTheme(val label: String) { SYSTEM("System"), LIGHT("Light"), SEPIA("Sepia"), GREY("Grey"), DARK("Dark"), BLACK("Black"), CUSTOM("Your colours") }
/** A font the reader added for books (a TTF, OTF or WOFF file kept on this device). */
@Serializable data class ReadingFont(val id: String, val name: String, val extension: String)
@Serializable data class ReadingBook(val id: String, val title: String, val authors: List<String> = emptyList(),
    val format: ReadingFormat, val filePath: String, val coverPath: String? = null, val language: String = "",
    val description: String = "", val sourceUrl: String? = null, val addedAtMs: Long = System.currentTimeMillis(),
    /** The file the book was imported from when Read converted it to EPUB ("MOBI", "FB2", "TXT"…); blank for an EPUB or PDF. */
    val originalFormat: String = "",
    /** The SHA-256 of the owned file when it differs from [id] (a converted book keeps its original file's identity). */
    val fileSha: String = "",
    /** KOReader's identity of the imported file (for KOReader sync), and the library server and download address it came from. */
    val koreaderHash: String = "", val serverSource: String = "", val serverLink: String = "")
@Serializable data class ReadingLocator(val resourceHref: String? = null, val progression: Double = 0.0,
    val page: Int = 0, val scrollOffset: Double = 0.0)
@Serializable data class ReadingProgress(val bookId: String, val locator: ReadingLocator, val updatedAtMs: Long = System.currentTimeMillis())
@Serializable data class ReadingBookmark(val id: String, val bookId: String, val name: String, val locator: ReadingLocator,
    val createdAtMs: Long = System.currentTimeMillis())
/** How a highlight is marked: a colour behind the words, or a line under them. */
@Serializable enum class ReadingMarkStyle(val label: String) { YELLOW("Yellow"), GREEN("Green"), BLUE("Blue"), PINK("Pink"), UNDERLINE("Underline") }
/**
 * A highlighted passage of a chapter ([href]), by character offsets into the chapter's text ([start] until [end]), with the words
 * themselves, the reader's [note] and where in the chapter it sits ([progression]) for going back to it.
 */
@Serializable data class ReadingHighlight(val id: String, val bookId: String, val href: String, val start: Int, val end: Int, val text: String,
    val style: ReadingMarkStyle = ReadingMarkStyle.YELLOW, val note: String = "", val progression: Double = 0.0,
    val createdAtMs: Long = System.currentTimeMillis(), val updatedAtMs: Long = createdAtMs,
    /** The chapter's place in the reading order and its title, for listing and exporting highlights in book order. */
    val section: Int = 0, val chapter: String = "")
@Serializable data class ReadingPreferences(val fontScale: Float = 1f, val lineHeight: Float = 1.5f,
    val theme: ReadingTheme = ReadingTheme.SYSTEM, val scrollMode: Boolean = true,
    val fontFamily: String = "serif", val marginDp: Int = 24,
    /** The page's brightness, 0.3 to 1: a dimming over the page, under the system's own. */
    val brightness: Float = 1f,
    /** Keep the screen on while a book is open. */
    val keepScreenOn: Boolean = false,
    /** Look words up on Wiktionary, Wikipedia and the free translator when no dictionary on this device has them. */
    val onlineLookup: Boolean = true,
    /** The language Translate turns words into (a code such as "en"); blank is the device's language. */
    val translateTo: String = "",
    /** Reading aloud: the voice's id (blank: the device's own) and the speed, 1 being normal. */
    val voice: String = "", val speechRate: Float = 1f,
    /** Typography: justified text, hyphenation, the space after a paragraph (in lines), indented first lines, two columns on pages. */
    val justify: Boolean = false, val hyphenate: Boolean = false, val paragraphSpacing: Float = -1f, val paragraphIndent: Boolean = false,
    val columns: Int = 1,
    /** The reader's own style rules laid over every book (no addresses or imports). */
    val customCss: String = "",
    /** The page and text colours of [ReadingTheme.CUSTOM], as #rrggbb. */
    val pageColor: String = "#fdf6e3", val textColor: String = "#2b2b2b")
@Serializable data class ReadingOpdsSource(val id: String, val name: String, val url: String,
    val username: String = "", val password: String = "", val allowInsecureHttp: Boolean = false)
@Serializable data class ReadingCollection(val id: String, val name: String, val bookIds: Set<String> = emptySet())
@Serializable data class ReadingState(val version: Int = 1, val books: List<ReadingBook> = emptyList(),
    val progress: Map<String, ReadingProgress> = emptyMap(), val bookmarks: List<ReadingBookmark> = emptyList(),
    val preferences: ReadingPreferences = ReadingPreferences(), val collections: List<ReadingCollection> = emptyList(),
    val saved: List<ReadingPublication> = emptyList(), val finished: Set<String> = emptySet(),
    val sources: List<ReadingOpdsSource> = emptyList(), val highlights: List<ReadingHighlight> = emptyList(),
    val dictionaries: List<ReadingDictionary> = emptyList(), val lookups: List<String> = emptyList(),
    val fonts: List<ReadingFont> = emptyList(),
    /** Sync: when each Finished mark last changed, the removed marks (id to when) and the WebDAV folder books sync through. */
    val finishedAt: Map<String, Long> = emptyMap(), val removed: Map<String, Long> = emptyMap(), val webDav: ReadingWebDav? = null,
    /** Folders of books Read watches, and the files it has taken from them (file key to the book's id, or "!" when it could not). */
    val folders: List<ReadingFolder> = emptyList(), val folderFiles: Map<String, String> = emptyMap(),
    /** The KOReader sync account, when the reader signed in. */
    val koSync: ReadingKoSync? = null)

/** A folder of books on this device: a tree address on Android, a path on the desktop. */
@Serializable data class ReadingFolder(val location: String, val name: String)
/** A file found in a [ReadingFolder]: [key] stays the same while the file does. */
class ReadingFolderFile(val location: String, val name: String, val size: Long, val modifiedMs: Long, val open: () -> java.io.InputStream) {
    val key get() = "$location|$size|$modifiedMs"
}

data class ReadingEpubSection(val href: String, val file: File, val mediaType: String, val title: String = "")
data class ReadingNavigation(val title: String, val href: String, val children: List<ReadingNavigation> = emptyList())
data class ReadingResource(val href: String, val file: File, val mediaType: String)
sealed interface ReadingDocument {
    val book: ReadingBook
    data class Epub(override val book: ReadingBook, val root: File, val spine: List<ReadingEpubSection>,
        val navigation: List<ReadingNavigation>, val resources: Map<String, ReadingResource>) : ReadingDocument
    data class Pdf(override val book: ReadingBook, val file: File) : ReadingDocument
}

/** Where the reader's own fonts are kept (set by the library); book pages get them at [PREFIX] on their own origin. */
internal object ReadingFonts {
    @Volatile var directory: File? = null
    const val PREFIX = "cosmotune-font/"
    fun file(id: String): File? = if (!id.matches(Regex("[0-9a-f]{32}"))) null
        else directory?.listFiles()?.firstOrNull { it.nameWithoutExtension == id && it.extension in setOf("ttf", "otf", "woff", "woff2") }
}
