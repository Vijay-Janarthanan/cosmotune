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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
        if (template.type == "json") {
            val root = json(url, rule.method, values)
            val items = root.at(rule.itemSelector) as? JsonArray ?: error("This template found no series list at ${rule.itemSelector}")
            val books = items.take(1_000).mapNotNull { item ->
                val link = item.at(rule.linkSelector).text()?.take(4_096) ?: return@mapNotNull null
                val title = item.at(rule.titleSelector).text()?.trim()?.take(500)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                MangaBook(identity("book", link), config.id, title, link, item.at(rule.coverSelector).text()?.let { imageAddress(it, url) }, language = config.language)
            }.distinctBy { it.id }
            return@withContext MangaBrowsePage(books, if (books.isNotEmpty() && rule.pagination != "none") offset + rule.pageSize else null)
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
        if (template.type == "json") return@withContext jsonDetails(book)
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
        if (template.type == "json") {
            val url = site(expand(template.pageList.endpoint.ifBlank { "{link}" }, chapter.url))
            val images = json(url).at(template.pageList.imageSelector) as? JsonArray ?: error("This template found no page list at ${template.pageList.imageSelector}")
            val pages = images.take(10_000).mapNotNull { item ->
                (if (template.pageList.imageField.isBlank()) item else item.at(template.pageList.imageField)).text()?.let { imageAddress(it, url) }
            }.distinct()
            require(pages.isNotEmpty()) { "This template found no pages. Check its image path or the site's verification." }
            return@withContext pages.map { image -> MangaPage(identity("page", image), image, headers = mapOf("Referer" to url.toString())) }
        }
        val url = site(chapter.url)
        val doc = document(url)
        val pages = doc.select(template.pageList.imageSelector).take(10_000).mapNotNull { node -> image(node, url) }.distinct()
        require(pages.isNotEmpty()) { "This template found no pages. Check its image selector or the site's verification." }
        pages.map { image -> MangaPage(identity("page", image), image, headers = mapOf("Referer" to url.toString())) }
    }

    private suspend fun jsonDetails(book: MangaBook): MangaBook {
        val rule = template.mangaDetail
        val url = site(expand(rule.endpoint.ifBlank { "{link}" }, book.url))
        val root = json(url)
        val chaptersRule = template.chapterList
        val chapterRoot = if (chaptersRule.endpoint.isBlank()) root
            else json(site(expand(chaptersRule.endpoint, book.url).replace("{bookUrl}", url.toString().trimEnd('/')), url), chaptersRule.method, chaptersRule.parameters)
        val items = chapterRoot.at(chaptersRule.selector) as? JsonArray ?: error("This template found no chapter list at ${chaptersRule.selector}")
        val chapters = items.take(10_000).mapNotNull { item ->
            val link = item.at(chaptersRule.linkSelector).text()?.take(4_096) ?: return@mapNotNull null
            val title = item.at(chaptersRule.titleSelector).text()?.trim()?.take(500).orEmpty()
            val number = item.at(chaptersRule.numberSelector).text()?.toFloatOrNull() ?: Regex("\\d+(?:\\.\\d+)?").find(title)?.value?.toFloatOrNull() ?: 0f
            MangaChapter(identity("chapter", link), book.id, config.id, title.ifBlank { "Chapter ${number.toString().removeSuffix(".0")}" }, link, number, config.language)
        }.distinctBy { it.id }.let { if (chaptersRule.newestFirst) it.reversed() else it }
        require(chapters.isNotEmpty()) { "This template found no chapters. Check its paths or complete the site's verification." }
        return book.copy(title = root.at(rule.titleSelector).text()?.trim()?.take(500)?.takeIf(String::isNotBlank) ?: book.title,
            coverUrl = root.at(rule.coverSelector).text()?.let { imageAddress(it, url) } ?: book.coverUrl,
            description = root.at(rule.descriptionSelector).text().orEmpty().take(100_000),
            authors = root.at(rule.authorSelector).texts(), tags = root.at(rule.genreSelector).texts(), chapters = chapters)
    }

    /** `{link}` is a series or chapter link from the API: an address or path is used as is, a bare identifier as one path segment. */
    private fun expand(endpoint: String, link: String): String = endpoint.replace("{link}",
        if (link.contains('/') || link.contains(':')) link else java.net.URLEncoder.encode(link, Charsets.UTF_8).replace("+", "%20"))
    private suspend fun json(address: HttpUrl, method: String = "GET", parameters: Map<String, String> = emptyMap()): JsonElement {
        val (text, _) = fetch(address, method, parameters)
        return try { JSON.parseToJsonElement(text) } catch (failure: kotlinx.serialization.SerializationException) {
            throw IllegalStateException("The source did not return JSON. Check the template's endpoints or complete the site's verification.", failure)
        }
    }
    private fun JsonElement?.at(path: String): JsonElement? {
        if (this == null || path.isBlank()) return if (path.isBlank()) null else this
        if (path == "$") return this
        return path.split('.').fold(this as JsonElement?) { node, name -> when (node) {
            is JsonObject -> node[name]
            is JsonArray -> name.toIntOrNull()?.let { node.getOrNull(it) }
            else -> null
        } }
    }
    private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
    /** A string, or a list of strings or of objects with a name or title, as the series' authors or genres. */
    private fun JsonElement?.texts(): List<String> = when (this) {
        is JsonArray -> take(100).mapNotNull { it.text() ?: (it as? JsonObject)?.let { item -> item["name"].text() ?: item["title"].text() } }
        else -> listOfNotNull(text())
    }.map { it.trim().take(500) }.filter(String::isNotBlank).distinct()
    private fun imageAddress(reference: String, base: HttpUrl): String? {
        val url = base.resolve(reference.trim()) ?: return null
        return url.takeIf { it.username.isEmpty() && it.password.isEmpty() && (it.isHttps || local && sameOrigin(it)) }?.toString()
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
        return imageAddress(ref, base)
    }
    private fun identity(kind: String, address: String) = "${config.id}:$kind:" + MessageDigest.getInstance("SHA-256").digest(address.toByteArray(Charsets.UTF_8)).take(16).joinToString("") { "%02x".format(it) }
    private suspend fun document(address: HttpUrl, method: String = "GET", parameters: Map<String, String> = emptyMap()): Document =
        fetch(address, method, parameters).let { (html, url) -> Jsoup.parse(html, url.toString()) }
    /** The body of a site-scoped request after same-site redirects, with the address it finally came from. */
    private suspend fun fetch(address: HttpUrl, method: String = "GET", parameters: Map<String, String> = emptyMap()): Pair<String, HttpUrl> {
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
            } else return response.html to target
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
        private val JSON = Json { ignoreUnknownKeys = true }
        private fun permittedAddress(address: InetAddress, local: Boolean): Boolean {
            if (local) return !address.isMulticastAddress && !address.isAnyLocalAddress
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return false
            val bytes = address.address; val first = bytes[0].toInt() and 255
            return if (bytes.size == 4) first != 0 && first < 224 && !(first == 100 && (bytes[1].toInt() and 255) in 64..127) else first and 254 != 252
        }
    }
}
