package com.cosmotune.manga.ui

import com.cosmotune.manga.data.MangaCbz
import com.cosmotune.manga.data.MangaOpdsFeed
import com.cosmotune.manga.data.MangaPageImages
import com.cosmotune.manga.data.MangaRepository
import com.cosmotune.manga.data.listedChapters
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.URLDecoder
import java.time.Instant
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The computer shares its Manga library as an OPDS catalogue on the home network (Suwayomi's OPDS feed) while Settings › Manga
 * has it on: other readers open `http://<this computer>:4568/opds/<key>` and fetch downloaded chapters as CBZ. The random key in the
 * path keeps the rest of the network out; only downloaded chapters are offered.
 */
internal object MangaOpdsServer {
    const val PORT = 4568
    @Volatile private var server: HttpServer? = null
    @Volatile private var watching = false

    /** Starts or stops the server with the setting, for as long as the app runs. */
    @Synchronized fun follow(repository: MangaRepository) {
        if (watching) return
        watching = true
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            repository.store.state.map { it.opdsKey.takeIf { _ -> it.opdsServer } }.distinctUntilChanged().collect { key ->
                stop()
                if (key != null) runCatching { start(repository, key) }
            }
        }
    }

    /** The address to type into another reader, on this computer's home-network address. */
    fun address(key: String): String {
        val host = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.inetAddresses.toList() }.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
        }.getOrNull() ?: "localhost"
        return "http://$host:$PORT/opds/$key"
    }

    val running: Boolean get() = server != null

    @Synchronized private fun stop() { server?.stop(0); server = null }

    @Synchronized private fun start(repository: MangaRepository, key: String) {
        val http = HttpServer.create(InetSocketAddress(PORT), 0)
        http.executor = Executors.newFixedThreadPool(2) { task -> Thread(task, "manga-opds").apply { isDaemon = true } }
        http.createContext("/opds/$key") { exchange -> runCatching { answer(repository, key, exchange) }.onFailure { runCatching { send(exchange, 500, "text/plain", "Error".toByteArray()) } } }
        http.start()
        server = http
    }

    private fun answer(repository: MangaRepository, key: String, exchange: HttpExchange) {
        val state = repository.store.state.value
        val base = "http://" + (exchange.requestHeaders.getFirst("Host") ?: "localhost:$PORT") + "/opds/$key"
        val rest = exchange.requestURI.rawPath.removePrefix("/opds/$key").trim('/')
        val now = Instant.now().toString()
        val atom = "application/atom+xml;charset=utf-8"
        fun id(prefix: String) = URLDecoder.decode(rest.removePrefix(prefix), "UTF-8")
        when {
            rest.isEmpty() -> send(exchange, 200, atom, MangaOpdsFeed.root(state.books, state, base, now).toByteArray())
            rest.startsWith("series/") -> state.books.find { it.id == id("series/") }
                ?.let { send(exchange, 200, atom, MangaOpdsFeed.series(it, state, base, now).toByteArray()) } ?: send(exchange, 404, "text/plain", "Not found".toByteArray())
            rest.startsWith("cover/") -> {
                // The first page of the first downloaded chapter stands in for the cover: the feed never reaches out to a source.
                val book = state.books.find { it.id == id("cover/") }
                val page = book?.listedChapters()?.firstNotNullOfOrNull { state.downloads[it.id] }?.firstOrNull()?.localFile?.let(::File)?.takeIf { it.isFile }
                if (page == null) send(exchange, 404, "text/plain", "Not found".toByteArray())
                else send(exchange, 200, "image/" + MangaCbz.extensionOf(page).replace("jpg", "jpeg"), page.readBytes())
            }
            rest.startsWith("chapter/") && rest.endsWith(".cbz") -> {
                val chapterId = URLDecoder.decode(rest.removePrefix("chapter/").removeSuffix(".cbz"), "UTF-8")
                val pages = state.downloads[chapterId].orEmpty().mapNotNull { page -> page.localFile?.let(::File)?.takeIf { it.isFile } }
                if (pages.isEmpty()) { send(exchange, 404, "text/plain", "Not found".toByteArray()); return }
                val archive = File.createTempFile("opds-", ".cbz")
                try {
                    archive.delete()
                    MangaCbz.write(archive, pages, split = false, sizeOf = MangaPageImages::size, slice = MangaPageImages::slice)
                    exchange.responseHeaders.add("Content-Type", "application/vnd.comicbook+zip")
                    exchange.sendResponseHeaders(200, archive.length())
                    exchange.responseBody.use { out -> archive.inputStream().use { it.copyTo(out) } }
                } finally { archive.delete() }
            }
            else -> send(exchange, 404, "text/plain", "Not found".toByteArray())
        }
    }

    private fun send(exchange: HttpExchange, code: Int, type: String, body: ByteArray) {
        exchange.responseHeaders.add("Content-Type", type)
        exchange.sendResponseHeaders(code, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }
}
