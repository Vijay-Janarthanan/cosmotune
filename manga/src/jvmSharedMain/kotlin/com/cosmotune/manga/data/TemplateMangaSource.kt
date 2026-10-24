package com.cosmotune.manga.data

import java.net.InetAddress
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Site-scoped HTML requests and declarative extraction, independent of Android APK extensions. */
class TemplateMangaSource(override val config: MangaSourceConfig, baseClient: OkHttpClient) : MangaSource, MangaImageTransport {
    private val template = MangaHtmlTemplate.parse(requireNotNull(config.settings["template"]))
    private val origin = config.baseUrl.toHttpUrl()
    private val local = config.settings["allowLocalNetwork"] == "true"
    override val imageClient = baseClient.newBuilder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true).followSslRedirects(false)
        .dns { host -> baseClient.dns.lookup(host).also { addresses ->
            require(addresses.isNotEmpty() && addresses.all { permittedAddress(it, local && host == origin.host) }) { "This source resolved to a private or unavailable address" }
            if (!origin.isHttps && host == origin.host) require(addresses.all { it.isLoopbackAddress || it.isLinkLocalAddress || it.isSiteLocalAddress || it.address.size == 16 && (it.address[0].toInt() and 254) == 252 }) { "Cleartext is limited to the explicitly configured local server" }
        } }.addNetworkInterceptor { chain ->
            val url = chain.request().url
            require(url.username.isEmpty() && url.password.isEmpty() && (url.isHttps || local && sameOrigin(url))) { "Source requires HTTPS" }
            chain.proceed(chain.request())
        }.build()
    private val client = imageClient.newBuilder().followRedirects(false).followSslRedirects(false).build()
    init { require(config.kind == "template" && origin.username.isEmpty() && origin.password.isEmpty() && (origin.isHttps || local)) }

    override suspend fun browse(query: String, offset: Int): MangaBrowsePage = withContext(Dispatchers.IO) {
        require(offset in 0..1_000_000 && query.length <= 500)
        val rule = template.mangaList
        val page = offset / rule.pageSize + 1
        var url = site(if (query.isBlank()) rule.endpoint else rule.searchEndpoint)
        val values = rule.parameters.toMutableMap()
        if (query.isNotBlank()) { values.putAll(rule.searchParams); values[rule.searchParam] = query }
        if (rule.pagination != "none" && (page > 1 || rule.method == "POST")) when (rule.pagination) {
            "path", "wordpress" -> url = site(url.toString().trimEnd('/') + "/page/$page/")
            "offset" -> values[rule.pageParam] = offset.toString()
            else -> values[rule.pageParam] = page.toString()
        }
        val doc = document(url, rule.method, values)
        val books = doc.select(rule.itemSelector).take(1_000).mapNotNull { item ->
            val link = element(item, rule.linkSelector) ?: return@mapNotNull null
            val target = link.attr("href").takeIf(String::isNotBlank)?.let { site(it, url) } ?: return@mapNotNull null
            val title = element(item, rule.titleSelector)?.text()?.trim().orEmpty().ifBlank { link.attr("title").trim() }.take(500)
            if (title.isBlank()) return@mapNotNull null
            MangaBook(identity("book", target.toString()), config.id, title, target.toString(), image(element(item, rule.coverSelector), url), language = config.language)
        }.distinctBy { it.id }
        val next = if (books.isNotEmpty() && rule.pagination != "none") offset + rule.pageSize else null
        MangaBrowsePage(books, next)
    }

    override suspend fun details(book: MangaBook): MangaBook = withContext(Dispatchers.IO) {
        require(book.sourceId == config.id)
        val url = site(book.url)
        val doc = document(url)
        val rule = template.mangaDetail
        val chaptersRule = template.chapterList
        var chapterDoc = doc
        if (doc.select(chaptersRule.selector).isEmpty() && chaptersRule.endpoint.isNotBlank()) {
            val endpoint = chaptersRule.endpoint.replace("{bookUrl}", url.toString().trimEnd('/'))
            chapterDoc = document(site(endpoint, url), chaptersRule.method, chaptersRule.parameters)
        }
        val chapters = chapterDoc.select(chaptersRule.selector).take(10_000).mapNotNull { item ->
            val link = element(item, chaptersRule.linkSelector) ?: return@mapNotNull null
            val target = link.attr("href").takeIf(String::isNotBlank)?.let { site(it, url) } ?: return@mapNotNull null
            val title = element(item, chaptersRule.titleSelector)?.text()?.trim().orEmpty().ifBlank { link.text().trim() }.take(500)
            if (title.isBlank()) return@mapNotNull null
            val number = Regex("\\d+(?:\\.\\d+)?").find(title)?.value?.toFloatOrNull() ?: 0f
            MangaChapter(identity("chapter", target.toString()), book.id, config.id, title, target.toString(), number, config.language)
        }.distinctBy { it.id }.let { if (chaptersRule.newestFirst) it.reversed() else it }
        require(chapters.isNotEmpty()) { "This template found no chapters. Check its selectors or complete the site's verification." }
        book.copy(title = text(doc, rule.titleSelector).ifBlank { book.title }.take(500),
            coverUrl = image(element(doc, rule.coverSelector), url) ?: book.coverUrl,
            description = text(doc, rule.descriptionSelector).take(100_000),
            authors = texts(doc, rule.authorSelector), tags = texts(doc, rule.genreSelector), chapters = chapters)
    }

    override suspend fun pages(chapter: MangaChapter): List<MangaPage> = withContext(Dispatchers.IO) {
        require(chapter.sourceId == config.id)
        val url = site(chapter.url)
        val doc = document(url)
        val pages = doc.select(template.pageList.imageSelector).take(10_000).mapNotNull { node -> image(node, url) }.distinct()
        require(pages.isNotEmpty()) { "This template found no pages. Check its image selector or the site's verification." }
        pages.map { image -> MangaPage(identity("page", image), image, headers = mapOf("Referer" to url.toString())) }
    }

    private fun sameOrigin(url: HttpUrl) = url.scheme == origin.scheme && url.host == origin.host && url.port == origin.port
    private fun site(reference: String, base: HttpUrl = origin): HttpUrl {
        val url = requireNotNull(base.resolve(reference)) { "Invalid source address" }
        require(sameOrigin(url) && url.username.isEmpty() && url.password.isEmpty()) { "Template navigation must stay on this site" }
        return url.newBuilder().fragment(null).build()
    }
    private fun element(root: Element, selector: String): Element? = if (selector.isBlank()) null else if (root.`is`(selector)) root else root.selectFirst(selector)
    private fun text(root: Element, selector: String) = element(root, selector)?.text()?.trim().orEmpty()
    private fun texts(root: Element, selector: String): List<String> = if (selector.isBlank()) emptyList() else root.select(selector).take(100).map { it.text().trim().take(500) }.filter(String::isNotBlank).distinct()
    private fun image(node: Element?, base: HttpUrl): String? {
        if (node == null) return null
        val ref = listOf("data-src", "data-lazy-src", "data-original", "src").firstNotNullOfOrNull { node.attr(it).trim().takeIf(String::isNotBlank) } ?: return null
        val url = base.resolve(ref) ?: return null
        return url.takeIf { it.username.isEmpty() && it.password.isEmpty() && (it.isHttps || local && sameOrigin(it)) }?.toString()
    }
    private fun identity(kind: String, address: String) = "${config.id}:$kind:" + MessageDigest.getInstance("SHA-256").digest(address.toByteArray(Charsets.UTF_8)).take(16).joinToString("") { "%02x".format(it) }
    private suspend fun document(address: HttpUrl, method: String = "GET", parameters: Map<String, String> = emptyMap()): Document {
        var target = address
        var verb = method
        var fields = parameters
        repeat(6) { hop ->
            currentCoroutineContext().ensureActive()
            val builder = Request.Builder().url(target).header("User-Agent", ParserContext.USER_AGENT).header("Referer", origin.toString())
            if (verb == "POST") builder.post(FormBody.Builder().apply { fields.forEach { (key, value) -> add(key, value) } }.build())
            else builder.url(target.newBuilder().apply { fields.forEach { (key, value) -> setQueryParameter(key, value) } }.build())
            val response = body(builder.build())
            if (response.redirect != null) {
                require(hop < 5) { "Too many source redirects" }
                target = site(response.redirect, target)
                fields = emptyMap()
                if (response.status == 303 || verb == "POST" && response.status in setOf(301, 302)) verb = "GET"
            } else return Jsoup.parse(response.html, target.toString())
        }
        error("Source did not return a page")
    }
    private data class HtmlResponse(val html: String, val redirect: String? = null, val status: Int = 200)
    private suspend fun body(request: Request): HtmlResponse = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: java.io.IOException) { if (continuation.isActive) continuation.resumeWithException(error) }
            override fun onResponse(call: Call, response: Response) {
                try { response.use { reply ->
                    val value = if (reply.code in setOf(301, 302, 303, 307, 308)) HtmlResponse("", requireNotNull(reply.header("Location")), reply.code) else {
                        if (reply.code in setOf(403, 503)) throw MangaBrowserRequired(request.url.toString())
                        check(reply.isSuccessful) { "Source returned HTTP ${reply.code}" }
                        require(reply.body.contentLength() <= 2 * 1024 * 1024) { "Source page exceeds 2 MB" }
                        val output = java.io.ByteArrayOutputStream()
                        reply.body.byteStream().use { input ->
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                check(continuation.isActive) { "Source request cancelled" }
                                val count = input.read(buffer); if (count < 0) break
                                require(output.size() + count <= 2 * 1024 * 1024) { "Source page exceeds 2 MB" }
                                output.write(buffer, 0, count)
                            }
                        }
                        HtmlResponse(output.toByteArray().toString(Charsets.UTF_8))
                    }
                    if (continuation.isActive) continuation.resume(value)
                } } catch (failure: Exception) { if (continuation.isActive) continuation.resumeWithException(failure) }
            }
        })
    }
    companion object {
        private fun permittedAddress(address: InetAddress, local: Boolean): Boolean {
            if (local) return !address.isMulticastAddress && !address.isAnyLocalAddress
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return false
            val bytes = address.address; val first = bytes[0].toInt() and 255
            return if (bytes.size == 4) first != 0 && first < 224 && !(first == 100 && (bytes[1].toInt() and 255) in 64..127) else first and 254 != 252
        }
    }
}
