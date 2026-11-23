package com.cosmotune.listening

import com.cosmotune.music.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SongTagsTest {
    private fun song(id: String) = Song(videoId = id, title = "Title $id", artist = "Artist", thumbnailUrl = null, durationText = "3:30")

    @Test fun ratingsAndTagsSurviveTheStore() {
        var songs = SongTags.set(emptyList(), song("a"), 5, listOf(" Calm ", "calm", "Late   night"), now = 1)
        songs = SongTags.set(songs, song("local:/m/b.flac").copy(localPath = "/m/b.flac"), 3, emptyList(), now = 2)
        val back = SongTags.decode(SongTags.encode(songs))
        assertEquals(songs, back)
        assertEquals(listOf("Calm", "Late night"), back.last().tags)
        assertEquals("/m/b.flac", back.first().toSong().localPath)
    }

    @Test fun aSongWithNeitherStarsNorTagsIsDroppedAndTagsKeepTheirFirstSpelling() {
        var songs = SongTags.set(emptyList(), song("a"), 0, listOf("Workout"), now = 1)
        songs = SongTags.set(songs, song("b"), 4, listOf("workout"), now = 2)
        assertEquals(listOf("Workout"), songs.first().tags)
        songs = SongTags.set(songs, song("a"), 0, emptyList(), now = 3)
        assertEquals(listOf("b"), songs.map { it.id })
        assertNull(SongTags.of(songs, "a"))
    }

    @Test fun pagesListTopRatedAndEachTag() {
        var songs = SongTags.set(emptyList(), song("a"), 3, listOf("Calm"), now = 1)
        songs = SongTags.set(songs, song("b"), 5, listOf("Calm", "Focus"), now = 2)
        songs = SongTags.set(songs, song("c"), 4, emptyList(), now = 3)
        assertEquals(listOf("b", "c"), SongTags.page(songs, SongTags.Page.TopRated).map { it.id })
        assertEquals(listOf("b", "c", "a"), SongTags.page(songs, SongTags.Page.Rated).map { it.id })
        assertEquals(listOf("b", "a"), SongTags.page(songs, SongTags.Page.Tag("calm")).map { it.id })
        assertEquals(listOf("Calm", "Focus"), SongTags.allTags(songs))
        assertTrue(SongTags.decode("not json").isEmpty())
    }
}
