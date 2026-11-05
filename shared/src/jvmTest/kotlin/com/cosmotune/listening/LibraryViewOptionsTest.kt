package com.cosmotune.listening

import com.cosmotune.audio.LibraryAudioAnalysis
import com.cosmotune.music.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryViewOptionsTest {
    private val flac = Song("local:a", "Kannalane", "A. R. Rahman", null, albumName = "Bombay", durationText = "5:35", localPath = "/m/a.flac")
    private val mp3 = Song("local:b", "Humma", "A. R. Rahman", null, localPath = "/m/b.mp3")
    private val suspect = Song("content://media/3", "Roja", "A. R. Rahman", null, localUri = "content://media/3")
    private val share = Song("smb:nas/music/c.flac", "Uyire", "A. R. Rahman", null)
    private val server = Song("src:home::tr-1", "Taal", "A. R. Rahman", null)
    private val analysis = mapOf(
        flac.videoId to LibraryAudioAnalysis(flac.videoId, "a", integratedLufs = -12.3, codec = "flac", sampleRate = 44_100, bitsPerSample = 16, genre = "Soundtrack", durationSeconds = 335.0),
        mp3.videoId to LibraryAudioAnalysis(mp3.videoId, "b", integratedLufs = -9.0, codec = "mp3", sampleRate = 44_100, genre = "soundtrack", durationSeconds = 300.4),
        suspect.videoId to LibraryAudioAnalysis(suspect.videoId, "c", codec = "flac", sampleRate = 44_100, cutoffHz = 16_000, genre = "Film"),
    )
    private val all = listOf(flac, mp3, suspect, share, server)
    private val lookup = { song: Song -> analysis[song.videoId] }

    @Test fun filtersCombineSourceQualityAndGenre() {
        val options = LibraryViewOptions()
        assertEquals(all, options.apply(all, lookup))
        assertEquals(listOf(flac, suspect), options.copy(quality = LibraryQualityFilter.LOSSLESS).apply(all, lookup))
        assertEquals(listOf(mp3), options.copy(quality = LibraryQualityFilter.LOSSY).apply(all, lookup))
        assertEquals(listOf(suspect), options.copy(quality = LibraryQualityFilter.SUSPECT).apply(all, lookup))
        assertEquals(listOf(share, server), options.copy(quality = LibraryQualityFilter.UNMEASURED).apply(all, lookup))
        assertEquals(listOf(flac, mp3), options.copy(genre = "SOUNDTRACK").apply(all, lookup))
        assertEquals(listOf(flac, mp3, suspect), options.copy(source = LibrarySource.DEVICE).apply(all, lookup))
        assertEquals(listOf(share), options.copy(source = LibrarySource.NETWORK).apply(all, lookup))
        assertEquals(listOf(server), options.copy(source = LibrarySource.SERVER).apply(all, lookup))
        assertEquals(listOf(flac), options.copy(source = LibrarySource.DEVICE, quality = LibraryQualityFilter.LOSSLESS, genre = "Soundtrack").apply(all, lookup))
        assertEquals(listOf("Soundtrack", "Film"), LibraryViewOptions.genresOf(all, lookup))
    }

    @Test fun cellsFollowColumnOrderAndSkipWhatIsUnknown() {
        val options = LibraryViewOptions(columns = LibraryColumn.entries.toSet())
        assertEquals(listOf("Bombay", "5:35", "This device", "FLAC 44.1 kHz 16-bit", "Lossless", "-12.3 LUFS", "Soundtrack"),
            options.cells(flac, analysis[flac.videoId]).map { it.second })
        assertEquals(listOf("5:00", "This device", "MP3 44.1 kHz", "Lossy", "-9.0 LUFS", "soundtrack"), options.cells(mp3, analysis[mp3.videoId]).map { it.second })
        assertEquals(listOf("Network share", "FLAC"), options.cells(share.copy(localUri = "smb://nas/music/c.flac"), null).map { it.second })
        assertEquals(emptyList(), LibraryViewOptions(columns = emptySet()).cells(flac, analysis[flac.videoId]))
    }

    @Test fun choicesRoundTripAndBadInputFallsBackToDefaults() {
        val options = LibraryViewOptions(setOf(LibraryColumn.GENRE, LibraryColumn.QUALITY), LibrarySource.NETWORK, LibraryQualityFilter.SUSPECT, "Film|score")
        val decoded = LibraryViewOptions.decode(options.encode())
        assertEquals(options.copy(genre = "Film score"), decoded)
        assertEquals(LibraryViewOptions(), LibraryViewOptions.decode(null))
        assertEquals(LibraryViewOptions(), LibraryViewOptions.decode("garbage"))
        assertEquals(LibraryViewOptions(columns = emptySet()), LibraryViewOptions.decode("NOPE||ALSO_NOPE|"))
    }
}
