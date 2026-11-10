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

    @Test fun openStaxAndGlobalGreyOfferReadablePublisherFiles() = runBlocking<Unit> {
        assumeTrue(System.getenv("COSMOTUNE_READING_LIVE") == "1" || System.getProperty("cosmotune.reading.live") == "true")
        val repository = ReadingRepository(temporary.newFolder())
        val textbook = repository.textbooks("additive manufacturing", 0).publications
            .first { it.title == "Additive Manufacturing Essentials" }
        val pdf = repository.download(textbook, textbook.links.first { it.downloadFormat == ReadingFormat.PDF }) { bytes, _ ->
            check(bytes <= 32 * 1024 * 1024)
        }
        assertIs<ReadingDocument.Pdf>(repository.openDocument(pdf.id))

        val recent = repository.globalGreyBooks().publications
        val moonstone = recent.firstOrNull { it.title == "The Moonstone" } ?: recent.first()
        val edition = repository.details(moonstone)
        val book = repository.download(edition, edition.links.first { it.downloadFormat == ReadingFormat.EPUB }) { bytes, _ ->
            check(bytes <= 8 * 1024 * 1024)
        }
        assertTrue(assertIs<ReadingDocument.Epub>(repository.openDocument(book.id)).spine.isNotEmpty())
    }

    @Test fun newPublicCataloguesFindAndOpenActualBooks() = runBlocking<Unit> {
        assumeTrue(System.getenv("COSMOTUNE_READING_LIVE") == "1" || System.getProperty("cosmotune.reading.live") == "true")
        val repository = ReadingRepository(temporary.newFolder())
        val archive = repository.archiveBooks("pride and prejudice", 0)
        val austen = archive.publications.first { it.title.contains("Pride and Prejudice", true) }
        val editions = repository.details(austen)
        assertTrue(editions.links.any { it.downloadFormat != null })

        val kafka = repository.spanishBooks(null, "metamorfosis").publications.first { book ->
            book.title.contains("metamorfosis", true) && book.authors.any { it.contains("Kafka", true) } &&
                book.links.any { it.downloadFormat == ReadingFormat.EPUB }
        }
        val epub = repository.download(kafka, kafka.links.first { it.downloadFormat == ReadingFormat.EPUB }) { received, _ ->
            check(received <= 8 * 1024 * 1024) { "Live probe only allows small public fixtures." }
        }
        assertTrue(assertIs<ReadingDocument.Epub>(repository.openDocument(epub.id)).spine.isNotEmpty())

        val tamil = repository.tamilBooks("ஆத்திசூடி", 0).publications.first { it.title == "ஆத்திசூடி" }
        val pdf = repository.download(tamil, tamil.links.first { it.downloadFormat == ReadingFormat.PDF }) { received, _ ->
            check(received <= 8 * 1024 * 1024) { "Live probe only allows small public fixtures." }
        }
        assertIs<ReadingDocument.Pdf>(repository.openDocument(pdf.id))
    }

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
