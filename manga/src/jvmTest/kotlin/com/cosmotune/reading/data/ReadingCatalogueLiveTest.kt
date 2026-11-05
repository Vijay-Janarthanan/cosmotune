package com.cosmotune.reading.data

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

/** Opt-in network probe: metadata plus one small public-domain Gutenberg file, never a commercial book download. */
class ReadingCatalogueLiveTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun modernMetadataTrendingAndPublicDomainColourEpubOpenEndToEnd() = runBlocking<Unit> {
        assumeTrue(System.getenv("COSMOTUNE_READING_LIVE") == "1" || System.getProperty("cosmotune.reading.live") == "true")
        val repository = ReadingRepository(temporary.newFolder())
        println("Reading live: modern Open Library metadata")
        val modern = repository.search("Harry Potter")
        assertTrue(modern.publications.any { it.title.contains("Harry Potter", true) }, "Broad metadata includes commercial titles.")
        assertTrue(modern.publications.all { it.links.none { link -> link.kind == ReadingLinkKind.ACQUIRE } })
        println("Reading live: Open Library trending range query")
        assertTrue(repository.trending().publications.isNotEmpty(), "The provider's real trending sort must return actual metadata.")
        println("Reading live: official Gutenberg OPDS Peter Rabbit metadata")
        val classics = repository.gutenberg("The Tale of Peter Rabbit")
        val listed = classics.publications.first { it.title.contains("Peter Rabbit", true) && it.authors.any { author -> author.contains("Potter", true) } }
        println("Reading live: selected Gutenberg acquisition feed")
        val title = repository.details(listed)
        assertEquals("gutenberg", title.sourceId)
        val acquisition = title.links.first { it.kind == ReadingLinkKind.ACQUIRE && it.mediaType == "application/epub+zip" }
        println("Reading live: Gutenberg public-domain EPUB download")
        val imported = repository.download(title, acquisition) { received, _ -> check(received <= 8 * 1024 * 1024) { "Live probe only allows a small public-domain fixture." } }
        println("Reading live: validate and open downloaded EPUB")
        val document = assertIs<ReadingDocument.Epub>(repository.openDocument(imported.id))
        assertTrue(document.spine.isNotEmpty())
        assertTrue(document.resources.values.any { it.mediaType.startsWith("image/") }, "The illustrated source preserves image resources.")
        assertTrue(document.resources.values.all { it.file.isFile })
        assertTrue(document.spine.first().file.readText().isNotBlank())
    }
}
