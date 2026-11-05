package com.cosmotune.reading.data

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

/**
 * Read-only OPDS 1.2/2.0 metadata for a server explicitly supplied by the user. No preset public credentials,
 * file acquisition, DRM handling, or reader integration. BookLore, Calibre-Web/CWA and Kavita expose this protocol.
 * Private server credentials stay in memory and are sent only to the configured origin; redirects are refused.
 */
class PersonalOpdsCatalogue(
    private val sourceId: String,
    endpoint: String,
    username: String? = null,
    password: String = "",
    client: OkHttpClient = OkHttpClient(),
    private val allowInsecureHttp: Boolean = false,
    private val navigationAsPublications: Boolean = false,
) {
    private val root = endpoint.toHttpUrl()
    private val authorization = username?.let { Credentials.basic(it, password, Charsets.UTF_8) }
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    init { require(sourceId.isNotBlank()); checked(root) }

    /** Follow only catalogue/navigation URLs returned for this configured origin, one page per request. */
    suspend fun browse(address: String? = null): ReadingCataloguePage = withContext(Dispatchers.IO) {
        val url = address?.toHttpUrl() ?: root
        val text = fetch(url)
        if (text.startsWith("{")) parseJson(text, url) else parseAtom(text, url)
    }

    /** Search only with the catalogue's own advertised URI template or OpenSearch descriptor. */
    suspend fun search(query: String): ReadingCataloguePage = withContext(Dispatchers.IO) {
        require(query.trim().length in 2..500) { "Enter at least two search characters." }
        val search = browse().search.firstOrNull() ?: error("This library does not advertise catalogue search.")
        var template = search.url
        if (search.mediaType.substringBefore(';') == "application/opensearchdescription+xml") {
            val descriptorUrl = template.toHttpUrl()
            val text = fetch(descriptorUrl)
            require(!text.contains("<!DOCTYPE", true) && !text.contains("<!ENTITY", true))
            val descriptor = Jsoup.parse(text, "", Parser.xmlParser())
            val advertised = descriptor.getAllElements().firstOrNull { it.local() == "Url" &&
                (it.attr("type").contains("atom+xml") || it.attr("type").contains("opds+json")) }
                ?: error("The library has no compatible catalogue search format.")
            template = descriptorUrl.resolve(advertised.attr("template").replace("{", "%7B").replace("}", "%7D"))
                ?.toString()?.replace("%7B", "{")?.replace("%7D", "}") ?: error("Invalid library search template.")
        }
        val term = java.net.URLEncoder.encode(query.trim(), Charsets.UTF_8.name()).replace("+", "%20")
        require(listOf("{searchTerms}", "{searchTerms?}", "{query}", "{?query}", "{&query}").any(template::contains)) {
            "This library uses an unsupported search template."
        }
        template = template.replace("{?query}", "?query=$term").replace("{&query}", "&query=$term")
            .replace("{searchTerms}", term).replace("{searchTerms?}", term).replace("{query}", term)
        for ((key, value) in mapOf("count" to "20", "startIndex" to "0", "startPage" to "1", "language" to "", "inputEncoding" to "UTF-8", "outputEncoding" to "UTF-8")) {
            template = template.replace("{$key}", value).replace("{$key?}", value)
        }
        require('{' !in template && '}' !in template) { "This library requires unsupported search parameters." }
        browse(template)
    }

    private suspend fun fetch(url: HttpUrl): String {
        checked(url)
        require(sameOrigin(url)) { "Open a different server as a separate catalogue." }
        val request = Request.Builder().url(url)
            .header("Accept", "application/opds+json, application/atom+xml;q=0.9")
            .header("User-Agent", "Cosmotune/ReadingCatalogue")
            .apply { authorization?.let { header("Authorization", it) } }.build()
        currentCoroutineContext().ensureActive()
        val text = http.newCall(request).awaitReading { response ->
            check(response.code !in setOf(401, 403)) { "The library requires valid credentials and catalogue access." }
            check(!response.isRedirect) { "Use the library's final catalogue address; redirects are not followed." }
            check(response.isSuccessful) { "The library returned HTTP ${response.code}." }
            val body = response.body
            require(body.contentLength() <= MAX_BYTES) { "The catalogue page exceeds the metadata limit." }
            val output = ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= MAX_BYTES) { "The catalogue page exceeds the metadata limit." }
                    output.write(buffer, 0, count)
                }
            }
            output.toString(Charsets.UTF_8.name()).trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        }
        currentCoroutineContext().ensureActive()
        return text
    }

    private fun checked(url: HttpUrl) {
        require(url.username.isEmpty() && url.password.isEmpty() && url.fragment == null) { "Use a catalogue URL without embedded credentials or a fragment." }
        require(url.isHttps || allowInsecureHttp) { "Use HTTPS for the personal library." }
    }
    private fun sameOrigin(url: HttpUrl) = url.scheme == root.scheme && url.host == root.host && url.port == root.port

    private fun link(base: HttpUrl, href: String, title: String, type: String, relation: String): ReadingLink? {
        if (href.isBlank()) return null
        // Search templates remain metadata until an explicit search expands a supported advertised variable.
        val templated = '{' in href || '}' in href
        val parsed = base.resolve(href.replace("{", "%7B").replace("}", "%7D")) ?: return null
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty() || (!parsed.isHttps && !(allowInsecureHttp && sameOrigin(parsed)))) return null
        val kind = when {
            relation == "search" -> ReadingLinkKind.SEARCH
            relation.endsWith("/sample") -> ReadingLinkKind.SAMPLE
            relation.endsWith("/borrow") -> ReadingLinkKind.BORROW
            relation.endsWith("/buy") -> ReadingLinkKind.BUY
            relation == "download" || relation == "http://opds-spec.org/acquisition" || relation.endsWith("/open-access") -> ReadingLinkKind.ACQUIRE
            type.contains("opds") || type.contains("atom+xml") -> ReadingLinkKind.BROWSE
            relation == "alternate" || type == "text/html" -> ReadingLinkKind.DETAILS
            else -> ReadingLinkKind.OTHER
        }
        val resolved = parsed.toString().let { if (templated) it.replace("%7B", "{").replace("%7D", "}") else it }
        return ReadingLink(title, resolved, type, relation, kind)
    }

    private fun parseAtom(text: String, base: HttpUrl): ReadingCataloguePage {
        require(!text.contains("<!DOCTYPE", true) && !text.contains("<!ENTITY", true)) { "Catalogue document declarations are not supported." }
        val feed = Jsoup.parse(text, base.toString(), Parser.xmlParser()).children().firstOrNull { it.local() == "feed" }
            ?: error("The library did not return an OPDS catalogue.")
        val entries = feed.children().filter { it.local() == "entry" }
        require(entries.size <= MAX_ITEMS) { "Use a smaller catalogue page size." }
        val navigation = ArrayList<ReadingLink>()
        val books = entries.mapNotNull { entry ->
            val links = entry.links(base)
            val acquisitions = links.filter { it.kind in setOf(ReadingLinkKind.ACQUIRE, ReadingLinkKind.BORROW, ReadingLinkKind.BUY, ReadingLinkKind.SAMPLE) }
            val authors = entry.children().filter { it.local() == "author" }.map { it.childText("name") }.filter(String::isNotBlank)
            if (!navigationAsPublications && acquisitions.isEmpty() && authors.isEmpty() && links.any { it.kind == ReadingLinkKind.BROWSE }) {
                navigation += links.filter { it.kind == ReadingLinkKind.BROWSE }; return@mapNotNull null
            }
            val id = entry.childText("id").ifBlank { links.firstOrNull()?.url ?: error("Publication identifier is missing.") }
            ReadingPublication(id, entry.childText("title"),
                authors = authors,
                languages = entry.children().filter { it.local() == "language" }.map(Element::text),
                description = entry.childText("summary").ifBlank { entry.childText("content") },
                identifiers = entry.children().filter { it.local() == "identifier" }.map(Element::text), links = links,
                coverUrl = links.firstOrNull { it.relation == "http://opds-spec.org/image" || it.relation.endsWith("/thumbnail") }?.url,
                accessEvidence = if (acquisitions.isEmpty()) "metadata-only" else "source-advertised-acquisition", sourceId = sourceId)
        }
        val links = feed.links(base)
        return ReadingCataloguePage(sourceId, feed.childText("title"), books, navigation,
            links.firstOrNull { it.relation == "next" }, links.filter { it.kind == ReadingLinkKind.SEARCH })
    }

    private fun Element.local() = tagName().substringAfter(':')
    private fun Element.childText(name: String) = children().firstOrNull { it.local() == name }?.text().orEmpty()
    private fun Element.links(base: HttpUrl) = children().filter { it.local() == "link" }.mapNotNull {
        link(base, it.attr("href"), it.attr("title").ifBlank { childText("title") }, it.attr("type"), it.attr("rel"))
    }

    private fun parseJson(text: String, base: HttpUrl): ReadingCataloguePage {
        val root = json.parseToJsonElement(text).jsonObject
        require(root["metadata"] is JsonObject && (root.containsKey("publications") || root.containsKey("navigation") || root.containsKey("groups"))) {
            "The library did not return an OPDS 2.0 catalogue."
        }
        val sections = listOf(root) + root.array("groups").map { it.jsonObject }
        require(sections.size <= 50) { "The catalogue contains too many groups." }
        val publications = sections.flatMap { it.array("publications") }
        val navigation = sections.flatMap { section -> section.array("navigation").mapNotNull { jsonLink(it.jsonObject, base) } }
        require(publications.size + navigation.size <= MAX_ITEMS) { "Use a smaller catalogue page size." }
        val books = publications.map { item ->
            val publication = item.jsonObject
            val meta = publication["metadata"]?.jsonObject ?: error("Publication metadata is missing.")
            val links = publication.array("links").mapNotNull { jsonLink(it.jsonObject, base) }
            fun strings(value: JsonElement?): List<String> = when (value) {
                is JsonArray -> value.flatMap(::strings)
                is JsonObject -> listOfNotNull(value.text("name").takeIf(String::isNotBlank))
                is JsonPrimitive -> listOfNotNull(value.contentOrNull)
                else -> emptyList()
            }
            val id = meta.text("identifier").ifBlank { links.firstOrNull()?.url ?: error("Publication identifier is missing.") }
            ReadingPublication(id, meta.text("title"), strings(meta["author"]), strings(meta["language"]), meta.text("description"),
                strings(meta["identifier"]), links,
                publication.array("images").firstOrNull()?.jsonObject?.text("href")?.let { link(base, it, "", "image/*", "")?.url },
                if (links.any { it.kind in setOf(ReadingLinkKind.ACQUIRE, ReadingLinkKind.BORROW, ReadingLinkKind.BUY, ReadingLinkKind.SAMPLE) })
                    "source-advertised-acquisition" else "metadata-only", sourceId = sourceId)
        }
        val links = root.array("links").mapNotNull { jsonLink(it.jsonObject, base) }
        return ReadingCataloguePage(sourceId, root["metadata"]!!.jsonObject.text("title"), books, navigation,
            links.firstOrNull { it.relation == "next" }, links.filter { it.kind == ReadingLinkKind.SEARCH })
    }

    private fun jsonLink(value: JsonObject, base: HttpUrl): ReadingLink? {
        val relations = (value["rel"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: listOf(value.text("rel"))
        val relation = relations.firstOrNull { it.startsWith("http://opds-spec.org/acquisition") || it == "download" }
            ?: relations.firstOrNull { it == "next" || it == "search" } ?: relations.firstOrNull().orEmpty()
        return link(base, value.text("href"), value.text("title"), value.text("type"), relation)
    }
    private fun JsonObject.text(name: String) = (this[name] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.array(name: String) = (this[name] as? JsonArray).orEmpty()

    companion object { private const val MAX_BYTES = 2 * 1024 * 1024; private const val MAX_ITEMS = 200 }
}
