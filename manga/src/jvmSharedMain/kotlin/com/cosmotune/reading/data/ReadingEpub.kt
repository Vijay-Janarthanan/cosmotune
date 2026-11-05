package com.cosmotune.reading.data

import java.io.File
import java.net.URI
import java.util.zip.ZipFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

/** A validated, bounded EPUB package. Colour illustrations, fonts and CSS stay byte-for-byte intact. */
internal object ReadingEpub {
    data class Package(val title: String, val authors: List<String>, val language: String, val description: String,
        val root: File, val spine: List<ReadingEpubSection>, val navigation: List<ReadingNavigation>,
        val resources: Map<String, ReadingResource>, val cover: File?)

    suspend fun open(archive: File, destination: File): Package {
        val staging = File(destination.parentFile, destination.name + ".staging-" + java.util.UUID.randomUUID()).apply { mkdirs() }
        try {
            ZipFile(archive).use { zip ->
                val entries = zip.entries().asSequence().toList()
                require(entries.size in 1..10_000) { "EPUB contains too many resources." }
                val names = HashSet<String>()
                var total = 0L
                entries.forEach { entry ->
                    currentCoroutineContext().ensureActive()
                    val name = safe(entry.name)
                    require(names.add(name.lowercase(java.util.Locale.ROOT))) { "EPUB contains duplicate resource paths." }
                    val output = File(staging, name).canonicalFile
                    require(output.toPath().startsWith(staging.canonicalFile.toPath())) { "EPUB resource leaves its package." }
                    if (entry.isDirectory) output.mkdirs() else {
                        require(entry.size <= MAX_RESOURCE) { "EPUB resource exceeds the size limit." }
                        output.parentFile.mkdirs()
                        var resourceSize = 0L
                        zip.getInputStream(entry).use { input -> output.outputStream().use { target ->
                            val buffer = ByteArray(32 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                resourceSize += count; total += count
                                require(resourceSize <= MAX_RESOURCE && total <= MAX_EXPANDED) { "EPUB expands beyond the resource limit." }
                                target.write(buffer, 0, count)
                            }
                        } }
                    }
                }
            }
            require(File(staging, "mimetype").takeIf { it.isFile }?.readText()?.trim() == "application/epub+zip") { "This ZIP is not an EPUB package." }
            // Encrypted fonts/content need a dedicated entitlement engine; never silently render scrambled resources.
            require(!File(staging, "META-INF/encryption.xml").exists()) { "This EPUB contains protected resources; open it in its authorized source reader." }
            val container = xml(File(staging, "META-INF/container.xml"))
            val opfPath = container.getAllElements().firstOrNull { it.local() == "rootfile" }?.attr("full-path")
                ?.let(::safe) ?: error("EPUB package document is missing.")
            val opf = xml(File(staging, opfPath))
            val manifest = opf.getAllElements().firstOrNull { it.local() == "manifest" } ?: error("EPUB manifest is missing.")
            val items = manifest.children().filter { it.local() == "item" }
            require(items.size in 1..10_000)
            val resources = LinkedHashMap<String, ReadingResource>()
            val ids = LinkedHashMap<String, String>()
            items.forEach { item ->
                val path = resolve(opfPath, item.attr("href")).substringBefore('#')
                val file = File(staging, path)
                require(file.isFile) { "EPUB resource is missing: ${File(path).name}" }
                val type = item.attr("media-type")
                require(type !in setOf("application/javascript", "text/javascript")) { "Scripted EPUB documents are not supported." }
                require(ids.put(item.attr("id"), path) == null) { "EPUB contains duplicate manifest identities." }
                resources[path] = ReadingResource(path, file, type)
            }
            val metadata = opf.getAllElements().firstOrNull { it.local() == "metadata" }
            fun texts(name: String) = metadata?.children()?.filter { it.local() == name }?.map(Element::text).orEmpty()
            val title = texts("title").firstOrNull().orEmpty()
            val navigation = ArrayList<ReadingNavigation>()
            val navItem = items.firstOrNull { "nav" in it.attr("properties").split(' ') }
            if (navItem != null) {
                val navPath = ids.getValue(navItem.attr("id"))
                val nav = xml(File(staging, navPath)).getAllElements().firstOrNull { it.local() == "nav" &&
                    (it.attr("epub:type").split(' ').contains("toc") || it.attr("role") == "doc-toc") }
                fun parseList(list: Element): List<ReadingNavigation> = list.children().filter { it.local() == "li" }.mapNotNull { li ->
                    val anchor = li.children().firstOrNull { it.local() == "a" }
                    val nested = li.children().firstOrNull { it.local() == "ol" }?.let(::parseList).orEmpty()
                    if (anchor == null) nested.firstOrNull()?.copy(children = nested.drop(1)) else
                        ReadingNavigation(anchor.text(), resolve(navPath, anchor.attr("href")), nested)
                }
                nav?.children()?.firstOrNull { it.local() == "ol" }?.let { navigation += parseList(it) }
            }
            val spineNode = opf.getAllElements().firstOrNull { it.local() == "spine" } ?: error("EPUB reading order is missing.")
            if (navigation.isEmpty()) {
                val ncxPath = ids[spineNode.attr("toc")]
                if (ncxPath != null) {
                    fun points(parent: Element): List<ReadingNavigation> = parent.children().filter { it.local() == "navPoint" }.mapNotNull { point ->
                        val content = point.children().firstOrNull { it.local() == "content" } ?: return@mapNotNull null
                        ReadingNavigation(point.getAllElements().firstOrNull { it.local() == "text" }?.text().orEmpty(),
                            resolve(ncxPath, content.attr("src")), points(point))
                    }
                    xml(File(staging, ncxPath)).getAllElements().firstOrNull { it.local() == "navMap" }?.let { navigation += points(it) }
                }
            }
            val spine = spineNode.children().filter { it.local() == "itemref" && it.attr("linear") != "no" }.map { ref ->
                val path = ids[ref.attr("idref")] ?: error("EPUB reading order references a missing resource.")
                val resource = resources.getValue(path)
                require(resource.mediaType in setOf("application/xhtml+xml", "text/html", "image/svg+xml")) { "EPUB chapter format is unsupported." }
                ReadingEpubSection(path, resource.file, resource.mediaType, navigation.firstOrNull { it.href.substringBefore('#') == path }?.title.orEmpty())
            }
            require(spine.isNotEmpty()) { "EPUB contains no readable chapters." }
            val coverId = metadata?.children()?.firstOrNull { it.local() == "meta" && it.attr("name") == "cover" }?.attr("content")
            val coverPath = items.firstOrNull { "cover-image" in it.attr("properties").split(' ') }?.attr("id")?.let(ids::get)
                ?: coverId?.let(ids::get)
            if (destination.exists()) deleteOwned(destination)
            ReadingStore.move(staging, destination)
            val relocated = resources.mapValues { (_, resource) -> resource.copy(file = File(destination, resource.href)) }
            return Package(title, texts("creator"), texts("language").firstOrNull().orEmpty(), texts("description").firstOrNull().orEmpty(),
                destination, spine.map { it.copy(file = File(destination, it.href)) }, navigation, relocated, coverPath?.let { File(destination, it) })
        } finally { if (staging.exists()) deleteOwned(staging) }
    }

