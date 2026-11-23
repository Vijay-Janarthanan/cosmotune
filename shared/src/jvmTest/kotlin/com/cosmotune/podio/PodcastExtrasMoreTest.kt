package com.cosmotune.podio

import kotlin.test.*

class PodcastExtrasMoreTest {
    // ---- Transcripts -------------------------------------------------------------------------

    @Test fun srtAndWebVttBothReadAndMultiLineCuesBecomeOneLine() {
        val srt = "1\n00:00:01,000 --> 00:00:04,000\nHello there,\nand welcome.\n\n2\n00:00:05,000 --> 00:00:06,500\nSecond."
        val vtt = "WEBVTT\n\n00:01.000 --> 00:04.000\nHello there,\nand welcome.\n\n00:05.000 --> 00:06.500\nSecond."
        val a = PodcastExtras.transcript(srt, "application/x-subrip")
        assertEquals(listOf("Hello there, and welcome.", "Second."), a.map { it.text })
        assertEquals(listOf(1000L, 5000L), a.map { it.startMs })
        assertTrue(a.all { it.timed })
        val b = PodcastExtras.transcript(vtt, "text/vtt")
        assertEquals(a.map { it.text }, b.map { it.text })
        assertEquals(a.map { it.startMs }, b.map { it.startMs })
    }

    @Test fun podcastingTwoPointZeroJsonKeepsSpeakersAndTimes() {
        val json = """{"version":"1.0.0","segments":[
            {"speaker":"Ana","startTime":0,"endTime":2.5,"body":"Hi."},
            {"speaker":"Ben","startTime":2.5,"endTime":5,"body":"Hello, Ana."},
            {"startTime":-3,"body":"bad"},{"startTime":6,"body":"  "}]}"""
        val cues = PodcastExtras.transcript(json, "application/json")
        assertEquals(2, cues.size)
        assertEquals("Ana", cues[0].speaker)
        assertEquals(2500L, cues[1].startMs)
        assertEquals(5000L, cues[1].endMs)
        assertEquals("Hello, Ana.", cues[1].text)
        assertTrue(cues.all { it.timed })
    }

    @Test fun jsonIsRecognisedEvenWhenTheFeedCalledItSomethingElse() {
        val cues = PodcastExtras.transcript("""{"segments":[{"startTime":1,"endTime":2,"body":"x"}]}""", "text/plain")
        assertEquals(1000L, cues.single().startMs)
    }

    @Test fun htmlTranscriptWithoutTimesBecomesUntimedParagraphs() {
        val html = "<html><body><h1>Episode 4</h1><p>First   paragraph<br>continues.</p><p>Second &amp; last.</p></body></html>"
        val cues = PodcastExtras.transcript(html, "text/html")
        assertEquals(listOf("Episode 4", "First paragraph continues.", "Second & last."), cues.map { it.text })
        assertTrue(cues.none { it.timed })
    }

    @Test fun plainTextWithTimestampedLinesIsTimedAndWithoutIsParagraphs() {
        val timed = "[00:00:05] Welcome to the show.\n[00:01:10] Our first guest.\n1:02:03 The end."
        val cues = PodcastExtras.transcript(timed, "text/plain")
        assertEquals(listOf(5000L, 70000L, 3723000L), cues.map { it.startMs })
        assertEquals("Welcome to the show.", cues[0].text)
        assertEquals(70000L, cues[0].endMs)
        val untimed = PodcastExtras.transcript("One thought.\n\nAnother thought,\nsame paragraph.", "text/plain")
        assertEquals(listOf("One thought.", "Another thought, same paragraph."), untimed.map { it.text })
        assertTrue(untimed.none { it.timed })
    }

    @Test fun garbageGivesNoCuesAndDoesNotThrow() {
        assertTrue(PodcastExtras.transcript("", "text/plain").isEmpty())
        assertTrue(PodcastExtras.transcript("{not json", "application/json").isEmpty())
        assertTrue(PodcastExtras.transcript("[1,2,3]", "application/json").isEmpty())
    }

    @Test fun feedKeepsEveryTranscriptKindAndPutsTimedOnesFirst() {
        val xml = """<rss xmlns:podcast="https://podcastindex.org/namespace/1.0"><channel><title>S</title><item><guid>1</guid><title>E</title>
            <enclosure url="https://example.com/a.mp3" type="audio/mpeg"/>
            <podcast:transcript url="/t.html" type="text/html"/>
            <podcast:transcript url="/t.txt" type="text/plain"/>
            <podcast:transcript url="/t.json" type="application/json"/>
            <podcast:transcript url="/t.vtt" type="text/vtt"/>
            <podcast:transcript url="/t.pdf" type="application/pdf"/>
            </item></channel></rss>"""
        val urls = PodioCatalogue.parseFeed(xml, "https://example.com/feed").episodes.single().transcripts.map { it.url.substringAfterLast('/') }
        assertEquals(listOf("t.json", "t.vtt", "t.html", "t.txt"), urls)
    }

    // ---- Chapters ----------------------------------------------------------------------------

    @Test fun podloveSimpleChaptersInTheFeedBecomeChapters() {
        val xml = """<rss xmlns:psc="http://podlove.org/simple-chapters"><channel><title>S</title><item><guid>1</guid><title>E</title>
            <enclosure url="https://example.com/a.mp3" type="audio/mpeg"/>
            <psc:chapters version="1.2">
              <psc:chapter start="00:00:00.000" title="Intro"/>
              <psc:chapter start="00:12:30" title="The main thing" image="/c.jpg"/>
              <psc:chapter start="bad" title="Nope"/>
              <psc:chapter start="01:02:03.500" title=""/>
            </psc:chapters></item></channel></rss>"""
        val chapters = PodioCatalogue.parseFeed(xml, "https://example.com/feed").episodes.single().inlineChapters
        assertEquals(listOf("Intro", "The main thing"), chapters.map { it.title })
        assertEquals(listOf(0L, 750_000L), chapters.map { it.startMs })
        assertEquals("https://example.com/c.jpg", chapters[1].image)
    }

