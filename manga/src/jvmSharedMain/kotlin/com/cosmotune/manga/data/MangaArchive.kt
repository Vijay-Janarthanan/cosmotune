package com.cosmotune.manga.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Reading uses generated cache names; archive paths are never extracted onto the filesystem. */
class MangaArchive(private val root: File) {
    private val books = File(root, "books").apply { mkdirs() }
    private val pages = File(root, "cache/pages").apply { mkdirs() }

    /** Images with a [MangaImageImport.chapter] become one chapter per folder; the folder name is kept only as the chapter title. */
    suspend fun importImages(name: String, images: List<MangaImageImport>): MangaBook = withContext(Dispatchers.IO) {
        require(images.isNotEmpty() && images.size <= MAX_ENTRIES) { "Choose a folder with 1 to 10,000 page images" }
        val chapters = images.groupBy { safeFolder(it.chapter) }
        require(chapters.size <= MAX_CHAPTERS) { "Choose a folder with at most 1,000 chapter folders" }
        val temporary = File.createTempFile("folder-", ".cbz", books)
        try {
            var total = 0L
            ZipOutputStream(temporary.outputStream()).use { zip ->
                chapters.keys.sortedWith(NaturalPageOrder).forEach { folder ->
                    val prefix = if (folder.isEmpty()) "" else "$folder/"
                    chapters.getValue(folder).sortedWith { left, right -> NaturalPageOrder.compare(left.name, right.name) }.forEachIndexed { index, image ->
                        val extension = image.name.substringAfterLast('.', "").lowercase()
                        require(extension in IMAGE_EXTENSIONS) { "Unsupported page image" }
                        zip.putNextEntry(ZipEntry("$prefix${index.toString().padStart(5, '0')}.$extension"))
                        var pageSize = 0L
                        image.open().use { input ->
                            val buffer = ByteArray(32 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                pageSize += count; total += count
                                require(pageSize <= MAX_PAGE_BYTES && total <= MAX_ARCHIVE_BYTES) { "Image folder exceeds the import limit" }
                                zip.write(buffer, 0, count)
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }
            import("$name.cbz") { temporary.inputStream() }
        } finally { temporary.delete() }
    }

    suspend fun import(name: String, open: () -> InputStream): MangaBook = withContext(Dispatchers.IO) {
        require(supports(name)) { "Choose CBZ, ZIP, CBT, TAR, TAR.GZ or TGZ. RAR, 7z and PDF need conversion to CBZ." }
        val temporary = File.createTempFile("import-", ".part", books)
        var converted: File? = null
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            open().use { input -> temporary.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var size = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    size += count
                    require(size <= MAX_ARCHIVE_BYTES) { "Archive exceeds the 512 MB import limit" }
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
            } }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val target = File(books, "$hash.cbz")
            val archive = if (name.endsWith(".cbz", true) || name.endsWith(".zip", true)) temporary else {
                File.createTempFile("converted-", ".part", books).also { file ->
                    converted = file
                    MangaTar.convert(temporary, file, name.endsWith(".gz", true) || name.endsWith(".tgz", true))
                }
            }
            val contents = inspect(archive)
            require(contents.pages.isNotEmpty()) { "This archive contains no supported page images" }
            move(archive, target)
            val bookId = "local:$hash"
            val info = contents.info
            val folders = chapterFolders(contents.pages)
            val chapters = if (folders == null) listOf(MangaChapter("$bookId:chapter", bookId, "local", info?.title?.takeIf { it.isNotBlank() } ?: "Read", "",
                number = info?.number ?: 0f, language = info?.language.orEmpty(), archiveFile = target.path))
            else folders.mapIndexed { index, (folder, title) ->
                val key = MessageDigest.getInstance("SHA-256").digest(folder.toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it) }
                MangaChapter("$bookId:chapter:$key", bookId, "local", title, "$folder/", number = index + 1f,
                    language = info?.language.orEmpty(), archiveFile = target.path)
            }
            MangaBook(
                id = bookId, sourceId = "local", title = info?.series?.takeIf { it.isNotBlank() } ?: File(name).nameWithoutExtension,
                authors = info?.authors.orEmpty(), description = info?.summary.orEmpty(), tags = info?.genres.orEmpty(),
                language = info?.language.orEmpty(), chapters = chapters,
            )
        } finally { temporary.delete(); converted?.delete() }
    }

    suspend fun chapterPages(chapter: MangaChapter): List<MangaPage> = withContext(Dispatchers.IO) {
        val archive = ownedArchive(chapter.archiveFile ?: error("Archive is missing"))
        val folder = chapter.url.takeIf { it.endsWith("/") }?.removeSuffix("/")
        // Single-chapter archives (and every import made before chapter folders) read all pages.
        inspect(archive).pages.filter { folder == null || pageFolder(it) == folder }.mapIndexed { index, name ->
            MangaPage("${chapter.id}:$index", archiveFile = archive.path, archiveEntry = name)
        }
    }

    suspend fun pageFile(page: MangaPage): File = withContext(Dispatchers.IO) {
        val archive = ownedArchive(page.archiveFile ?: error("Archive is missing"))
        val name = page.archiveEntry ?: error("Page is missing")
        val key = MessageDigest.getInstance("SHA-256").digest((archive.name + "\n" + name).toByteArray())
            .joinToString("") { "%02x".format(it) }
        val output = File(pages, "$key.${name.substringAfterLast('.').lowercase()}")
        if (output.isFile && output.length() > 0) {
            output.setLastModified(System.currentTimeMillis())
            return@withContext output
        }
        val temporary = File.createTempFile("page-", ".part", pages)
        try {
            ZipFile(archive).use { zip ->
                val entry = zip.getEntry(name) ?: error("The archive no longer contains this page")
                require(entry.size <= MAX_PAGE_BYTES) { "This page exceeds the 32 MB limit" }
                zip.getInputStream(entry).use { input -> temporary.outputStream().use { out ->
                    val buffer = ByteArray(32 * 1024)
                    var written = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        written += count
                        require(written <= MAX_PAGE_BYTES) { "This page exceeds the 32 MB limit" }
                        out.write(buffer, 0, count)
                    }
                } }
            }
            move(temporary, output)
            output
        } finally { temporary.delete() }
    }