    private fun xml(file: File): Element {
        require(file.isFile && file.length() <= 4 * 1024 * 1024) { "EPUB metadata is missing or exceeds its limit." }
        val text = file.readText()
        // Jsoup's XML parser never loads external DTDs. Allow the standard XHTML/NCX doctype, reject entity declarations.
        require(!text.contains("<!ENTITY", true)) { "EPUB entity declarations are not supported." }
        return Jsoup.parse(text, "", Parser.xmlParser())
    }
    private fun Element.local() = tagName().substringAfter(':')
    private fun safe(value: String): String {
        require(value.isNotBlank() && value.length <= 1_024 && '\\' !in value && !value.startsWith('/') && !value.contains(':') && value.none(Char::isISOControl)) { "EPUB contains an unsafe resource path." }
        require(value.split('/').none { it == ".." || it == "." }) { "EPUB contains a traversal path." }
        require(value.trimEnd('/').split('/').none { part -> part.endsWith('.') || part.endsWith(' ') || part.any { it in "<>\"|?*" } ||
            part.substringBefore('.').uppercase(java.util.Locale.ROOT).matches(Regex("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) }) { "EPUB resource path is not portable." }
        return value.trimEnd('/')
    }
    internal fun resolve(base: String, href: String): String {
        val uri = URI(href.replace(" ", "%20"))
        require(!uri.isAbsolute && uri.authority == null && uri.query == null) { "EPUB resource must stay inside its package." }
        val resolved = URI(base).resolve(uri).normalize()
        val path = safe(resolved.path)
        return path + (resolved.fragment?.let { "#$it" } ?: "")
    }
    internal fun deleteOwned(directory: File) {
        // Callers pass only a verified SHA directory or the staging directory created beside it.
        directory.walkBottomUp().forEach { require(it.delete() || !it.exists()) { "Could not remove an owned EPUB resource." } }
    }
    private const val MAX_RESOURCE = 64L * 1024 * 1024
    private const val MAX_EXPANDED = 512L * 1024 * 1024
}
