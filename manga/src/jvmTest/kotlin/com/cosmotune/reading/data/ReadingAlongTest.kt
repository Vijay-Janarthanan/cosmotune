package com.cosmotune.reading.data

import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class ReadingAlongTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun smilClockValuesInEveryForm() {
        assertEquals(3_723_500L, ReadingOverlays.clock("1:02:03.5"))
        assertEquals(123_500L, ReadingOverlays.clock("02:03.5"))
        assertEquals(3_500L, ReadingOverlays.clock("3.5s"))
        assertEquals(1_500L, ReadingOverlays.clock("1500ms"))
        assertEquals(120_000L, ReadingOverlays.clock("2min"))
        assertEquals(4_000L, ReadingOverlays.clock("4"))
        assertNull(ReadingOverlays.clock("soon"))
    }

    @Test fun anEpubWithRecordedNarrationKnowsEachPhrasesClip() = runBlocking<Unit> {
        val bytes = ByteArrayOutputStream().use { output ->
            val entries = linkedMapOf(
                "mimetype" to "application/epub+zip",
                "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="OEBPS/package.opf"/></rootfiles></container>""",
                "OEBPS/package.opf" to """<package xmlns:dc="http://purl.org/dc/elements/1.1/"><metadata><dc:title>Told aloud</dc:title>
                  <meta property="media:active-class">-epub-media-overlay-active</meta></metadata>
                  <manifest><item id="c1" href="text/one.xhtml" media-type="application/xhtml+xml" media-overlay="s1"/>
                  <item id="c2" href="text/two.xhtml" media-type="application/xhtml+xml"/>
                  <item id="s1" href="smil/one.smil" media-type="application/smil+xml"/>
                  <item id="a1" href="audio/one.mp3" media-type="audio/mpeg"/></manifest>
                  <spine><itemref idref="c1"/><itemref idref="c2"/></spine></package>""",
                "OEBPS/text/one.xhtml" to """<html><body><p id="p1">First words.</p><p id="p2">Then more.</p></body></html>""",
                "OEBPS/text/two.xhtml" to """<html><body><p>No narration here.</p></body></html>""",
                "OEBPS/smil/one.smil" to """<smil xmlns="http://www.w3.org/ns/SMIL" version="3.0"><body><seq>
                  <par id="x1"><text src="../text/one.xhtml#p1"/><audio src="../audio/one.mp3" clipBegin="0:00:00.000" clipEnd="0:00:02.250"/></par>
                  <par id="x2"><text src="../text/one.xhtml#p2"/><audio src="../audio/one.mp3" clipBegin="2.25s" clipEnd="5s"/></par>
                  </seq></body></smil>""",
                "OEBPS/audio/one.mp3" to "not really audio",
            )
            ZipOutputStream(output).use { zip -> entries.forEach { (name, text) -> zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() } }
            output.toByteArray()
        }
        val repository = ReadingRepository(temporary.newFolder())
        val book = repository.importFile("Told aloud.epub") { bytes.inputStream() }
        val document = assertIs<ReadingDocument.Epub>(repository.openDocument(book.id))
        val overlay = assertNotNull(document.overlays["OEBPS/text/one.xhtml"])
        assertNull(document.overlays["OEBPS/text/two.xhtml"])
        assertEquals("-epub-media-overlay-active", document.overlayClass)
        assertEquals(listOf("p1", "p2"), overlay.clips.map { it.fragment })
        assertEquals(listOf("OEBPS/audio/one.mp3"), overlay.audio)
        assertEquals(2_250L to 5_000L, overlay.clips[1].beginMs to overlay.clips[1].endMs)
        assertEquals(1, overlay.clipAt("OEBPS/audio/one.mp3", 3_000))
        assertNull(overlay.clipAt("OEBPS/audio/one.mp3", 6_000))
        assertEquals(1, overlay.clipFor("p2"))
        assertEquals(0, overlay.clipFor("elsewhere"))
    }

    @Test fun chaptersPairByNumberThenNameThenPlace() {
        val book = listOf("Cover", "Contents", "Chapter One", "Chapter 2: The Road", "III. The Sea", "Epilogue")
        val audio = listOf("Opening credits", "Chapter 1", "Chapter 2", "Chapter 3", "Epilogue", "End credits")
        val pairs = ReadingAlongMatch.chapters(book, audio)
        assertEquals(1, pairs[2]); assertEquals(2, pairs[3]); assertEquals(3, pairs[4]); assertEquals(4, pairs[5])
        assertTrue(pairs[0] <= pairs[1] && pairs[1] <= pairs[2], "never going backwards")
        assertEquals(2, ReadingAlongMatch.section(pairs, 1))
        assertEquals(5, ReadingAlongMatch.section(pairs, 4))
        assertEquals(listOf(0, 1, 2), ReadingAlongMatch.chapters(listOf("", "", ""), listOf("a", "b", "c")), "untitled ones go by their place")
        assertEquals(21, ReadingAlongMatch.number("Chapter Twenty-One"))
        assertEquals(1, ReadingAlongMatch.number("Chapter One The Boy"))
        assertEquals(14, ReadingAlongMatch.number("XIV. Storm"))
    }

    @Test fun aBookIsPairedWithAnAudiobookOnTheShelf() = runBlocking<Unit> {
        val repository = ReadingRepository(temporary.newFolder())
        val book = repository.importText("Pasted", "A short text to read.")
        val audio = ReadingAudiobook("lv:1", "A recording", source = ReadingAudioSource.LIBRIVOX, tracks = listOf(ReadingAudioTrack("https://archive.org/x.mp3", "One", 1000)))
        repository.store.update { it.copy(audiobooks = listOf(audio)) }
        repository.pairAudio(book.id, audio.id)
        assertEquals(mapOf(book.id to "lv:1"), repository.state.value.audioPairs)
        assertFailsWith<IllegalArgumentException> { repository.pairAudio(book.id, "missing") }
        repository.pairAudio(book.id, null)
        assertTrue(repository.state.value.audioPairs.isEmpty())
    }
}
