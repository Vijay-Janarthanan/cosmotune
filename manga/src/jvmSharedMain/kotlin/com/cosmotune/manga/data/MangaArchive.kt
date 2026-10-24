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

    suspend fun importImages(name: String, images: List<MangaImageImport>): MangaBook = withContext(Dispatchers.IO) {
        require(images.isNotEmpty() && images.size <= MAX_ENTRIES) { "Choose a folder with 1 to 10,000 page images" }
        val temporary = File.createTempFile("folder-", ".cbz", books)
        try {
            var total = 0L
            ZipOutputStream(temporary.outputStream()).use { zip ->
                images.sortedWith { left, right -> NaturalPageOrder.compare(left.name, right.name) }.forEachIndexed { index, image ->
                    val extension = image.name.substringAfterLast('.', "").lowercase()
                    require(extension in IMAGE_EXTENSIONS) { "Unsupported page image" }
                    zip.putNextEntry(ZipEntry("${index.toString().padStart(5, '0')}.$extension"))
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
            import("$name.cbz") { temporary.inputStream() }
        } finally { temporary.delete() }
    }

    suspend fun import(name: String, open: () -> InputStream): MangaBook = withContext(Dispatchers.IO) {
        require(name.endsWith(".cbz", true) || name.endsWith(".zip", true)) { "Choose a CBZ or ZIP comic archive" }
        val temporary = File.createTempFile("import-", ".part", books)
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
            val entries = entries(temporary)
            require(entries.isNotEmpty()) { "This archive contains no supported page images" }
            move(temporary, target)
            val bookId = "local:$hash"
            MangaBook(
                id = bookId, sourceId = "local", title = File(name).nameWithoutExtension,
                chapters = listOf(MangaChapter("$bookId:chapter", bookId, "local", "Read", "", archiveFile = target.path)),
            )
        } finally { temporary.delete() }
    }

    suspend fun chapterPages(chapter: MangaChapter): List<MangaPage> = withContext(Dispatchers.IO) {
        val archive = ownedArchive(chapter.archiveFile ?: error("Archive is missing"))
        entries(archive).mapIndexed { index, name ->
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

    private fun entries(file: File): List<String> = ZipFile(file).use { zip ->
        val list = zip.entries().asSequence().take(MAX_ENTRIES + 1).toList()
        require(list.size <= MAX_ENTRIES) { "Archive contains too many entries" }
        list.filter { !it.isDirectory && it.name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS }
            .map { it.name }.sortedWith(NaturalPageOrder)
    }

    companion object {
        const val MAX_ARCHIVE_BYTES = 512L * 1024 * 1024
        const val MAX_PAGE_BYTES = 32L * 1024 * 1024
        private const val MAX_ENTRIES = 10_000
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "avif", "gif")
        internal fun move(from: File, to: File) {
            try { Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}

data class MangaImageImport(val name: String, val open: () -> InputStream)
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
