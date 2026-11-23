package com.cosmotune.podio

import kotlin.test.*

class PodioStationLinksTest {
    @Test fun playlistsNameTheirStreams() {
        val pls = "[playlist]\nNumberOfEntries=2\nFile2=https://b.example/live\nFile1=http://a.example:8000/stream\nTitle1=A\n"
        assertEquals(listOf("http://a.example:8000/stream", "https://b.example/live"), PodioStationLinks.streams(pls, "https://radio.example/x.pls"))
        val m3u = "#EXTM3U\n#EXTINF:-1,Station\nstream.mp3\nhttps://c.example/live\n"
        assertEquals(listOf("https://radio.example/stream.mp3", "https://c.example/live"), PodioStationLinks.streams(m3u, "https://radio.example/list.m3u"))
        assertTrue(PodioStationLinks.streams("#EXTM3U\n#EXT-X-VERSION:3\nchunk.ts\n", "https://radio.example/live.m3u8").isEmpty(), "HLS plays as it is")
        assertTrue(PodioStationLinks.isPlaylist("https://radio.example/listen.pls?x=1"))
        assertTrue(PodioStationLinks.isPlaylist("https://radio.example/live", "audio/x-mpegurl"))
        assertFalse(PodioStationLinks.isPlaylist("https://radio.example/live.mp3", "audio/mpeg"))
    }

    @Test fun linksFromOutsideWaitForPodio() {
        assertTrue(PodioLinks.accepts("https://radio.example/listen.pls?sid=1"))
        assertTrue(PodioLinks.accepts("C:\\Users\\me\\Downloads\\Station.M3U"))
        assertFalse(PodioLinks.accepts("https://radio.example/live.mp3"))
        assertTrue(PodioLinks.offerPlaylist("[playlist]\nFile1=https://a.example/live\nTitle1=Radio A\n"))
        assertEquals(PodioLinks.Pending("Radio A", "https://a.example/live"), PodioLinks.pending.value)
        val file = java.nio.file.Files.createTempFile("Station", ".m3u").toFile().apply { writeText("#EXTM3U\nhttps://b.example/live\n") }
        assertTrue(PodioLinks.offerDesktop(file.absolutePath))
        assertEquals("https://b.example/live", PodioLinks.pending.value?.address)
        assertEquals(file.nameWithoutExtension, PodioLinks.pending.value?.name)
        PodioLinks.handled()
        assertNull(PodioLinks.pending.value)
        assertFalse(PodioLinks.offerPlaylist("not a playlist"))
    }

    @Test fun aStationAddedByHandKeepsOneEntryAndCanBeEdited() {
        val station = assertNotNull(PodioStationLinks.station(" ", "https://www.myradio.example/live"))
        assertEquals("myradio.example", station.name)
        assertTrue(PodioStationLinks.isCustom(station))
        assertNull(PodioStationLinks.station("x", "ftp://nope"))
        var saved = PodioStationLinks.put(PodioSaved(), station)
        saved = PodioStationLinks.put(saved, station)
        assertEquals(1, saved.stations.size)
        saved = PodioStationLinks.put(saved, station.copy(name = "Mine"), replacing = station.id)
        assertEquals(listOf("Mine"), saved.stations.map { it.name })
        assertEquals("#EXTM3U\n#EXTINF:-1,Mine\nhttps://www.myradio.example/live\n", PodioStationLinks.m3u(saved.stations))
    }
}
