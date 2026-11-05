package com.cosmotune.reading.ui

import com.cosmotune.reading.data.*
import java.awt.Color
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import kotlin.test.*
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import androidx.compose.ui.graphics.toPixelMap

class ReadingRendererTest {
    internal fun publication(root: File): ReadingDocument.Epub {
        val files = mapOf(
            "OPS/chapter.xhtml" to ("application/xhtml+xml" to """<html><head><link rel="stylesheet" href="style.css"/></head><body><h1 id="start">Illustrated public domain fixture</h1><img src="colour.svg"/><p>Once upon a time.</p></body></html>"""),
            "OPS/style.css" to ("text/css" to "@import 'more.css'; body { color: navy; } img { width: 80%; }"),
            "OPS/more.css" to ("text/css" to "p{font-family:serif}"),
            "OPS/colour.svg" to ("image/svg+xml" to """<svg xmlns="http://www.w3.org/2000/svg" width="300" height="120"><rect width="150" height="120" fill="red"/><rect x="150" width="150" height="120" fill="blue"/></svg>"""))
        val resources = files.mapValues { (href, value) ->
            ReadingResource(href, File(root, href).apply { parentFile.mkdirs(); writeText(value.second) }, value.first)
        }
        return ReadingDocument.Epub(ReadingBook("fixture", "Illustrated public domain fixture", format = ReadingFormat.EPUB,
            filePath = File(root, "fixture.epub").path), root,
            listOf(ReadingEpubSection("OPS/chapter.xhtml", resources.getValue("OPS/chapter.xhtml").file, "application/xhtml+xml")),
            listOf(ReadingNavigation("Beginning", "OPS/chapter.xhtml#start")), resources)
    }

    @Test fun publicationPreservesLayoutAndIllustrationsWithoutActiveContentOrRemoteRequests() {
        val root = Files.createTempDirectory("reading-render-").toFile()
        try {
            val doc = publication(root)
            val content = ReadingEpubContent(doc, "https://local.invalid/capability/")
            val html = content.resource("OPS/chapter.xhtml")!!.second.decodeToString()
            assertContains(html, "https://local.invalid/capability/OPS/colour.svg")
            assertContains(html, "https://local.invalid/capability/OPS/style.css")
            assertContains(html, "script-src 'none'")
            val css = content.resource("OPS/style.css")!!.second.decodeToString()
            assertContains(css, "https://local.invalid/capability/OPS/more.css")
            assertContains(content.resource("OPS/colour.svg")!!.second.decodeToString(), "fill=\"red\"")
            val hostile = content.html("OPS/chapter.xhtml", """<html><head><meta http-equiv="refresh" content="0;url=https://evil.test"/><base href="https://evil.test"/></head><body onload="steal()"><script src="https://evil.test/x.js">steal()</script><iframe src="file:///etc/passwd"></iframe><img src="https://evil.test/pixel" srcset="https://evil.test/2x 2x"/><a href="javascript:steal()">No</a><svg><foreignObject><script>steal()</script></foreignObject></svg><style>@import "https://evil.test/x.css";p{background:url(//evil.test/pixel)}</style><p style="background:url(file:///etc/passwd);color:red" onclick="steal()">Read</p></body></html>""")
            assertFalse(hostile.contains("evil.test"), hostile)
            assertFalse(hostile.contains("steal()"), hostile)
            assertFalse(hostile.contains("file:///"), hostile)
            assertFalse(hostile.contains("srcset"), hostile)
            assertFalse(hostile.contains("<iframe"), hostile)
            val svg = content.html("OPS/colour.svg", """<?xml-stylesheet href="https://evil.test/x.css"?><svg xmlns="http://www.w3.org/2000/svg" onload="steal()"><script>steal()</script><rect fill="red" width="100" height="100"/></svg>""", true)
            assertFalse(svg.contains("evil.test"), svg)
            assertFalse(svg.contains("steal"), svg)
            assertContains(svg, "fill=\"red\"")
            assertNull(content.href("https://external.invalid/OPS/chapter.xhtml"))
            assertNull(content.href(content.origin + "../secret.txt"))
            assertNull(content.resource("../secret.txt"))
        } finally { root.deleteRecursively() }
    }

    @Test fun loopbackServerServesOnlyManifestResourcesAndCloses() {
        val root = Files.createTempDirectory("reading-loopback-").toFile()
        try {
            ReadingEpubServer(publication(root)).use { server ->
                val good = URL(server.content.url("OPS/chapter.xhtml")).openConnection() as HttpURLConnection
                try { assertEquals(200, good.responseCode); assertContains(good.inputStream.bufferedReader().readText(), "Illustrated public domain fixture") }
                finally { good.disconnect() }
                for (suffix in listOf("../secret", "OPS/../../secret", "OPS/%2e%2e/%2e%2e/secret", "not-in-manifest.js")) {
                    val bad = URL(server.content.origin + suffix).openConnection() as HttpURLConnection
                    try { assertEquals(404, bad.responseCode, suffix) } finally { bad.disconnect() }
                }
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun desktopPdfRasterPreservesNativeColourAndFindsTextAcrossPages() {
        val file = File.createTempFile("reading-colour-", ".pdf")
        try {
            PDDocument().use { pdf ->
                repeat(2) { index ->
                    val page = PDPage(PDRectangle(300f, 200f)); pdf.addPage(page)
                    PDPageContentStream(pdf, page).use { canvas ->
                        canvas.setNonStrokingColor(Color.WHITE); canvas.addRect(0f, 0f, 300f, 200f); canvas.fill()
                        canvas.setNonStrokingColor(Color.RED); canvas.addRect(0f, 0f, 150f, 150f); canvas.fill()
                        canvas.setNonStrokingColor(Color.BLUE); canvas.addRect(150f, 0f, 150f, 150f); canvas.fill()
                        canvas.beginText(); canvas.setNonStrokingColor(Color.BLACK)
                        canvas.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                        canvas.newLineAtOffset(20f, 175f); canvas.showText(if (index == 0) "Public domain fixture" else "Second chapter searchable")
                        canvas.endText()
                    }
                }
                pdf.save(file)
            }
            DesktopReadingPdf(file).use { reader ->
                assertEquals(2, reader.pageCount)
                val bitmap = reader.render(0, 600)
                assertEquals(600, bitmap.width)
                assertTrue(bitmap.width.toLong() * bitmap.height <= 8_000_000)
                val pixels = bitmap.toPixelMap()
                val red = pixels[100, 250]; val blue = pixels[500, 250]
                assertTrue(red.red > .9f && red.blue < .1f, "Red illustration was recoloured: $red")
                assertTrue(blue.blue > .9f && blue.red < .1f, "Blue illustration was recoloured: $blue")
                assertEquals(1, reader.find("searchable", 0))
                assertEquals(0, reader.find("public domain", 1))
                assertNull(reader.find("not present", 0))
            }
        } finally { file.delete() }
    }
}
