package com.cosmotune.listening

import com.cosmotune.audio.LibraryAudioAnalysis
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class LosslessUpgradeTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun lossyEntriesMoveToAVerifiedLosslessCopyOfTheSameRecordingOnly() {
        val mp3 = temporary.newFile("song.mp3"); val flac = temporary.newFile("song.flac"); val fake = temporary.newFile("fake.flac")
        val hires = temporary.newFile("song-hires.flac"); val otherRecording = temporary.newFile("live.flac")
        val analysis = listOf(
            LibraryAudioAnalysis("rec", mp3.path, codec = "mp3", sampleRate = 44_100),
            LibraryAudioAnalysis("rec", flac.path, codec = "flac", sampleRate = 44_100, bitsPerSample = 16, cutoffHz = 22_000),
            LibraryAudioAnalysis("rec", hires.path, codec = "flac", sampleRate = 96_000, bitsPerSample = 24, cutoffHz = 40_000),
            LibraryAudioAnalysis("rec", fake.path, codec = "flac", sampleRate = 192_000, bitsPerSample = 24, cutoffHz = 16_000), // upsampled lossy
            LibraryAudioAnalysis("live", otherRecording.path, codec = "flac", sampleRate = 192_000, bitsPerSample = 24, cutoffHz = 90_000),
        ).associateBy { it.location }
        val track = ListeningTrack("rec", "Song", "Artist", path = mp3.path, entryId = "q", format = "mp3")
        val state = ListeningState(sessions = listOf(ListeningSession(id = "s", name = "S", tracks = listOf(track, ListeningTrack("live", "Live", "Artist", path = otherRecording.path)), index = 0, positionMs = 0)))
        val available: (ListeningTrack) -> Boolean = { t -> t.path?.let { java.io.File(it) }?.isFile == true }
        val upgrades = previewLosslessUpgrades(state, analysis, available)
        assertEquals(listOf(hires.path), upgrades.map { it.after.path })
        val applied = applySessionFileRepairs(state, upgrades, available)
        assertEquals(hires.path, applied.sessions.single().tracks[0].path); assertEquals("q", applied.sessions.single().tracks[0].entryId)
        assertEquals(otherRecording.path, applied.sessions.single().tracks[1].path)
        assertTrue(mp3.isFile) // nothing on disk changes
        hires.delete()
        assertEquals(listOf(flac.path), previewLosslessUpgrades(state, analysis, available).map { it.after.path })
    }

    @Test fun onlyMeasuredLossyCatalogueEntriesWithoutAVerifiedCopyAreRequestedFromSources() {
        val mp3 = temporary.newFile("a.mp3"); val other = temporary.newFile("b.m4a"); val device = temporary.newFile("c.mp3")
        val done = temporary.newFile("d.mp3"); val doneFlac = temporary.newFile("d.flac"); val fake = temporary.newFile("e.mp3"); val fakeFlac = temporary.newFile("e.flac")
        val analysis = listOf(
            LibraryAudioAnalysis("src:addon::a", mp3.path, codec = "mp3", sampleRate = 44_100),
            LibraryAudioAnalysis("yt-b", other.path, codec = "aac", sampleRate = 44_100),
            LibraryAudioAnalysis("local:c", device.path, codec = "mp3", sampleRate = 44_100),
            LibraryAudioAnalysis("yt-d", done.path, codec = "mp3", sampleRate = 44_100),
            LibraryAudioAnalysis("yt-d", doneFlac.path, codec = "flac", sampleRate = 44_100, bitsPerSample = 16, cutoffHz = 22_000),
            LibraryAudioAnalysis("yt-e", fake.path, codec = "mp3", sampleRate = 44_100),
            // A "FLAC" copy with a lossy spectrum does not count as verified, so the lossless copy is still wanted.
            LibraryAudioAnalysis("yt-e", fakeFlac.path, codec = "flac", sampleRate = 44_100, bitsPerSample = 16, cutoffHz = 16_000),
        ).associateBy { it.location }
        val tracks = listOf(
            ListeningTrack("src:addon::a", "A", "Artist", path = mp3.path), ListeningTrack("yt-b", "B", "Artist", path = other.path),
            ListeningTrack("local:c", "C", "Artist", path = device.path), ListeningTrack("yt-d", "D", "Artist", path = done.path),
            ListeningTrack("yt-e", "E", "Artist", path = fake.path), ListeningTrack("yt-f", "F", "Artist", path = "/not/analysed.mp3"),
            ListeningTrack("src:addon::a", "A again", "Artist", path = mp3.path),
        )
        val state = ListeningState(sessions = listOf(ListeningSession(id = "s", name = "S", tracks = tracks, index = 0, positionMs = 0)))
        assertEquals(listOf("src:addon::a", "yt-b", "yt-e"), losslessCopyRequests(state, analysis).map { it.id })
    }
}