    @Test fun chapterTimesInShowNotesAreReadFromLinesThatStartWithATime() {
        val notes = """Show notes
            |00:00 Intro
            |02:15 - Why it matters
            |(12:30) The big question
            |1:02:03 – Wrap up
            |Thanks for listening at 5:00 pm""".trimMargin()
        val chapters = PodcastExtras.notesChapters(notes)
        assertEquals(listOf("Intro", "Why it matters", "The big question", "Wrap up"), chapters.map { it.title })
        assertEquals(listOf(0L, 135_000L, 750_000L, 3_723_000L), chapters.map { it.startMs })
    }

    @Test fun aSingleTimestampOrOutOfOrderListIsNotAChapterList() {
        assertTrue(PodcastExtras.notesChapters("00:00 Only one").isEmpty())
        assertTrue(PodcastExtras.notesChapters("10:00 Late\n05:00 Early\n01:00 Earlier").isEmpty())
        assertTrue(PodcastExtras.notesChapters("It was 10:30 when 11:45 came").isEmpty())
    }

    @Test fun feedShowNotesWithTimesBecomeChaptersEvenThoughTheDescriptionIsFlattened() {
        val xml = """<rss><channel><title>S</title><item><guid>1</guid><title>E</title>
            <enclosure url="https://example.com/a.mp3" type="audio/mpeg"/>
            <description><![CDATA[<p>Topics:</p><ul><li>00:00 Start</li><li>05:30 Middle</li></ul>]]></description>
            </item></channel></rss>"""
        val episode = PodioCatalogue.parseFeed(xml, "https://example.com/feed").episodes.single()
        assertEquals(listOf("Start", "Middle"), episode.inlineChapters.map { it.title })
        assertEquals(330_000L, episode.inlineChapters[1].startMs)
    }

    @Test fun podloveChaptersWinOverShowNotes() {
        val xml = """<rss xmlns:psc="http://podlove.org/simple-chapters"><channel><title>S</title><item><guid>1</guid><title>E</title>
            <enclosure url="https://example.com/a.mp3" type="audio/mpeg"/>
            <description>00:00 Notes one
            01:00 Notes two</description>
            <psc:chapters><psc:chapter start="00:00:00" title="Podlove one"/></psc:chapters></item></channel></rss>"""
        assertEquals(listOf("Podlove one"), PodioCatalogue.parseFeed(xml, "https://example.com/feed").episodes.single().inlineChapters.map { it.title })
    }

    // ---- ID3 CHAP frames ---------------------------------------------------------------------

    private fun frame(id: String, body: ByteArray, v4: Boolean): ByteArray {
        val size = body.size
        val sizeBytes = if (v4) byteArrayOf((size shr 21 and 0x7f).toByte(), (size shr 14 and 0x7f).toByte(), (size shr 7 and 0x7f).toByte(), (size and 0x7f).toByte())
        else byteArrayOf((size shr 24).toByte(), (size shr 16).toByte(), (size shr 8).toByte(), size.toByte())
        return id.toByteArray(Charsets.ISO_8859_1) + sizeBytes + byteArrayOf(0, 0) + body
    }
    private fun int(value: Long) = byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())
    private fun chap(element: String, startMs: Long, title: String, v4: Boolean): ByteArray {
        val tit2 = frame("TIT2", byteArrayOf(3) + title.toByteArray(Charsets.UTF_8), v4)
        return frame("CHAP", element.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) + int(startMs) + int(startMs + 1000) + int(-1) + int(-1) + tit2, v4)
    }
    private fun tag(version: Int, vararg frames: ByteArray): ByteArray {
        val body = frames.fold(ByteArray(0)) { a, b -> a + b }
        val size = body.size
        val header = "ID3".toByteArray() + byteArrayOf(version.toByte(), 0, 0) +
            byteArrayOf((size shr 21 and 0x7f).toByte(), (size shr 14 and 0x7f).toByte(), (size shr 7 and 0x7f).toByte(), (size and 0x7f).toByte())
        return header + body + ByteArray(32)
    }

    @Test fun id3ChapFramesInV23AndV24AreReadInTimeOrder() {
        for (version in listOf(3, 4)) {
            val v4 = version == 4
            val bytes = tag(version, chap("ch1", 60_000, "Second", v4), chap("ch0", 0, "Intro é", v4), frame("TIT2", byteArrayOf(3) + "Episode".toByteArray(), v4))
            val chapters = Id3Chapters.read(bytes)
            assertEquals(listOf("Intro é", "Second"), chapters.map { it.title }, "v2.$version")
            assertEquals(listOf(0L, 60_000L), chapters.map { it.startMs })
        }
    }

    @Test fun id3ReaderIgnoresFilesWithoutATagOrWithATruncatedOne() {
        assertTrue(Id3Chapters.read(ByteArray(0)).isEmpty())
        assertTrue(Id3Chapters.read("not a tag at all".toByteArray()).isEmpty())
        val good = tag(4, chap("c", 0, "A", true))
        assertTrue(Id3Chapters.read(good.copyOf(good.size / 2)).size <= 1)
    }
}
