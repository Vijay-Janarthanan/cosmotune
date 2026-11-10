package com.cosmotune.reading.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test
import kotlin.test.*

class PublicBookCataloguesTest {
    @Test fun openStaxRequiresAnOpenLicenseAndPublisherHostedPdf() {
        val entry = """{"id":873,"title":"Additive Manufacturing Essentials","meta":{"slug":"additive-manufacturing-essentials","locale":"en"},
            "license_url":"https://creativecommons.org/licenses/by-nc-sa/4.0/",
            "pdf_url":"https://assets.openstax.org/oscms-prodcms/media/documents/book.pdf"}"""
        fun parse(value: String) = OpenStaxBookCatalogue.publication(Json.parseToJsonElement(value).jsonObject)
        assertEquals(ReadingFormat.PDF, assertNotNull(parse(entry)).links.first().downloadFormat)
        assertNull(parse(entry.replace("creativecommons.org", "example.org")))
        assertNull(parse(entry.replace("assets.openstax.org", "assets.openstax.org.example.org")))
    }

    @Test fun globalGreyRequiresItsFullBookDownloadPath() {
        assertTrue(GlobalGreyBookCatalogue.isEdition("https://www.globalgreyebooks.com/ebooks-2/wilkie-collins_moonstone.epub".toHttpUrl(), "epub"))
        assertFalse(GlobalGreyBookCatalogue.isEdition("https://www.globalgreyebooks.com/ebooks/previews/sample.pdf".toHttpUrl(), "pdf"))
        assertFalse(GlobalGreyBookCatalogue.isEdition("https://example.org/ebooks-2/book.epub".toHttpUrl(), "epub"))
    }

    @Test fun archiveOffersOnlyPublicCompleteFilesFromHistoricalLibraryEditions() {
        val response = """
            {"metadata":{"identifier":"jane","date":"1893","collection":["americana"]},"files":[
                {"name":"Jane Austen.epub","format":"EPUB","size":"10000"},
                {"name":"jane.pdf","format":"Text PDF","size":"30000"},
                {"name":"jane-sample.pdf","format":"PDF","size":"5"},
                {"name":"private.pdf","format":"PDF","size":"5","private":"true"},
                {"name":"restricted.epub","format":"ACS Encrypted EPUB","size":"5"}
            ]}
        """.trimIndent()
        fun links(body: String) = ArchiveBookCatalogue.editions(Json.parseToJsonElement(body).jsonObject, "jane")
        val links = links(response)
        assertEquals(listOf(ReadingFormat.EPUB, ReadingFormat.PDF), links.map { it.downloadFormat })
        assertEquals("https://archive.org/download/jane/Jane%20Austen.epub", links.first().url)
        assertTrue(links(response.replace("1893", "2020")).isEmpty())
        assertTrue(links(response.replace("\"americana\"", "\"americana\",\"inlibrary\"")).isEmpty())
        assertTrue(links(response.replace("\"date\":", "\"access-restricted-item\":true,\"date\":")).isEmpty())
    }

    @Test fun archiveMirrorsMayServeOnlyTheSameAdvertisedFile() {
        val original = "https://archive.org/download/jane/jane.pdf".toHttpUrl()
        assertTrue(ArchiveBookCatalogue.isRedirect(original, "https://dn760104.eu.archive.org/0/items/jane/jane.pdf".toHttpUrl()))
        assertFalse(ArchiveBookCatalogue.isRedirect(original, "https://ia123.us.archive.org/0/items/different/jane.pdf".toHttpUrl()))
        assertFalse(ArchiveBookCatalogue.isRedirect(original, "https://ia123.us.archive.org.example.org/0/items/jane/jane.pdf".toHttpUrl()))
        assertFalse(ArchiveBookCatalogue.isRedirect(original, "https://ia123.us.archive.org/0/items/jane/other.pdf".toHttpUrl()))
    }

    @Test fun maduraiParsesTamilTitlesAndKeepsMultiplePublisherEditions() {
        val book = MaduraiBookCatalogue.parse("""
            <table id="sortabletable"><tr><td>1</td><td>திருக்குறள்</td><td>திருவள்ளுவர்</td><td>நீதிநெறி</td>
            <td><a href="/pm_etexts/pdf/pm0001.pdf">first</a><a href="/pm_etexts/pdf/pm0001_01.pdf">second</a>
                <a href="https://unrelated.example/pm_etexts/pdf/pm0001.pdf">external</a></td><td></td></tr></table>
        """.trimIndent()).single()
        assertEquals("திருக்குறள்", book.title)
        assertEquals(listOf("திருவள்ளுவர்"), book.authors)
        assertEquals(2, book.links.count { it.downloadFormat == ReadingFormat.PDF })
    }

    @Test fun textosAcquisitionsMustUseThePublishersExactFormatRoute() {
        val edition = "https://www.textos.info/franz-kafka/la-metamorfosis/epub".toHttpUrl()
        assertTrue(TextosBookCatalogue.isEdition(edition, "epub"))
        assertFalse(TextosBookCatalogue.isEdition(edition, "pdf"))
        assertFalse(TextosBookCatalogue.isEdition(edition.newBuilder().host("textos.info.example.org").build(), "epub"))
    }
}