    private fun ownedArchive(path: String): File {
        val archive = File(path).canonicalFile
        require(archive.parentFile == books.canonicalFile && archive.isFile) { "The imported archive is unavailable" }
        return archive
    }

    private class Contents(val pages: List<String>, val info: ComicInfo?)

    private fun inspect(file: File): Contents = ZipFile(file).use { zip ->
        val list = zip.entries().asSequence().take(MAX_ENTRIES + 1).toList()
        require(list.size <= MAX_ENTRIES) { "Archive contains too many entries" }
        val pages = list.filter { !it.isDirectory && it.name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS }
            .map { it.name }.sortedWith(NaturalPageOrder)
        // Only a root ComicInfo.xml describes the book; oversized or unreadable metadata is ignored, never fatal.
        val info = list.firstOrNull { !it.isDirectory && it.name.equals("ComicInfo.xml", true) }
            ?.takeIf { it.size in 0..MAX_COMIC_INFO_BYTES }
            ?.let { entry -> runCatching {
                val bytes = zip.getInputStream(entry).use { it.readNBytes(MAX_COMIC_INFO_BYTES.toInt() + 1) }
                if (bytes.size > MAX_COMIC_INFO_BYTES) null else ComicInfo.parse(bytes.toString(Charsets.UTF_8))
            }.getOrNull() }
        Contents(pages, info)
    }

