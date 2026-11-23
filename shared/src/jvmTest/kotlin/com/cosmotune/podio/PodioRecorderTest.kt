package com.cosmotune.podio

import java.nio.file.Files
import kotlin.test.*

class PodioRecorderTest {
    private fun meta(title: String): ByteArray {
        val text = "StreamTitle='$title';".toByteArray()
        val blocks = (text.size + 15) / 16
        return byteArrayOf(blocks.toByte()) + text + ByteArray(blocks * 16 - text.size)
    }

    @Test fun theSongTitlesAreTakenOutOfTheAudioWhereverTheReadsFall() {
        val audio1 = ByteArray(8) { 1 }; val audio2 = ByteArray(8) { 2 }; val audio3 = ByteArray(8) { 3 }
        val stream = audio1 + meta("A - One") + audio2 + byteArrayOf(0) + audio3 + meta("B - Two")
        for (chunk in listOf(1, 3, 7, stream.size)) {
            val splitter = IcyStreamSplitter(8)
            val out = java.io.ByteArrayOutputStream()
            val titles = ArrayList<String>()
            stream.toList().chunked(chunk).forEach { part ->
                val bytes = part.toByteArray()
                splitter.feed(bytes, bytes.size, { b, o, l -> out.write(b, o, l) }, { titles += it })
            }
            assertContentEquals(audio1 + audio2 + audio3, out.toByteArray(), "reads of $chunk")
            assertEquals(listOf("A - One", "B - Two"), titles)
        }
    }

    @Test fun aStreamWithoutTitlesIsAllAudio() {
        val out = java.io.ByteArrayOutputStream()
        IcyStreamSplitter(null).feed(ByteArray(100) { 9 }, 60, { b, o, l -> out.write(b, o, l) }, { fail() })
        assertEquals(60, out.size())
    }

    @Test fun filesAreNamedByStationAndSongAndNeverOverwritten() {
        assertEquals("Radio X – AC DC Back", PodioRecordings.fileName("Radio X", "AC/DC Back", 0))
        assertTrue(PodioRecordings.fileName("Radio X", null, 0).startsWith("Radio X 19"))
        val dir = Files.createTempDirectory("podio-rec").toFile()
        val first = java.io.File(dir, "Radio X – Song.mp3").apply { writeText("x") }
        assertEquals("Radio X – Song (2).mp3", PodioRecordings.unique(first).name)
        val listed = PodioRecordings.list(dir).single()
        assertEquals("Radio X", listed.station)
        assertEquals("Song", listed.title)
        assertTrue(listed.item().id.startsWith("podio:episode:recording-"))
    }

    @Test fun onlyPlainAudioIsSaved() {
        assertEquals("mp3", PodioRecorder.extensionOf("audio/mpeg", "https://s.example/live"))
        assertEquals("aac", PodioRecorder.extensionOf("audio/aacp; charset=x", "https://s.example/live"))
        assertNull(PodioRecorder.extensionOf("application/vnd.apple.mpegurl", "https://s.example/live.m3u8"))
        assertEquals("mp3", PodioRecorder.extensionOf("", "https://s.example/live.mp3"))
    }
}
