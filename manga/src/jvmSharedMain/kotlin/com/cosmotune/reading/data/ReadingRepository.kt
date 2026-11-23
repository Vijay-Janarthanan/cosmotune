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
import kotlinx.coroutines.launch
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

class ReadingRepository(val root: File, protection: MangaFileProtection = UnsealedMangaFiles,
    internal val client: OkHttpClient = OkHttpClient()) {
    val store = ReadingStore(root, protection)
    val state = store.state
    private val files = File(root, "books").apply { mkdirs() }
    private val documents = File(root, "documents").apply { mkdirs() }
    private val imports = Mutex()
    // A real app contact can replace this before distribution; all metadata calls identify this app.
    private val metadata = OpenLibraryBookCatalogue("Cosmotune/Reading (https://github.com/Vijay-Janarthanan/cosmotune)", client)
    private val archive = ArchiveBookCatalogue(client)
    private val madurai = MaduraiBookCatalogue(client)
    private val textos = TextosBookCatalogue(client)
    private val openStax = OpenStaxBookCatalogue(client)
    private val globalGrey = GlobalGreyBookCatalogue(client)
    val lookup = ReadingLookup(client)
    /** Audiobooks for Listen: this device's, LibriVox's and the listener's Audiobookshelf's. */
    val audio = ReadingAudioLibrary(store, File(root, "audio"), client)
    private val fontFiles = File(root, "fonts").apply { mkdirs() }.also { ReadingFonts.directory = it; ReadingFonts.known = store.state.value.fonts }

    /** Adds a font (TTF, OTF, WOFF or WOFF2) for books, such as OpenDyslexic. */
    suspend fun importFont(name: String, open: () -> InputStream): ReadingFont = imports.withLock { withContext(Dispatchers.IO) {
        val extension = name.substringAfterLast('.', "").lowercase()
        require(extension in setOf("ttf", "otf", "woff", "woff2")) { "Choose a font file: TTF, OTF, WOFF or WOFF2." }
        val bytes = open().use { input -> input.readNBytes(16 * 1024 * 1024 + 1) }
        require(bytes.size in 64..16 * 1024 * 1024) { "This font file is empty or too large." }
        val magic = String(bytes, 0, 4, Charsets.ISO_8859_1)
        require(magic in setOf("wOFF", "wOF2", "OTTO", "true", "\u0000\u0001\u0000\u0000")) { "This file is not a font." }
        val id = MessageDigest.getInstance("SHA-256").digest(bytes).take(16).joinToString("") { "%02x".format(it) }
        File(fontFiles, "$id.$extension").writeBytes(bytes)
        val (family, weight, italic) = ReadingFont.parse(File(name).nameWithoutExtension.take(60))
        val font = ReadingFont(id, File(name).nameWithoutExtension.take(60).ifBlank { "My font" }, extension, family.take(60), weight, italic)
        store.update { old -> require(old.fonts.size < 64 || old.fonts.any { it.id == id }) { "Remove a font before adding another." }
            old.copy(fonts = old.fonts.filterNot { it.id == id } + font) }
        ReadingFonts.known = state.value.fonts
        font
    } }
    suspend fun removeFont(id: String) = withContext(Dispatchers.IO) {
        store.update { old -> old.copy(fonts = old.fonts.filterNot { it.id == id },
            preferences = if (old.preferences.fontFamily == "font:$id") old.preferences.copy(fontFamily = "serif") else old.preferences) }
        fontFiles.listFiles().orEmpty().filter { it.nameWithoutExtension == id }.forEach { it.delete() }
        ReadingFonts.known = state.value.fonts
    }
    private val dictionaryFiles = File(root, "dictionaries").apply { mkdirs() }
    private val openDictionaries = HashMap<String, StarDict>()

    /** Adds a StarDict dictionary (a ZIP or .tar.gz of its .ifo, .idx and .dict) for looking words up without a connection. */
    suspend fun importDictionary(name: String, open: () -> InputStream): ReadingDictionary = imports.withLock { withContext(Dispatchers.IO) {
        val temporary = File.createTempFile("dictionary-", ".part", dictionaryFiles)
        val id = UUID.randomUUID().toString()
        val target = File(dictionaryFiles, id)
        try {
            open().use { input -> temporary.outputStream().use { output -> input.copyTo(output) } }
            require(temporary.length() in 1..512L * 1024 * 1024) { "This dictionary is empty or too large." }
            val dictionary = StarDict.install(temporary, target)
            val entry = ReadingDictionary(id, dictionary.name.take(120).ifBlank { File(name).nameWithoutExtension }, dictionary.words)
            store.update { old -> require(old.dictionaries.size < 32) { "Remove a dictionary before adding another." }; old.copy(dictionaries = old.dictionaries + entry) }
            synchronized(openDictionaries) { openDictionaries[id] = dictionary }
            entry
        } catch (failure: Exception) { target.deleteRecursively(); throw failure }
        finally { temporary.delete() }
    } }
    suspend fun removeDictionary(id: String) = withContext(Dispatchers.IO) {
        store.update { old -> old.copy(dictionaries = old.dictionaries.filterNot { it.id == id }) }
        synchronized(openDictionaries) { openDictionaries.remove(id) }
        if (id.matches(Regex("[0-9a-f-]{36}"))) File(dictionaryFiles, id).deleteRecursively()
    }
    /** [word]'s entries in the dictionaries on this device, in the order they were added. */
    suspend fun defineOffline(word: String): List<ReadingDefinition> = withContext(Dispatchers.IO) {
        state.value.dictionaries.flatMap { entry ->
            val dictionary = synchronized(openDictionaries) { openDictionaries[entry.id] } ?: runCatching { StarDict(File(dictionaryFiles, entry.id)) }.getOrNull()
                ?.also { synchronized(openDictionaries) { openDictionaries[entry.id] = it } } ?: return@flatMap emptyList()
            runCatching { dictionary.lookup(word) }.getOrDefault(emptyList()).map { ReadingDefinition(entry.name, word.trim(), it) }
        }
    }
    /** Keeps [word] at the top of the recent look-ups. */
    suspend fun rememberLookup(word: String) {
        val clean = word.trim().take(100).takeIf { it.isNotEmpty() && it.none(Char::isISOControl) } ?: return
        store.update { old -> old.copy(lookups = (listOf(clean) + old.lookups.filterNot { it.equals(clean, true) }).take(100)) }
    }
    suspend fun clearLookups() = store.update { it.copy(lookups = emptyList()) }

    suspend fun importFile(name: String, open: () -> InputStream): ReadingBook = imports.withLock {
        withContext(Dispatchers.IO) { importOwned(name, open, null) }
    }

    private suspend fun importOwned(name: String, open: () -> InputStream, source: ReadingPublication?, server: Pair<String, String>? = null): ReadingBook {
        val convertKind = ReadingConvert.kindOf(name)
        val extension = if (convertKind != null) "epub" else name.substringAfterLast('.', "").lowercase()
        require(extension in setOf("epub", "pdf")) { "Read opens EPUB, PDF, MOBI, AZW3, FB2, TXT, HTML, Markdown, Word, RTF and CBZ books." }
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
            val fallback = File(name).nameWithoutExtension.removeSuffix(".fb2").take(300).ifBlank { "Imported book" }
            var fileSha = ""
            val koreaderHash = ReadingKoSyncClient.documentHash(temporary)
            if (convertKind != null) {
                // Converted once, here: the book keeps its original file's identity and owns the EPUB made from it.
                val converted = File.createTempFile("convert-", ".part", files)
                try {
                    ReadingConvert.writeEpub(ReadingConvert.convert(convertKind, temporary, source?.title?.takeIf(String::isNotBlank) ?: fallback), converted)
                    fileSha = sha256(converted)
                    ReadingStore.move(converted, temporary)
                } finally { converted.delete() }
            }
            val parsed = if (extension == "epub") ReadingEpub.open(temporary, File(documents, id)) else null
            if (parsed == null) validatePdf(temporary)
            val previous = state.value.books.find { it.id == id }
            val book = ReadingBook(id, parsed?.title?.takeIf(String::isNotBlank) ?: source?.title?.takeIf(String::isNotBlank) ?: fallback,
                authors = parsed?.authors?.takeIf { it.isNotEmpty() } ?: source?.authors.orEmpty(),
                format = if (extension == "epub") ReadingFormat.EPUB else ReadingFormat.PDF, filePath = target.path,
                coverPath = parsed?.cover?.path, language = parsed?.language ?: source?.languages?.firstOrNull().orEmpty(),
                description = parsed?.description ?: source?.description.orEmpty(),
                sourceUrl = source?.links?.firstOrNull { it.kind == ReadingLinkKind.DETAILS }?.url ?: previous?.sourceUrl,
                addedAtMs = previous?.addedAtMs ?: System.currentTimeMillis(),
                originalFormat = convertKind?.let(ReadingConvert::label).orEmpty(), fileSha = fileSha, koreaderHash = koreaderHash,
                serverSource = server?.first ?: previous?.serverSource.orEmpty(), serverLink = server?.second ?: previous?.serverLink.orEmpty(),
                series = parsed?.series.orEmpty(), seriesIndex = parsed?.seriesIndex, subjects = parsed?.subjects.orEmpty(),
                tags = previous?.tags.orEmpty())
            ReadingStore.move(temporary, target)
            store.update { it.copy(books = it.books.filterNot { old -> old.id == book.id } + book) }
            return book
        } finally { temporary.delete() }
    }

    suspend fun openDocument(bookId: String): ReadingDocument = imports.withLock { withContext(Dispatchers.IO) {
        val book = state.value.books.find { it.id == bookId } ?: error("This book is no longer in the library.")
        val file = ownedFile(book)
        require(file.isFile && file.length() <= MAX_FILE_BYTES) { "The imported book file is missing or too large." }
        require(sha256(file) == book.fileSha.ifBlank { book.id }) { "The owned book file changed; import the replacement edition again." }
        val document = if (book.format == ReadingFormat.PDF) { validatePdf(file); ReadingDocument.Pdf(book, file) }
        else ReadingEpub.open(file, File(documents, book.id)).let {
            ReadingDocument.Epub(book, it.root, it.spine, it.navigation, it.resources, it.overlays, it.overlayClass)
        }
        store.update { old -> old.copy(opened = (old.opened + (bookId to System.currentTimeMillis())).entries.sortedBy { it.value }.takeLast(500).associate { it.key to it.value }) }
        document
    } }

    /** Keeps a line drawn on page [page] of the PDF [bookId] ([points]: x, y shares of the page). Gives it, or null when it is too short. */
    suspend fun addInk(bookId: String, page: Int, ink: ReadingInk, points: List<Float>): ReadingInkStroke? {
        val clean = ReadingInkMath.simplify(points)
        if (clean.size < 2) return null
        val stroke = ReadingInkStroke(UUID.randomUUID().toString(), bookId, page.coerceAtLeast(0), ink, clean)
        store.update { old ->
            require(old.books.any { it.id == bookId && it.format == ReadingFormat.PDF }) { "Drawing works on PDFs in the library." }
            require(old.inks.size < ReadingInkMath.MAX_STROKES) { "There are too many drawings; clear some pages first." }
            old.copy(inks = old.inks + stroke)
        }
        return stroke
    }

    /** Removes the drawn line [id]. */
    suspend fun removeInk(id: String) = store.update { old -> old.copy(inks = old.inks.filterNot { it.id == id }) }

    /** Removes every line drawn on [page] of [bookId]. */
    suspend fun clearInk(bookId: String, page: Int) = store.update { old -> old.copy(inks = old.inks.filterNot { it.bookId == bookId && it.page == page }) }

    /** Takes [bookId] out of the history (all of it with null); the books and their places stay. */
    suspend fun forgetOpened(bookId: String?) = store.update { old -> old.copy(opened = if (bookId == null) emptyMap() else old.opened - bookId) }

    suspend fun saveLocator(bookId: String, locator: ReadingLocator) {
        validateLocator(locator)
        store.update { old ->
            require(old.books.any { it.id == bookId }) { "Save the book before its reading position." }
            old.copy(progress = old.progress + (bookId to ReadingProgress(bookId, locator)))
        }
    }
    /** Adds [ms] of reading [bookId] today, and to its pace when [covered] (a share of the book read forward) is believable. */
    suspend fun recordReading(bookId: String, ms: Long, covered: Double) {
        if (ms <= 0 || ms > 30 * 60_000) return
        val day = ReadingStatistics.dayKey(System.currentTimeMillis())
        store.update { old ->
            if (old.books.none { it.id == bookId }) return@update old
            val pace = old.paces[bookId] ?: ReadingPace()
            val steady = covered in 0.0..0.05
            old.copy(readingMs = old.readingMs + (bookId to (old.readingMs[bookId] ?: 0L) + ms),
                readingDays = (old.readingDays + (day to (old.readingDays[day] ?: 0L) + ms)).entries.sortedBy { it.key }.takeLast(800).associate { it.key to it.value },
                paces = if (steady) old.paces + (bookId to ReadingPace(pace.fraction + covered, pace.ms + ms)) else old.paces)
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
    /** Highlights [text] (offsets [start] until [end] of chapter [href]); an overlapping highlight of the same chapter is replaced. */
    suspend fun saveHighlight(bookId: String, href: String, start: Int, end: Int, text: String, style: ReadingMarkStyle, progression: Double, note: String = "",
        section: Int = 0, chapter: String = ""): ReadingHighlight {
        require(href.length in 1..2_048 && start in 0..50_000_000 && end in start + 1..50_000_000 && progression.isFinite()) { "Choose some words to highlight." }
        val highlight = ReadingHighlight(UUID.randomUUID().toString(), bookId, href, start, end, text.trim().take(4_000), style, note(note), progression.coerceIn(0.0, 1.0),
            section = section.coerceIn(0, 100_000), chapter = chapter.trim().take(200))
        store.update { old ->
            require(old.books.any { it.id == bookId } && old.highlights.size < 50_000) { "Book missing or highlight limit reached." }
            old.copy(highlights = old.highlights.filterNot { it.bookId == bookId && it.href == href && it.start < end && start < it.end } + highlight)
        }
        return highlight
    }
    /** Changes a highlight's [style] or [note]. */
    suspend fun updateHighlight(id: String, style: ReadingMarkStyle? = null, note: String? = null) = store.update { old ->
        old.copy(highlights = old.highlights.map { if (it.id != id) it else it.copy(style = style ?: it.style, note = note?.let(::note) ?: it.note, updatedAtMs = System.currentTimeMillis()) })
    }
    suspend fun removeHighlight(id: String) = store.update { it.copy(highlights = it.highlights.filterNot { mark -> mark.id == id }, removed = it.removed + (id to System.currentTimeMillis())) }
    /** Puts a removed highlight back (its row's Undo). */
    suspend fun restoreHighlight(mark: ReadingHighlight) = store.update { old ->
        if (old.highlights.any { it.id == mark.id } || old.books.none { it.id == mark.bookId }) old
        else old.copy(highlights = old.highlights + mark.copy(updatedAtMs = System.currentTimeMillis()), removed = old.removed - mark.id)
    }
    suspend fun removeBookmark(id: String) = store.update { it.copy(bookmarks = it.bookmarks.filterNot { bookmark -> bookmark.id == id }, removed = it.removed + (id to System.currentTimeMillis())) }
    suspend fun updatePreferences(value: ReadingPreferences) {
        require(value.fontScale in 0.6f..3f && value.lineHeight in 1f..2.5f && value.marginDp in 0..80 && value.speechRate in 0.5f..3f && value.voice.length <= 300 && value.translateTo.length <= 12 &&
            (value.fontFamily in setOf("serif", "sans-serif", "monospace", "publisher") || value.fontFamily.removePrefix("font:").let { id -> state.value.fonts.any { it.id == id } }) &&
            value.paragraphSpacing in -1f..3f && value.columns in 1..2 && value.customCss.length <= 4_000 &&
            listOf("url(", "@import", "expression", "behavior", "</", "javascript:").none { value.customCss.contains(it, true) } &&
            listOf(value.pageColor, value.textColor).all { it.matches(Regex("#[0-9a-fA-F]{6}")) }) { "Choose supported reader preferences." }
        store.update { it.copy(preferences = value) }
    }
    suspend fun savePublication(value: ReadingPublication) = store.update { old ->
        require(old.saved.size < 10_000 || old.saved.any { it.id == value.id })
        old.copy(saved = old.saved.filterNot { it.id == value.id } + value)
    }
    suspend fun removeSaved(id: String) = store.update { it.copy(saved = it.saved.filterNot { publication -> publication.id == id }) }
    /** Pairs [bookId] with the Listen shelf's [audiobookId] for listening along, or unpairs it (null). */
    suspend fun pairAudio(bookId: String, audiobookId: String?) = store.update { old ->
        require(old.books.any { it.id == bookId }) { "This book is no longer in the library." }
        require(audiobookId == null || old.audiobooks.any { it.id == audiobookId }) { "This audiobook is no longer on the Listen shelf." }
        old.copy(audioPairs = if (audiobookId == null) old.audioPairs - bookId else old.audioPairs + (bookId to audiobookId))
    }

    suspend fun markFinished(bookId: String, finished: Boolean = true) {
        store.update { old ->
            require(old.books.any { it.id == bookId })
            old.copy(finished = if (finished) old.finished + bookId else old.finished - bookId, finishedAt = old.finishedAt + (bookId to System.currentTimeMillis()))
        }
        if (state.value.hardcover != null) background.launch { runCatching { sendHardcover(bookId) } }
    }
    /** Sets [bookId]'s own tags (each 1–40 characters). */
    suspend fun setTags(bookId: String, tags: Set<String>) {
        val clean = tags.map { it.trim() }.filter { it.isNotEmpty() }.onEach { require(it.length <= 40 && it.none(Char::isISOControl)) { "Keep a tag under 40 characters." } }.toSet()
        require(clean.size <= 30) { "A book can have up to 30 tags." }
        store.update { old -> old.copy(books = old.books.map { if (it.id == bookId) it.copy(tags = clean) else it }) }
    }
    /** Adds [add] to and takes [remove] off each of [bookIds]' tags at once (books chosen together in the library). */
    suspend fun editTags(bookIds: Set<String>, add: Set<String>, remove: Set<String>) {
        val clean = add.map { it.trim() }.filter { it.isNotEmpty() }.onEach { require(it.length <= 40 && it.none(Char::isISOControl)) { "Keep a tag under 40 characters." } }.toSet()
        store.update { old -> old.copy(books = old.books.map { book ->
            if (book.id !in bookIds) book else book.copy(tags = (book.tags - remove + clean).let { if (it.size > 30) book.tags - remove else it })
        }) }
    }
    /** Saves the current look as a style called [name] (replacing one of that name), or removes it with [remove]. */
    suspend fun saveProfile(name: String, preferences: ReadingPreferences, remove: Boolean = false) {
        val clean = title(name)
        store.update { old ->
            val others = old.profiles.filterNot { it.name.equals(clean, true) }
            require(remove || others.size < 20) { "Remove a style before saving another." }
            old.copy(profiles = if (remove) others else others + ReadingProfile(clean, preferences))
        }
    }
    /** A PDF as a reflowing book from its pages' [text] (ten pages a chapter), added beside the PDF. */
    suspend fun importPdfText(bookId: String, text: List<String>): ReadingBook {
        val book = state.value.books.find { it.id == bookId } ?: error("This book is no longer in the library.")
        require(text.any { it.isNotBlank() }) { "This PDF has no text to copy (it may be scanned pictures)." }
        val markdown = buildString {
            text.chunked(10).forEachIndexed { part, pages ->
                append("## Pages ${part * 10 + 1}–${part * 10 + pages.size}\n\n")
                pages.forEach { page -> ReadingConvert.reflowLines(page).forEach { append(it.replace("#", "\\#")).append("\n\n") } }
            }
        }
        val copy = importFile("${book.title} (text).md") { markdown.toByteArray().inputStream() }
        store.update { old -> old.copy(books = old.books.map { if (it.id == copy.id) it.copy(title = "${book.title} (text)", authors = book.authors, originalFormat = "PDF text") else it }) }
        return state.value.books.first { it.id == copy.id }
    }

    /** Saves the article at [address] (its main text and pictures) as a book. */
    suspend fun importWebPage(address: String): ReadingBook = withContext(Dispatchers.IO) {
        val url = runCatching { address.trim().toHttpUrl() }.getOrNull() ?: throw IllegalArgumentException("Enter the page's full address, starting with https://")
        require(url.isHttps) { "Only https:// pages can be saved." }
        val http = client.newBuilder().callTimeout(30, TimeUnit.SECONDS).build()
        val html = http.newCall(Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Cosmotune Read)").build()).awaitReading { response ->
            check(response.isSuccessful) { "The page answered ${response.code}." }
            require(response.body.contentType()?.subtype?.contains("html") != false) { "That address is not a web page." }
            response.catalogueText(10 * 1024 * 1024)
        }
        val page = org.jsoup.Jsoup.parse(html, url.toString())
        val title = page.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() } ?: page.title().ifBlank { url.host }
        val author = page.selectFirst("meta[name=author]")?.attr("content").orEmpty()
        val article = ReadingConvert.mainContent(page)
        var fetched = 0L
        article.select("img").take(40).forEach { image ->
            val source = image.absUrl("src").toHttpUrlOrNull()
            val bytes = if (source == null || !source.isHttps || fetched > 20L * 1024 * 1024) null else runCatching {
                http.newCall(Request.Builder().url(source).build()).execute().use { response ->
                    if (!response.isSuccessful || response.body.contentLength() > 4 * 1024 * 1024) null else response.body.bytes().takeIf { it.size <= 4 * 1024 * 1024 }
                }
            }.getOrNull()
            val type = bytes?.let(ReadingConvert::imageType)
            if (bytes == null || type == null) image.remove() else { fetched += bytes.size; image.attr("src", "data:$type;base64," + java.util.Base64.getEncoder().encodeToString(bytes)) }
        }
        val document = "<html><head><title>${ReadingConvert.escape(title)}</title><meta name=\"author\" content=\"${ReadingConvert.escape(author)}\"></head><body><h1>${ReadingConvert.escape(title)}</h1>${article.html()}</body></html>"
        val book = importFile(title.take(100).replace(Regex("[\\\\/:*?\"<>|]"), " ") + ".html") { document.toByteArray().inputStream() }
        store.update { old -> old.copy(books = old.books.map { if (it.id == book.id) it.copy(sourceUrl = url.toString(), originalFormat = "Web page") else it }) }
        state.value.books.first { it.id == book.id }
    }

    /** [text] the reader pasted, as a book called [title]. */
    suspend fun importText(title: String, text: String): ReadingBook {
        require(text.isNotBlank()) { "Paste some text first." }
        return importFile(title(title.ifBlank { "Pasted text" }).replace(Regex("[\\\\/:*?\"<>|]"), " ") + ".txt") { text.toByteArray().inputStream() }
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
            bookmarks = old.bookmarks.filterNot { it.bookId == bookId }, highlights = old.highlights.filterNot { it.bookId == bookId }, finished = old.finished - bookId,
            collections = old.collections.map { it.copy(bookIds = it.bookIds - bookId) }) }
        file.delete()
        File(documents, bookId).takeIf { it.exists() }?.let(ReadingEpub::deleteOwned)
    } }

    /**
     * Takes [bookId] out of the library with its place, bookmarks, Finished mark and collections, but keeps its files, so a "Removed ·
     * Undo" can put it back ([putBackBook]) until [deleteTakenBook] deletes them for good. Null when there is no such book.
     */
    suspend fun takeOutBook(bookId: String): TakenBook? = imports.withLock { withContext(Dispatchers.IO) {
        val old = state.value
        val book = old.books.find { it.id == bookId } ?: return@withContext null
        val taken = TakenBook(book, old.progress[bookId], old.bookmarks.filter { it.bookId == bookId }, bookId in old.finished,
            old.collections.filter { bookId in it.bookIds }.map { it.id }.toSet(), old.highlights.filter { it.bookId == bookId })
        store.update { now -> now.copy(books = now.books.filterNot { it.id == bookId }, progress = now.progress - bookId,
            bookmarks = now.bookmarks.filterNot { it.bookId == bookId }, highlights = now.highlights.filterNot { it.bookId == bookId }, finished = now.finished - bookId,
            collections = now.collections.map { it.copy(bookIds = it.bookIds - bookId) }) }
        taken
    } }

    /** Puts a book [takeOutBook] took out back as it was (unless it is in the library again already). */
    suspend fun putBackBook(taken: TakenBook) {
        val id = taken.book.id
        store.update { old ->
            if (old.books.any { it.id == id }) old
            else old.copy(books = old.books + taken.book, progress = taken.progress?.let { old.progress + (id to it) } ?: old.progress,
                bookmarks = old.bookmarks + taken.bookmarks.filter { mark -> old.bookmarks.none { it.id == mark.id } },
                highlights = old.highlights + taken.highlights.filter { mark -> old.highlights.none { it.id == mark.id } },
                finished = if (taken.finished) old.finished + id else old.finished,
                collections = old.collections.map { if (it.id in taken.collections) it.copy(bookIds = it.bookIds + id) else it })
        }
    }

    /** Deletes the files of a book [takeOutBook] took out, once its Undo has run out; nothing if it is back in the library. */
    suspend fun deleteTakenBook(taken: TakenBook) = imports.withLock { withContext(Dispatchers.IO) {
        if (state.value.books.any { it.id == taken.book.id }) return@withContext
        if (state.value.inks.any { it.bookId == taken.book.id }) store.update { old -> old.copy(inks = old.inks.filterNot { it.bookId == taken.book.id }) }
        ownedFile(taken.book).delete()
        File(documents, taken.book.id).takeIf { it.exists() }?.let(ReadingEpub::deleteOwned)
    } }

    /** Puts a removed bookmark back (the bookmark row's Undo). */
    suspend fun restoreBookmark(mark: ReadingBookmark) = store.update { old ->
        if (old.bookmarks.any { it.id == mark.id } || old.books.none { it.id == mark.bookId }) old
        else old.copy(bookmarks = old.bookmarks + mark.copy(createdAtMs = System.currentTimeMillis()), removed = old.removed - mark.id)
    }

    private val webDav = ReadingWebDavClient(client)
    private val syncing = Mutex()

    /** Connects the WebDAV folder books sync through, and syncs once to check it. */
    suspend fun connectWebDav(url: String, username: String, password: String): String {
        ReadingWebDavClient.folder(url)
        val config = ReadingWebDav(url.trim(), username.trim(), password)
        store.update { it.copy(webDav = config) }
        return syncWebDav() ?: "Synced."
    }
    suspend fun disconnectWebDav() = store.update { it.copy(webDav = null) }

    /**
     * Brings this device and the WebDAV folder together: reads the folder's file, merges it with this library (the later of each
     * place, mark and Finished; removals stay removed), writes the result back unless another device wrote meanwhile (then again).
     * Null when nothing is connected; otherwise a short line for Settings.
     */
    suspend fun syncWebDav(): String? = syncing.withLock {
        val config = state.value.webDav ?: return@withLock null
        val result = try {
            var attempts = 0
            while (true) {
                val (remote, tag) = webDav.read(config)
                val merged = ReadingSyncMerge.merge(ReadingSyncMerge.of(state.value), remote)
                store.update { ReadingSyncMerge.apply(it, merged) }
                if (merged == remote || webDav.write(config, merged, tag)) break
                require(++attempts < 4) { "Another device kept writing; try again in a moment." }
            }
            "Synced"
        } catch (failure: kotlinx.coroutines.CancellationException) { throw failure }
        catch (failure: Exception) { "Not synced: " + (failure.message ?: "the server could not be reached.") }
        store.update { old -> old.copy(webDav = old.webDav?.copy(lastSyncMs = System.currentTimeMillis(), lastResult = result)) }
        result
    }

    suspend fun addFolder(folder: ReadingFolder) = store.update { old ->
        require(old.folders.size < 16 || old.folders.any { it.location == folder.location }) { "Remove a folder before adding another." }
        old.copy(folders = old.folders.filterNot { it.location == folder.location } + folder)
    }
    suspend fun removeFolder(location: String) = store.update { old ->
        old.copy(folders = old.folders.filterNot { it.location == location }, folderFiles = old.folderFiles.filterKeys { !it.startsWith(location) })
    }

    /**
     * Adds the books in [files] that Read has not taken yet (each file once; a changed file again), reporting each as it goes.
     * Files Read cannot open are remembered so they are not tried on every visit. Returns how many books were added.
     */
    suspend fun scanFolder(files: List<ReadingFolderFile>, onProgress: (Int, Int) -> Unit = { _, _ -> }): Int {
        val known = state.value.folderFiles
        val fresh = files.filter { file -> file.key !in known && (file.name.substringAfterLast('.', "").lowercase() in setOf("epub", "pdf") || ReadingConvert.kindOf(file.name) != null) }
        var added = 0
        fresh.forEachIndexed { index, file ->
            onProgress(index + 1, fresh.size)
            val outcome = try {
                val count = state.value.books.size
                val book = importFile(file.name, file.open)
                if (state.value.books.size > count) added++
                book.id
            } catch (failure: kotlinx.coroutines.CancellationException) { throw failure }
            catch (_: Exception) { "!" }
            store.update { old -> old.copy(folderFiles = (old.folderFiles + (file.key to outcome)).entries.toList().takeLast(20_000).associate { it.key to it.value }) }
        }
        return added
    }

    private val koSync = ReadingKoSyncClient(client)
    private val background = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    /** [pushProgress] after the reader has closed, without holding the page up. */
    fun pushProgressLater(bookId: String, spine: List<String>) {
        if (state.value.koSync == null && state.value.hardcover == null && state.value.books.none { it.id == bookId && it.serverLink.isNotEmpty() }) return
        background.launch { runCatching { pushProgress(bookId, spine) } }
    }
    private val serverProgress = ReadingServerProgress(client)

    /** Signs in to KOReader sync (with [create], makes the account first); only the key KOReader derives from the password is kept. */
    suspend fun connectKoSync(server: String, username: String, password: String, create: Boolean) {
        require(username.isNotBlank() && password.isNotEmpty()) { "Enter the user name and password." }
        val account = ReadingKoSync(ReadingKoSyncClient.server(server).toString(), username.trim(), ReadingKoSyncClient.key(password),
            state.value.koSync?.device ?: UUID.randomUUID().toString().replace("-", "").uppercase())
        koSync.signIn(account, create)
        store.update { it.copy(koSync = account.copy(lastResult = "Signed in")) }
    }
    suspend fun disconnectKoSync() = store.update { it.copy(koSync = null) }

    private val hardcoverClient = ReadingHardcoverClient(client)

    /** Connects Hardcover with the reader's API key (from hardcover.app › Settings › Hardcover API); the key is kept, never shown. */
    suspend fun connectHardcover(token: String) {
        val key = token.trim().removePrefix("Bearer ").trim()
        require(key.length in 20..4000 && key.none(Char::isWhitespace)) { "Paste the whole API key from hardcover.app › Settings › Hardcover API." }
        val name = hardcoverClient.me(key)
        store.update { it.copy(hardcover = ReadingHardcover(key, name, "Connected")) }
    }
    suspend fun disconnectHardcover() = store.update { it.copy(hardcover = null, hardcoverBooks = emptyMap()) }

    /**
     * Tells Hardcover where the reader is with [bookId]: Currently reading with the page reached, or Read once it is finished. The
     * book is matched by its title and author the first time; the result is kept for Settings, never shown over the book.
     */
    suspend fun sendHardcover(bookId: String, spine: List<String> = emptyList()) {
        val current = state.value
        val account = current.hardcover ?: return
        val book = current.books.find { it.id == bookId } ?: return
        val finished = bookId in current.finished
        val locator = current.progress[bookId]?.locator
        if (!finished && locator == null) return
        val result = try {
            val known = current.hardcoverBooks[bookId] ?: hardcoverClient.find(account.token, book.title, book.authors.firstOrNull().orEmpty())
                ?: error("“${book.title.take(60)}” is not on Hardcover.")
            val fraction = if (finished) 1.0 else locator?.let { overall(book, it, spine) } ?: 0.0
            val sent = hardcoverClient.send(account.token, known, if (finished) ReadingHardcoverClient.READ else ReadingHardcoverClient.READING, fraction,
                java.time.LocalDate.now().toString())
            store.update { old -> old.copy(hardcoverBooks = old.hardcoverBooks + (bookId to sent)) }
            "Sent “${book.title.take(60)}” " + java.time.LocalTime.now().withNano(0).toString().take(5)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { "Not sent: " + (failure.message ?: "Hardcover could not be reached.") }
        store.update { old -> old.copy(hardcover = old.hardcover?.copy(lastResult = result)) }
    }

    private suspend fun koreaderHash(book: ReadingBook): String = book.koreaderHash.ifBlank {
        if (book.originalFormat.isNotBlank()) return "" // A converted book's own file is gone; KOReader would know it by that file.
        withContext(Dispatchers.IO) { ReadingKoSyncClient.documentHash(ownedFile(book)) }.also { hash ->
            store.update { old -> old.copy(books = old.books.map { if (it.id == book.id) it.copy(koreaderHash = hash) else it }) }
        }
    }

    /**
     * Sends [bookId]'s place to KOReader sync and back to the library server it came from (Komga, Kavita), where those are set up.
     * [spine] is the book's reading order. Failures are kept for Settings, never shown over the book.
     */
    suspend fun pushProgress(bookId: String, spine: List<String>) {
        val current = state.value
        val book = current.books.find { it.id == bookId } ?: return
        val locator = current.progress[bookId]?.locator ?: return
        current.koSync?.let { account ->
            val result = try {
                val hash = koreaderHash(book)
                if (hash.isNotEmpty()) koSync.push(account, hash, ReadingKoSyncClient.progressOf(book.format, locator, spine), overall(book, locator, spine))
                "Synced " + java.time.LocalTime.now().withNano(0).toString().take(5)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { "Not synced: " + (failure.message ?: "the server could not be reached.") }
            store.update { old -> old.copy(koSync = old.koSync?.copy(lastResult = result)) }
        }
        if (current.hardcover != null) sendHardcover(bookId, spine)
        ReadingServerProgress.target(book.serverLink)?.let { target ->
            runCatching {
                serverProgress.send(target, current.sources.find { it.id == book.serverSource }, book, locator,
                    spine.indexOf(locator.resourceHref?.substringBefore('#')).coerceAtLeast(0), bookId in current.finished)
            }
        }
    }

    /** Where KOReader sync says another device left [bookId], when that is newer than this device's place. */
    suspend fun remotePosition(bookId: String, spine: List<String>): ReadingRemotePosition? {
        val current = state.value
        val account = current.koSync ?: return null
        val book = current.books.find { it.id == bookId } ?: return null
        val hash = koreaderHash(book).ifEmpty { return null }
        val (progress, percentage, by) = runCatching { koSync.pull(account, hash) }.getOrNull() ?: return null
        val locator = ReadingKoSyncClient.locatorOf(book.format, progress, spine) ?: return null
        if (by.second <= (current.progress[bookId]?.updatedAtMs ?: 0L) + 5_000) return null
        if (locator.resourceHref == current.progress[bookId]?.locator?.resourceHref && book.format == ReadingFormat.EPUB) return null
        return ReadingRemotePosition(locator, percentage, by.first.ifBlank { "another device" }, by.second)
    }

    private fun overall(book: ReadingBook, locator: ReadingLocator, spine: List<String>): Double =
        if (book.format == ReadingFormat.PDF || spine.size <= 1) locator.progression
        else (spine.indexOf(locator.resourceHref?.substringBefore('#')).coerceAtLeast(0) + locator.progression) / spine.size

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
    suspend fun search(query: String, offset: Int = 0, author: Boolean = false, language: String? = null) = metadata.search(query, offset, author, language)
    suspend fun trending(offset: Int = 0) = metadata.trending(offset)
    suspend fun editions(publication: ReadingPublication, offset: Int = 0) = metadata.editions(publication, offset)
    suspend fun gutenberg(query: String = "", page: Int = 1) = GutenbergBookCatalogue(client).browse(query, page)
    suspend fun standardEbooks() = StandardEbooksCatalogue(client).browse()
    suspend fun archiveBooks(query: String, offset: Int) = archive.browse(query, offset)
    suspend fun tamilBooks(query: String, offset: Int) = madurai.browse(query, offset)
    suspend fun spanishBooks(address: String?, query: String?) = textos.browse(address, query)
    suspend fun textbooks(query: String, offset: Int) = openStax.browse(query, offset)
    suspend fun globalGreyBooks() = globalGrey.browse()
    suspend fun details(publication: ReadingPublication): ReadingPublication = when (publication.sourceId) {
        "gutenberg" -> GutenbergBookCatalogue(client).details(publication)
        ArchiveBookCatalogue.ID -> archive.details(publication)
        GlobalGreyBookCatalogue.ID -> globalGrey.details(publication)
        else -> publication
    }
    suspend fun publicationDetails(publication: ReadingPublication): ReadingPublication = details(publication)

    /** Only an explicit server-offered full-file acquisition can become an owned EPUB/PDF. */
    /** Page [page] (from 0) of a comic on the listener's own library, by its page stream; only from that library's server. */
    suspend fun streamPage(publication: ReadingPublication, link: ReadingLink, page: Int, maxWidth: Int = 1600): ByteArray = withContext(Dispatchers.IO) {
        require(link in publication.links && link.kind == ReadingLinkKind.STREAM) { "This book cannot be read page by page." }
        val source = state.value.sources.find { it.id == publication.sourceId } ?: error("Connect this library again to read from it.")
        val url = link.url.replace("{pageNumber}", page.coerceIn(0, maxOf(0, link.count - 1)).toString()).replace("{maxWidth}", maxWidth.toString()).toHttpUrl()
        val origin = source.url.toHttpUrl()
        require(url.scheme == origin.scheme && url.host == origin.host && url.port == origin.port && (url.isHttps || source.allowInsecureHttp)) {
            "Pages must come from the library's own server."
        }
        val http = client.newBuilder().followRedirects(false).callTimeout(60, TimeUnit.SECONDS).build()
        val request = Request.Builder().url(url).header("Accept", "image/*").apply {
            source.username.takeIf(String::isNotEmpty)?.let { header("Authorization", Credentials.basic(it, source.password, Charsets.UTF_8)) }
        }.build()
        http.newCall(request).awaitReading { response ->
            check(response.isSuccessful) { "The library answered HTTP ${response.code} for this page." }
            require(response.body.contentLength() <= 32L * 1024 * 1024) { "This page is too large." }
            response.body.bytes().also { require(it.size <= 32 * 1024 * 1024) { "This page is too large." } }
        }
    }

    suspend fun download(publication: ReadingPublication, link: ReadingLink, onProgress: (Long, Long?) -> Unit = { _, _ -> }): ReadingBook = imports.withLock {
        withContext(Dispatchers.IO) {
            require(link in publication.links && link.kind == ReadingLinkKind.ACQUIRE) { "Open borrowing, purchase and sample links on their source." }
            val format = link.downloadFormat?.name?.lowercase() ?: error("Only full EPUB or PDF files can be downloaded here.")
            val url = link.url.toHttpUrl()
            require(url.username.isEmpty() && url.password.isEmpty() && url.fragment == null)
            val source = state.value.sources.find { it.id == publication.sourceId }
            if (source != null) {
                val origin = source.url.toHttpUrl()
                require(url.scheme == origin.scheme && url.host == origin.host && url.port == origin.port) { "Library downloads must stay on the configured server." }
                require(url.isHttps || source.allowInsecureHttp)
            } else {
                val allowed = when (publication.sourceId) {
                    "gutenberg" -> url.isHttps && url.host in setOf("www.gutenberg.org", "gutenberg.org")
                    StandardEbooksCatalogue.ID -> format == "epub" && StandardEbooksCatalogue.isEditionUrl(url)
                    ArchiveBookCatalogue.ID -> ArchiveBookCatalogue.itemId(publication)?.let { ArchiveBookCatalogue.isEdition(url, it) } == true
                    MaduraiBookCatalogue.ID -> format == "pdf" && MaduraiBookCatalogue.isEdition(url)
                    TextosBookCatalogue.ID -> TextosBookCatalogue.isEdition(url, format)
                    OpenStaxBookCatalogue.ID -> format == "pdf" && OpenStaxBookCatalogue.isEdition(url)
                    GlobalGreyBookCatalogue.ID -> GlobalGreyBookCatalogue.isEdition(url, format)
                    else -> false
                }
                require(allowed) { "Connect this publication's library before downloading it." }
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
                        val sameOrigin = address.scheme == url.scheme && address.host == url.host && address.port == url.port
                        val allowedRedirect = if (source != null) sameOrigin else when (publication.sourceId) {
                            ArchiveBookCatalogue.ID -> ArchiveBookCatalogue.isRedirect(url, address)
                            MaduraiBookCatalogue.ID -> MaduraiBookCatalogue.isEdition(address) && address.encodedPath == url.encodedPath
                            TextosBookCatalogue.ID -> TextosBookCatalogue.isEdition(address, format) && address.encodedPath == url.encodedPath
                            OpenStaxBookCatalogue.ID -> OpenStaxBookCatalogue.isEdition(address) && address.encodedPath == url.encodedPath
                            GlobalGreyBookCatalogue.ID -> GlobalGreyBookCatalogue.isEdition(address, format) && address.encodedPath == url.encodedPath
                            else -> sameOrigin
                        }
                        require(allowedRedirect && address.username.isEmpty() && address.password.isEmpty() && address.fragment == null) {
                            "The download redirected outside its authorized source."
                        }
                        if (publication.sourceId == StandardEbooksCatalogue.ID) require(StandardEbooksCatalogue.isEditionUrl(address)) {
                            "The download redirected away from the advertised edition."
                        }
                        return@awaitReading address
                    }
                    check(response.isSuccessful) { "The source did not provide a downloadable file (HTTP ${response.code})." }
                    val type = response.body.contentType()?.toString()?.substringBefore(';')?.lowercase()
                    require(type == link.mediaType.substringBefore(';').trim().lowercase() || type == "application/octet-stream") {
                        "The source returned a different file type or a sign-in page."
                    }
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
                importOwned("${publication.title}.$format", { download.inputStream() }, publication, source?.let { it.id to link.url })
            } finally { download.delete() }
        }
    }

    private suspend fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) {
            currentCoroutineContext().ensureActive(); val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count)
        } }
        return digest.digest().joinToString("") { "%02x".format(it) }
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
    private fun note(value: String) = value.trim().also { require(it.length <= 10_000) { "Keep a note under 10,000 characters." } }
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

/** A book taken out of the library by [ReadingRepository.takeOutBook], with what belonged to it, while its Undo runs. */
class TakenBook internal constructor(
    val book: ReadingBook,
    internal val progress: ReadingProgress?,
    internal val bookmarks: List<ReadingBookmark>,
    internal val finished: Boolean,
    internal val collections: Set<String>,
    internal val highlights: List<ReadingHighlight> = emptyList(),
)