    companion object {
        fun supports(name: String): Boolean = listOf(".cbz", ".zip", ".cbt", ".tar", ".tar.gz", ".tgz").any { name.endsWith(it, true) }
        const val MAX_ARCHIVE_BYTES = 512L * 1024 * 1024
        const val MAX_PAGE_BYTES = 32L * 1024 * 1024
        private const val MAX_ENTRIES = 10_000
        private const val MAX_CHAPTERS = 1_000
        private const val MAX_COMIC_INFO_BYTES = 256L * 1024
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "avif", "gif")

        /** The folder of an entry, or "" for the root; a path with empty, "." or ".." segments counts as the root. */
        internal fun pageFolder(entry: String): String {
            val folder = entry.replace('\\', '/').substringBeforeLast('/', "")
            return if (folder.split('/').any { it.isEmpty() || it == "." || it == ".." }) "" else folder
        }

        /**
         * Pages in two or more folders, with none at the root, are chapters in natural folder order, titled by the part of their path
         * that differs. Anything else stays one chapter, as every earlier import did.
         */
        internal fun chapterFolders(pages: List<String>): List<Pair<String, String>>? {
            val folders = pages.map(::pageFolder).distinct()
            if (folders.size < 2 || "" in folders || folders.size > MAX_CHAPTERS) return null
            val split = folders.map { it.split('/') }
            var common = 0
            while (split.all { it.size > common + 1 } && split.map { it[common] }.distinct().size == 1) common++
            return folders.sortedWith(NaturalPageOrder).map { folder -> folder to folder.split('/').drop(common).joinToString(" · ").take(500) }
        }

        private fun safeFolder(name: String): String = name.filterNot { it == '/' || it == '\\' || it.isISOControl() }.trim()
            .takeUnless { it == "." || it == ".." }.orEmpty().take(120)

        internal fun move(from: File, to: File) {
            try { Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}

/** [chapter] names the chapter folder an image came from; "" keeps it in a single chapter. */
data class MangaImageImport(val name: String, val chapter: String = "", val open: () -> InputStream)

/** The subset of the ComicRack ComicInfo.xml schema that describes a book. Parsed without DTDs or external entities. */
internal data class ComicInfo(val series: String, val title: String, val number: Float?, val summary: String,
    val authors: List<String>, val genres: List<String>, val language: String) {
    companion object {
        fun parse(xml: String): ComicInfo {
            val root = org.jsoup.Jsoup.parse(xml, "", org.jsoup.parser.Parser.xmlParser()).children().firstOrNull { it.tagName().equals("ComicInfo", true) }
                ?: error("Not a ComicInfo document")
            fun text(tag: String) = root.children().firstOrNull { it.tagName().equals(tag, true) }?.text()?.trim().orEmpty().take(100_000)
            fun list(vararg tags: String) = tags.flatMap { text(it).split(',', ';') }.map { it.trim().take(200) }.filter(String::isNotBlank).distinct().take(50)
            return ComicInfo(text("Series").take(500), text("Title").take(500), text("Number").toFloatOrNull(), text("Summary"),
                list("Writer", "Penciller"), list("Genre"), text("LanguageISO").take(32))
        }
    }
}
data class MangaFolderImport(val name: String, val images: List<MangaImageImport>)

/** Numeric segments compare by significant length first, avoiding integer overflow on long filenames. */
internal object NaturalPageOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var x = 0; var y = 0
        while (x < a.length && y < b.length) {
            if (a[x].isDigit() && b[y].isDigit()) {
                val sx = x; val sy = y
                while (x < a.length && a[x].isDigit()) x++
                while (y < b.length && b[y].isDigit()) y++
                val left = a.substring(sx, x).trimStart('0').ifEmpty { "0" }
                val right = b.substring(sy, y).trimStart('0').ifEmpty { "0" }
                val difference = left.length.compareTo(right.length).takeIf { it != 0 } ?: left.compareTo(right)
                if (difference != 0) return difference
            } else {
                val difference = a[x++].lowercaseChar().compareTo(b[y++].lowercaseChar())
                if (difference != 0) return difference
            }
        }
        return (a.length - x).compareTo(b.length - y).takeIf { it != 0 } ?: a.compareTo(b)
    }
}
