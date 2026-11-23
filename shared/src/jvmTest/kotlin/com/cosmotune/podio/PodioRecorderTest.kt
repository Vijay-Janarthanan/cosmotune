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

    @Test fun theRecorderSavesWhatThePlayerReadsOneFileASong() {
        val dir = Files.createTempDirectory("podio-tap").toFile()
        val station = RadioStation("s", "Radio X", "https://radio.example/live")
        val other = RadioStation("o", "Other", "https://other.example/live")
        PodioRecorder.playing(station)
        assertNull(PodioRecorder.tap(other.url, "audio/mpeg", null), "only the station that plays")
        PodioRecorder.start(station, dir, split = true, nowMs = { 0L })
        val tap = assertNotNull(PodioRecorder.tap("https://radio.example/live/", "audio/mpeg", 64 * 1024))
        val song = ByteArray(64 * 1024) { 7 }
        // The first title comes after the first block of audio, then each block is one song.
        val stream = song + meta("A - One") + song + meta("B - Two") + song + byteArrayOf(0)
        stream.toList().chunked(5000).forEach { part -> val bytes = part.toByteArray(); tap.write(bytes, 0, bytes.size) }
        assertEquals("B - Two", PodioRecorder.state.value?.title)
        PodioRecorder.playing(other)
        assertNull(PodioRecorder.state.value, "another station stops the recording")
        val names = dir.listFiles().orEmpty().map { it.name }.sorted()
        assertEquals(listOf("Radio X – A - One.mp3", "Radio X – B - Two.mp3"), names, "the part before the first title is too short to keep")
        assertTrue(dir.listFiles()!!.all { it.length() == 64 * 1024L })
    }

    @Test fun onlyPlainAudioIsSaved() {
        assertEquals("mp3", PodioRecorder.extensionOf("audio/mpeg", "https://s.example/live"))
        assertEquals("aac", PodioRecorder.extensionOf("audio/aacp; charset=x", "https://s.example/live"))
        assertNull(PodioRecorder.extensionOf("application/vnd.apple.mpegurl", "https://s.example/live.m3u8"))
        assertEquals("mp3", PodioRecorder.extensionOf("", "https://s.example/live.mp3"))
    }
}
