package com.cosmotune.music.data.model

import kotlin.test.*

class MusicSongListTest {
    private fun song(id: String, title: String, artist: String = "Singer", album: String = "Album", duration: String? = "3:00") =
        Song(id, title, artist, null, albumName = album, durationText = duration)

    @Test fun wordsCanSpanCreditsTitleAndAlbumAndLatinAccentsDoNotHideResults() {
        val songs = listOf(song("a", "Déjà Vu", "Beyoncé", "B'Day"), song("b", "Other"))
        assertEquals(listOf(0), MusicSongList.rows(songs, "beyonce deja day").map { it.index })
        assertTrue(MusicSongList.rows(songs, "beyonce absent").isEmpty())
    }

    @Test fun tamilVowelSignsRemainDistinctAndSupplementaryLettersAreSearchable() {
        val songs = listOf(song("a", "கல்"), song("b", "கால்"), song("c", "𠀀"))
        assertEquals(listOf("a"), MusicSongList.rows(songs, "கல்").map { it.value.videoId })
        assertEquals(listOf("b"), MusicSongList.rows(songs, "கால்").map { it.value.videoId })
        assertEquals(listOf("c"), MusicSongList.rows(songs, "𠀀").map { it.value.videoId })
    }

    @Test fun filteredSortedRowsRetainOriginalOccurrenceAndVisibleQueueOrder() {
        val songs = listOf(song("same", "Zulu"), song("b", "Alpha"), song("same", "Zulu"))
        val rows = MusicSongList.rows(songs, order = MusicListOrder.TITLE)
        assertEquals(listOf(1, 0, 2), rows.map { it.index })
        assertEquals(listOf("b", "same", "same"), rows.map { it.value.videoId })
        assertEquals(3, rows.map(MusicSongList::occurrence).distinct().size)
        assertEquals(listOf("same:0", "same:2"), MusicSongList.rows(songs, "zulu").map(MusicSongList::occurrence))
        assertEquals(listOf("Zulu", "Alpha", "Zulu"), songs.map { it.title }, "sorting must not edit provider order")
    }

    @Test fun downloadedFilterIncludesDeviceFilesButNotBlankPathsOrInProgressOffers() {
        val songs = listOf(song("saved", "Saved"), song("local", "Device").copy(localPath = "/music/file.flac"),
            song("uri", "Device URI").copy(localUri = "content://music/1"), song("blank", "Blank").copy(localPath = ""), song("remote", "Remote"))
        assertEquals(listOf("saved", "local", "uri"), MusicSongList.rows(songs, downloadedOnly = true, downloadedIds = setOf("saved")).map { it.value.videoId })
    }

    @Test fun durationsUseNumericOrderWithUnknownValuesLastAndDatesKeepStableTies() {
        val songs = listOf(song("long", "Long", duration = "11:00"), song("unknown", "Unknown", duration = null), song("short", "Short", duration = "2:00"))
        assertEquals(listOf("short", "long", "unknown"), MusicSongList.rows(songs, order = MusicListOrder.DURATION).map { it.value.videoId })
        assertEquals(listOf(2, 1, 0), MusicSongList.rows(songs, order = MusicListOrder.ADDED_DESC).map { it.index })
    }
}
