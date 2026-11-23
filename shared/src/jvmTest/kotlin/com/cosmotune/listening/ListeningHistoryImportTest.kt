package com.cosmotune.listening

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ListeningHistoryImportTest {
    @Test fun spotifysExtendedHistoryCountsPlaysOfThirtySecondsOrMore() {
        val json = """[
          {"ts":"2023-05-01T10:00:00Z","ms_played":200000,"master_metadata_track_name":"Song A","master_metadata_album_artist_name":"Band","master_metadata_album_album_name":"Record"},
          {"ts":"2023-05-02T10:00:00Z","ms_played":12000,"master_metadata_track_name":"Song A","master_metadata_album_artist_name":"Band"},
          {"ts":"2023-05-03T10:00:00Z","ms_played":90000,"master_metadata_track_name":null,"episode_name":"A podcast"}
        ]"""
        val history = ListeningHistoryImport.read(json.toByteArray())
        assertEquals("Spotify", history.from)
        val track = history.tracks.single()
        assertEquals("Song A", track.title); assertEquals("Band", track.artist); assertEquals("Record", track.album)
        assertEquals(1, track.plays.count { it.counts })
        assertEquals(212_000, track.plays.sumOf { it.playedMs })
        assertEquals(1682935200000, track.plays.first().atMs)
    }

    @Test fun spotifysAccountDataHistoryInAZipIsRead() {
        val json = """[{"endTime":"2022-01-05 21:15","artistName":"Singer","trackName":"Tune","msPlayed":180000},
                      {"endTime":"2022-01-06 08:00","artistName":"Singer","trackName":"tune","msPlayed":40000}]"""
        val history = ListeningHistoryImport.read(zip("Spotify Account Data/StreamingHistory_music_0.json" to json, "Read me.pdf" to "x"))
        val track = history.tracks.single()
        assertEquals(2, track.plays.size)
        assertEquals(2, history.playCount)
    }

    @Test fun listenBrainzExportsAsLinesOrAnArray() {
        val line = """{"listened_at":1600000000,"track_metadata":{"artist_name":"Artist","track_name":"Track","release_name":"LP","additional_info":{"duration_ms":240000}}}"""
        val lines = ListeningHistoryImport.read(zip("listens/2020/9.jsonl" to "$line\n$line\n"))
        assertEquals("ListenBrainz", lines.from)
        assertEquals(2, lines.tracks.single().plays.size)
        assertEquals(240_000, lines.tracks.single().plays.first().playedMs)
        assertEquals(1600000000000, lines.tracks.single().plays.first().atMs)
        val array = ListeningHistoryImport.read("[$line]".toByteArray())
        assertEquals(1, array.playCount)
    }

    @Test fun theMostPlayedComeFirstAndOtherFilesAreRefused() {
        val json = """[{"endTime":"2022-01-05 21:15","artistName":"A","trackName":"Once","msPlayed":180000},
                      {"endTime":"2022-01-05 21:20","artistName":"B","trackName":"Twice","msPlayed":180000},
                      {"endTime":"2022-01-05 21:25","artistName":"B","trackName":"Twice","msPlayed":180000}]"""
        assertEquals(listOf("Twice", "Once"), ListeningHistoryImport.read(json.toByteArray()).tracks.map { it.title })
        assertFailsWith<IllegalArgumentException> { ListeningHistoryImport.read("""{"hello":1}""".toByteArray()) }
        assertTrue(ListeningHistoryImport.read("[]".toByteArray()).tracks.isEmpty())
    }

    private fun zip(vararg files: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z -> files.forEach { (name, text) -> z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() } }
    }.toByteArray()
}
