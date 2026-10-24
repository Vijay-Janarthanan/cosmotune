package com.cosmotune.manga.data

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request

class MangaRepository(val store: MangaStore,
    /** Offline tests only: a parser in place of the pinned catalogue's. */
    internal val kotatsuParser: ((MangaSourceConfig, ParserContext) -> org.koitharu.kotatsu.parsers.MangaParser)? = null,
    private val platformSource: ((MangaSourceConfig, ParserContext) -> MangaSource)? = null) {
    val context = ParserContext(store.root, store.protection) { store.state.value.sources }
    private val sources = LinkedHashMap<String, Pair<MangaSourceConfig, MangaSource>>()
    private val transfers = Mutex()
    // Guarded by transfers, including the instant a fetched file becomes visible to cache eviction.
    private val inFlightPins = HashMap<String, MutableSet<String>>()
    private val pageCache = File(store.root, "cache/network").apply { mkdirs() }
    private val changes = MutableStateFlow(0L)
    val sourceChanges = changes.asStateFlow()

    @Synchronized fun invalidateSource(id: String) { sources.remove(id); changes.value++ }

    @Synchronized fun source(id: String): MangaSource {
        val config = store.state.value.sources.find { it.id == id && it.enabled } ?: error("Enable this source in Manga sources")
        sources[id]?.takeIf { it.first == config }?.let { return it.second }
        val source = sourceApi { when (config.kind) {
            "kotatsu" -> KotatsuSource(config, context, kotatsuParser?.invoke(config, context))
            "komga" -> KomgaSource(config, context.httpClient)
            "template" -> TemplateMangaSource(config, context.httpClient)
            "mihon" -> platformSource?.invoke(config, context) ?: error("APK extension sources require the Android app")
            else -> error("Unknown manga source type: ${config.kind}")
        } }
        sources[id] = config to source
        while (sources.size > 8) sources.remove(sources.keys.first())
        return source
    }

    suspend fun chapters(book: MangaBook): MangaBook = withContext(Dispatchers.IO) {
        val detail = sourceApi { if (book.sourceId == "local") book else source(book.sourceId).details(book) }
        if (store.state.value.books.any { it.id == book.id }) store.save(detail)
        detail
    }

    suspend fun pages(chapter: MangaChapter): List<MangaPage> = withContext(Dispatchers.IO) {
        store.state.value.downloads[chapter.id]?.takeIf { pages -> pages.isNotEmpty() && pages.all { it.localFile?.let(::File)?.isFile == true } }
            ?.let { return@withContext it }
        sourceApi { if (chapter.sourceId == "local") store.archives.chapterPages(chapter) else source(chapter.sourceId).pages(chapter) }
    }

    suspend fun browse(sourceId: String, query: String, offset: Int): MangaBrowsePage = withContext(Dispatchers.IO) {
        sourceApi { source(sourceId).browse(query, offset) }
    }

    /** The catalogue languages of a multi-language source; empty when the source has one language. */
    suspend fun languages(sourceId: String): List<String> = withContext(Dispatchers.IO) { sourceApi { source(sourceId).languages() } }

    /** Chooses a multi-language source's catalogue language ("" for its default); the catalogue reloads with the new configuration. */
    suspend fun chooseLanguage(sourceId: String, language: String) {
        require(language.length <= 35)
        val config = store.state.value.sources.find { it.id == sourceId } ?: error("Enable this source in Manga sources")
        store.source(config.copy(settings = if (language.isBlank()) config.settings - MANGA_LANGUAGE_SETTING else config.settings + (MANGA_LANGUAGE_SETTING to language)))
    }

    suspend fun pageFile(page: MangaPage, sourceId: String): File = pageFile(page, sourceId, null)

    private suspend fun pageFile(page: MangaPage, sourceId: String, pinChapter: String?): File {
        page.localFile?.let { path ->
            val file = File(path).canonicalFile
            require(file.parentFile == pageCache.canonicalFile && file.isFile) { "Downloaded page is missing" }
            file.setLastModified(System.currentTimeMillis())
            return file
        }
        if (page.archiveFile != null) return transfers.withLock { withContext(Dispatchers.IO) {
            val file = store.archives.pageFile(page)
            trimCache(0, setOf(file.path))
            file
        } }
        return transfers.withLock { withContext(Dispatchers.IO) {
            val url = page.url ?: error("Page address is missing")
            val hash = MessageDigest.getInstance("SHA-256").digest((sourceId + "\n" + page.id).toByteArray())
                .joinToString("") { "%02x".format(it) }
            val output = File(pageCache, "$hash.image")
            if (output.isFile && output.length() > 0) {
                output.setLastModified(System.currentTimeMillis())
                pinChapter?.let { inFlightPins.getValue(it).add(output.path) }
                return@withContext output
            }
            val adapter = source(sourceId)
            val sourceRequest = (if (adapter is MangaImageTransport) adapter.imageRequest(page) else {
                require(page.requestMethod == "GET") { "Reload this chapter to refresh its image request" }
                Request.Builder().url(url).apply { page.headers.forEach { (key, value) -> header(key, value) } }.build()
            })
            val request = sourceRequest.newBuilder().apply {
                if (sourceRequest.header("User-Agent") == null) header("User-Agent", ParserContext.USER_AGENT)
            }.header("Accept", "image/*").build()
            val client = when (adapter) {
                is KotatsuSource -> context.httpClient.newBuilder().addInterceptor(adapter.parser).build()
                is MangaImageTransport -> adapter.imageClient.newBuilder().addInterceptor(MangaResponseLimit()).build()
                else -> context.httpClient.newBuilder().followRedirects(false).followSslRedirects(false).build()
            }
            val temporary = File.createTempFile("download-", ".part", pageCache)
            try {
                MangaImageFetch.fetch(client, request, temporary)
                trimCache(temporary.length(), setOf(output.path))
                MangaArchive.move(temporary, output)
                pinChapter?.let { inFlightPins.getValue(it).add(output.path) }
                output
            } finally { temporary.delete() }
        } }
    }

    /**
     * Checks every library series on an enabled network source for new chapters, [UPDATE_PARALLELISM] at a time and each within
     * [UPDATE_TIMEOUT_MS]. A failing source fails only its own series; the report goes to [onProgress] after each series.
     */
    suspend fun checkForUpdates(onProgress: (MangaUpdateReport) -> Unit = {}): MangaUpdateReport = coroutineScope {
        val state = store.state.value
        val enabled = state.sources.filter { it.enabled }.map { it.id }.toSet()
        val books = state.books.filter { it.sourceId != "local" && it.sourceId in enabled }
        val gate = Semaphore(UPDATE_PARALLELISM)
        val lock = Mutex()
        var report = MangaUpdateReport(0, books.size, 0, 0, emptyMap())
        books.map { book -> async {
            gate.withPermit {
                val outcome: Result<Int> = try {
                    Result.success(withTimeout(UPDATE_TIMEOUT_MS) {
                        val detail = withContext(Dispatchers.IO) { sourceApi { source(book.sourceId).details(book) } }
                        store.refreshed(detail)
                    })
                } catch (timeout: TimeoutCancellationException) { Result.failure(IllegalStateException("The source did not answer in time", timeout)) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { Result.failure(failure) }
                lock.withLock {
                    val found = outcome.getOrDefault(0)
                    report = report.copy(checked = report.checked + 1, newChapters = report.newChapters + found,
                        updatedSeries = report.updatedSeries + if (found > 0) 1 else 0,
                        failures = outcome.exceptionOrNull()?.let { report.failures + (book.id to (it.message ?: "Source unavailable")) } ?: report.failures)
                    onProgress(report)
                }
            }
        } }.awaitAll()
        report
    }

    /** Files already fetched have stable names, so retrying a cancelled download resumes without refetching them. */
    suspend fun download(chapter: MangaChapter, onProgress: (Int, Int) -> Unit) {
        require(chapter.sourceId != "local") { "Imported comics are already available offline" }
        val pages = pages(chapter)
        require(pages.isNotEmpty()) { "This chapter contains no pages" }
        val downloaded = ArrayList<MangaPage>()
        transfers.withLock {
            require(chapter.id !in inFlightPins) { "This chapter is already downloading" }
            inFlightPins[chapter.id] = HashSet()
        }
        try {
            pages.forEachIndexed { index, page ->
                val file = pageFile(page, chapter.sourceId, chapter.id)
                downloaded += page.copy(localFile = file.path, headers = emptyMap(), url = null, requestMethod = "GET")
                onProgress(index + 1, pages.size)
            }
            store.update { it.copy(downloads = it.downloads - "pending:${chapter.id}" + (chapter.id to downloaded.toList())) }
        } finally {
            withContext(NonCancellable) { transfers.withLock { inFlightPins.remove(chapter.id) } }
        }
    }

    suspend fun removeDownload(chapterId: String) {
        store.update { it.copy(downloads = it.downloads - chapterId) }
        transfers.withLock { withContext(Dispatchers.IO) { trimCache(0, emptySet()) } }
    }

    suspend fun removeBook(bookId: String) {
        val old = store.state.value.books.find { it.id == bookId } ?: return
        store.remove(bookId)
        withContext(Dispatchers.IO) {
            val owned = File(store.root, "books").canonicalFile
            val retained = store.state.value.books.flatMap { it.chapters }.mapNotNull { it.archiveFile }.toSet()
            old.chapters.mapNotNull { it.archiveFile }.distinct().filter { it !in retained }.forEach { path ->
                val file = File(path).canonicalFile
                if (file.parentFile == owned && file.name.matches(Regex("[0-9a-f]{64}\\.cbz"))) file.delete()
            }
        }
    }

    suspend fun clearCache() = transfers.withLock { withContext(Dispatchers.IO) {
        val protected = store.state.value.downloads.values.flatten().mapNotNull { it.localFile }.toSet() + inFlightPins.values.flatten()
        pageCache.listFiles().orEmpty().filter { it.isFile && it.path !in protected }.forEach { it.delete() }
        val archivePages = File(store.root, "cache/pages")
        archivePages.listFiles().orEmpty().filter { it.isFile }.forEach { it.delete() }
    } }

    private fun trimCache(incoming: Long, keep: Set<String>) {
        val protected = store.state.value.downloads.values.flatten().mapNotNull { it.localFile }.toSet() + keep + inFlightPins.values.flatten()
        val files = listOf(pageCache, File(store.root, "cache/pages")).flatMap { it.listFiles().orEmpty().toList() }
            .filter { it.isFile && it.extension != "part" }.sortedBy { it.lastModified() }
        var used = files.sumOf { it.length() }
        val limit = store.state.value.cacheLimitBytes.coerceIn(64L * 1024 * 1024, 4L * 1024 * 1024 * 1024)
        for (file in files) {
            if (used + incoming <= limit) return
            if (file.path !in protected) { val length = file.length(); if (file.delete()) used -= length }
        }
        require(used + incoming <= limit) { "Manga storage limit reached. Remove a downloaded chapter or increase the limit." }
    }

    companion object {
        const val UPDATE_PARALLELISM = 3
        const val UPDATE_TIMEOUT_MS = 60_000L
    }
}
