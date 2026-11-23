package com.cosmotune.listening

import com.cosmotune.music.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals

class NaturalOrderTest {

    @Test fun numbersCompareByTheirValue() {
        val names = listOf("Track 10", "track 2", "Track 1", "Track 02", "Ärger", "Apple", "Disc 1 - 10", "Disc 1 - 9")
        assertEquals(listOf("Apple", "Ärger", "Disc 1 - 9", "Disc 1 - 10", "Track 1", "track 2", "Track 02", "Track 10"), names.sortedWith(NaturalOrder))
    }

    @Test fun veryLongNumbersDoNotOverflow() {
        assertEquals(listOf("a 99999999999999999999", "a 100000000000000000000"), listOf("a 100000000000000000000", "a 99999999999999999999").sortedWith(NaturalOrder))
    }

    @Test fun songsGroupByTheirFolder() {
        fun song(path: String) = Song(videoId = path, title = path.substringAfterLast('/'), artist = "", thumbnailUrl = null, localPath = path)
        val folders = LocalFolders.of(listOf(song("/sdcard/Music/Album 10/02 b.mp3"), song("/sdcard/Music/Album 2/10 z.mp3"), song("/sdcard/Music/Album 2/9 y.mp3")))
        assertEquals(listOf("Music/Album 2", "Music/Album 10"), folders.map { it.label })
        assertEquals(listOf("9 y.mp3", "10 z.mp3"), folders[0].songs.map(LocalFolders::fileName))
    }
}
