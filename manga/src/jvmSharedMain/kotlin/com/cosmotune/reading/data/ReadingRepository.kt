package com.cosmotune.reading.data

import com.cosmotune.manga.data.MangaFileProtection
import com.cosmotune.manga.data.UnsealedMangaFiles
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class ReadingRepository(val root: File, protection: MangaFileProtection = UnsealedMangaFiles,
    private val client: OkHttpClient = OkHttpClient()) {
    val store = ReadingStore(root, protection)
    val state = store.state
    private val files = File(root, "books").apply { mkdirs() }
    private val documents = File(root, "documents").apply { mkdirs() }
    private val imports = Mutex()
    // A real app contact can replace this before distribution; all metadata calls identify this app.
    private val metadata = OpenLibraryBookCatalogue("Cosmotune/Reading (https://github.com/Vijay-Janarthanan/cosmotune)", client)

    suspend fun importFile(name: String, open: () -> InputStream): ReadingBook = imports.withLock {
        withContext(Dispatchers.IO) { importOwned(name, open, null) }
    }

    private suspend fun importOwned(name: String, open: () -> InputStream, source: ReadingPublication?): ReadingBook {
        val extension = name.substringAfterLast('.', "").lowercase()
        require(extension in setOf("epub", "pdf")) { "Choose an EPUB or PDF file." }
        val temporary = File.createTempFile("import-", ".part", files)
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
                    require(size <= MAX_FILE_BYTES) { "The book exceeds the 512 MB import limit." }
                    digest.update(buffer, 0, count); output.write(buffer, 0, count)
                }
                require(size > 0) { "The selected book is empty." }
                output.fd.sync()
            } }
            val id = digest.digest().joinToString("") { "%02x".format(it) }
            val target = File(files, "$id.$extension")
            val fallback = File(name).nameWithoutExtension.take(300).ifBlank { "Imported book" }
            val parsed = if (extension == "epub") ReadingEpub.open(temporary, File(documents, id)) else null
            if (parsed == null) validatePdf(temporary)
            val previous = state.value.books.find { it.id == id }
            val book = ReadingBook(id, parsed?.title?.takeIf(String::isNotBlank) ?: source?.title?.takeIf(String::isNotBlank) ?: fallback,
                authors = parsed?.authors?.takeIf { it.isNotEmpty() } ?: source?.authors.orEmpty(),
                format = if (extension == "epub") ReadingFormat.EPUB else ReadingFormat.PDF, filePath = target.path,
                coverPath = parsed?.cover?.path, language = parsed?.language ?: source?.languages?.firstOrNull().orEmpty(),
                description = parsed?.description ?: source?.description.orEmpty(),
                sourceUrl = source?.links?.firstOrNull { it.kind == ReadingLinkKind.DETAILS }?.url ?: previous?.sourceUrl,
                addedAtMs = previous?.addedAtMs ?: System.currentTimeMillis())
            ReadingStore.move(temporary, target)
            store.update { it.copy(books = it.books.filterNot { old -> old.id == book.id } + book) }
            return book
        } finally { temporary.delete() }
    }

    suspend fun openDocument(bookId: String): ReadingDocument = imports.withLock { withContext(Dispatchers.IO) {
        val book = state.value.books.find { it.id == bookId } ?: error("This book is no longer in the library.")
        val file = ownedFile(book)
        require(file.isFile && file.length() <= MAX_FILE_BYTES) { "The imported book file is missing or too large." }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) {
            currentCoroutineContext().ensureActive(); val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count)
        } }
        require(digest.digest().joinToString("") { "%02x".format(it) } == book.id) { "The owned book file changed; import the replacement edition again." }
        if (book.format == ReadingFormat.PDF) { validatePdf(file); ReadingDocument.Pdf(book, file) }
        else ReadingEpub.open(file, File(documents, book.id)).let {
            ReadingDocument.Epub(book, it.root, it.spine, it.navigation, it.resources)
        }
    } }

    suspend fun saveLocator(bookId: String, locator: ReadingLocator) {
        validateLocator(locator)
        store.update { old ->
            require(old.books.any { it.id == bookId }) { "Save the book before its reading position." }
            old.copy(progress = old.progress + (bookId to ReadingProgress(bookId, locator)))
        }
    }
    suspend fun saveBookmark(bookId: String, name: String, locator: ReadingLocator): ReadingBookmark {
        validateLocator(locator)
        val bookmark = ReadingBookmark(UUID.randomUUID().toString(), bookId, title(name), locator)
        store.update { old ->
            require(old.books.any { it.id == bookId } && old.bookmarks.size < 10_000) { "Book missing or bookmark limit reached." }
            old.copy(bookmarks = old.bookmarks + bookmark)
        }
        return bookmark
    }
    suspend fun removeBookmark(id: String) = store.update { it.copy(bookmarks = it.bookmarks.filterNot { bookmark -> bookmark.id == id }) }
    suspend fun updatePreferences(value: ReadingPreferences) {
        require(value.fontScale in 0.6f..3f && value.lineHeight in 1f..2.5f && value.marginDp in 0..80 &&
            value.fontFamily in setOf("serif", "sans-serif", "monospace", "publisher")) { "Choose supported reader preferences." }
        store.update { it.copy(preferences = value) }
    }
    suspend fun savePublication(value: ReadingPublication) = store.update { old ->
        require(old.saved.size < 10_000 || old.saved.any { it.id == value.id })
        old.copy(saved = old.saved.filterNot { it.id == value.id } + value)
    }
    suspend fun removeSaved(id: String) = store.update { it.copy(saved = it.saved.filterNot { publication -> publication.id == id }) }
    suspend fun markFinished(bookId: String, finished: Boolean = true) = store.update { old ->
        require(old.books.any { it.id == bookId })
        old.copy(finished = if (finished) old.finished + bookId else old.finished - bookId)
    }
    suspend fun createCollection(name: String): ReadingCollection {
        val collection = ReadingCollection(UUID.randomUUID().toString(), title(name))
        store.update { old -> require(old.collections.size < 128); old.copy(collections = old.collections + collection) }
        return collection
    }
    suspend fun assignCollection(bookId: String, collectionId: String, included: Boolean) = store.update { old ->
        require(old.books.any { it.id == bookId } && old.collections.any { it.id == collectionId })
        old.copy(collections = old.collections.map { if (it.id != collectionId) it else
            it.copy(bookIds = if (included) it.bookIds + bookId else it.bookIds - bookId) })
    }
    suspend fun removeCollection(id: String) = store.update { it.copy(collections = it.collections.filterNot { collection -> collection.id == id }) }

    suspend fun removeBook(bookId: String) = imports.withLock { withContext(Dispatchers.IO) {
        val book = state.value.books.find { it.id == bookId } ?: return@withContext
        val file = ownedFile(book)
        store.update { old -> old.copy(books = old.books.filterNot { it.id == bookId }, progress = old.progress - bookId,
            bookmarks = old.bookmarks.filterNot { it.bookId == bookId }, finished = old.finished - bookId,
            collections = old.collections.map { it.copy(bookIds = it.bookIds - bookId) }) }
        file.delete()
        File(documents, bookId).takeIf { it.exists() }?.let(ReadingEpub::deleteOwned)
    } }

    suspend fun saveOpdsSource(name: String, url: String, username: String = "", password: String = "", allowInsecureHttp: Boolean = false): ReadingOpdsSource {
        PersonalOpdsCatalogue("validation", url, username.takeIf(String::isNotEmpty), password, client, allowInsecureHttp)
        val id = "opds:" + MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
        val source = ReadingOpdsSource(id, title(name), url, username, password, allowInsecureHttp)
        store.update { old -> require(old.sources.size < 64 || old.sources.any { it.id == id }); old.copy(sources = old.sources.filterNot { it.id == id } + source) }
        return source
    }
    suspend fun removeOpdsSource(id: String) = store.update { it.copy(sources = it.sources.filterNot { source -> source.id == id }) }
    suspend fun browseOpds(sourceId: String, address: String? = null): ReadingCataloguePage {
        val config = state.value.sources.find { it.id == sourceId } ?: error("This catalogue was removed.")
        return PersonalOpdsCatalogue(config.id, config.url, config.username.takeIf(String::isNotEmpty), config.password, client, config.allowInsecureHttp).browse(address)
    }
    suspend fun searchOpds(sourceId: String, query: String): ReadingCataloguePage {
        val config = state.value.sources.find { it.id == sourceId } ?: error("This catalogue was removed.")
        return PersonalOpdsCatalogue(config.id, config.url, config.username.takeIf(String::isNotEmpty), config.password, client, config.allowInsecureHttp).search(query)
    }
    suspend fun search(query: String, offset: Int = 0) = metadata.search(query, offset)
    suspend fun trending(offset: Int = 0) = metadata.trending(offset)
    suspend fun editions(publication: ReadingPublication, offset: Int = 0) = metadata.editions(publication, offset)
    suspend fun gutenberg(query: String = "", page: Int = 1) = GutenbergBookCatalogue(client).browse(query, page)
    suspend fun details(publication: ReadingPublication): ReadingPublication =
        if (publication.sourceId == "gutenberg") GutenbergBookCatalogue(client).details(publication) else publication
    suspend fun publicationDetails(publication: ReadingPublication): ReadingPublication = details(publication)

    /** Only an explicit server-offered full-file acquisition can become an owned EPUB/PDF. */
    suspend fun download(publication: ReadingPublication, link: ReadingLink, onProgress: (Long, Long?) -> Unit = { _, _ -> }): ReadingBook = imports.withLock {
        withContext(Dispatchers.IO) {
            require(link in publication.links && link.kind == ReadingLinkKind.ACQUIRE) { "Open borrowing, purchase and sample links on their source." }
            val format = when (link.mediaType.substringBefore(';').lowercase()) {
                "application/epub+zip" -> "epub"
                "application/pdf" -> "pdf"
                else -> error("Only full EPUB or PDF files can be downloaded here.")
            }
            val url = link.url.toHttpUrl()
            require(url.username.isEmpty() && url.password.isEmpty() && url.fragment == null)
            val source = state.value.sources.find { it.id == publication.sourceId }
            if (source != null) {
                val origin = source.url.toHttpUrl()
                require(url.scheme == origin.scheme && url.host == origin.host && url.port == origin.port) { "Library downloads must stay on the configured server." }
                require(url.isHttps || source.allowInsecureHttp)
            } else require(publication.sourceId == "gutenberg" && url.isHttps && url.host in setOf("www.gutenberg.org", "gutenberg.org")) {
                "Connect this publication's library before downloading it."
            }
            val http = client.newBuilder().followRedirects(false).followSslRedirects(false).callTimeout(5, TimeUnit.MINUTES).build()
            val request = Request.Builder().url(url).header("Accept", link.mediaType).apply {
                source?.username?.takeIf(String::isNotEmpty)?.let { header("Authorization", Credentials.basic(it, source.password, Charsets.UTF_8)) }
            }.build()
            val download = File.createTempFile("download-", ".$format", files)
            try {
                var nextRequest = request
                var completed = false
                for (hop in 0..4) {
                val redirect = http.newCall(nextRequest).awaitReading { response ->
                    if (response.isRedirect) {
                        val address = response.header("Location")?.let { response.request.url.resolve(it) } ?: error("The download redirect has no valid target.")
                        require(address.scheme == url.scheme && address.host == url.host && address.port == url.port &&
                            address.username.isEmpty() && address.password.isEmpty() && address.fragment == null) { "The download redirected outside its authorized source." }
                        return@awaitReading address
                    }
                    check(response.isSuccessful) { "The source did not provide a downloadable file (HTTP ${response.code})." }
                    val type = response.body.contentType()?.toString()?.substringBefore(';')?.lowercase()
                    require(type == link.mediaType.substringBefore(';').lowercase() || type == "application/octet-stream") { "The source returned a different file type or a sign-in page." }
                    val length = response.body.contentLength().takeIf { it >= 0 }
                    require(length == null || length <= MAX_FILE_BYTES) { "The book exceeds the 512 MB download limit." }
                    response.body.byteStream().use { input -> download.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024); var received = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer); if (count < 0) break
                            received += count; require(received <= MAX_FILE_BYTES)
                            output.write(buffer, 0, count); onProgress(received, length)
                        }
                    } }
                    null
                }
                if (redirect == null) { completed = true; break }
                nextRequest = request.newBuilder().url(redirect).build()
                }
                check(completed) { "The source returned too many download redirects." }
                currentCoroutineContext().ensureActive()
                importOwned("${publication.title}.$format", { download.inputStream() }, publication)
            } finally { download.delete() }
        }
    }

    private fun ownedFile(book: ReadingBook): File {
        require(book.id.matches(Regex("[0-9a-f]{64}"))) { "Invalid owned book identity." }
        val expected = File(files, "${book.id}.${book.format.name.lowercase()}").canonicalFile
        require(File(book.filePath).canonicalFile == expected && expected.parentFile == files.canonicalFile) { "Book file is outside owned storage." }
        return expected
    }
    private fun validateLocator(value: ReadingLocator) {
        require(value.progression.isFinite() && value.progression in 0.0..1.0 && value.page in 0..1_000_000 &&
            value.scrollOffset.isFinite() && value.scrollOffset in 0.0..1_000_000_000.0 && (value.resourceHref?.length ?: 0) <= 2_048) { "Invalid reading position." }
    }
    private fun title(value: String) = value.trim().also { require(it.length in 1..120 && it.none(Char::isISOControl)) { "Use a name with 1–120 characters." } }
    private fun validatePdf(file: File) {
        java.io.RandomAccessFile(file, "r").use { input ->
            require(input.length() >= 8) { "This file is not a PDF document." }
            val header = ByteArray(8); input.readFully(header)
            require(header.toString(Charsets.US_ASCII).startsWith("%PDF-")) { "This file is not a PDF document." }
            val size = minOf(input.length(), 128L * 1024).toInt(); input.seek(input.length() - size)
            val tail = ByteArray(size); input.readFully(tail)
            require(tail.toString(Charsets.ISO_8859_1).contains("%%EOF")) { "This PDF file is incomplete." }
            require(!Regex("/Encrypt(?:\\s|/|<)").containsMatchIn(tail.toString(Charsets.ISO_8859_1))) { "This PDF is password protected; open it with its authorized source reader." }
        }
    }
    companion object { const val MAX_FILE_BYTES = 512L * 1024 * 1024 }
}
