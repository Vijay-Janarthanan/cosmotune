package com.cosmotune.music.data

import com.cosmotune.music.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Albums YouTube Music files by their music videos play as their songs. */
class AlbumVideoRowsTest {
    private fun video(id: String, title: String) =
        Song(id, title, "Rapper", null, isVideo = true, albumId = "MPREb_album", albumName = "The Album", setVideoId = "set-$id")

    private fun song(id: String, title: String) = Song(id, title, "Rapper", null, albumId = null, albumName = null)

    @Test
    fun eachVideoRowWithAFoundSongBecomesThatSongInItsPlace() {
        val rows = listOf(video("v1", "One (Official Video)"), song("s2", "Two"), video("v3", "Three (Official Video)"))
        val result = YtMusicRepository.albumRowsAsSongs(rows, mapOf("v1" to song("s1", "One")))

        assertEquals(listOf("s1", "s2", "v3"), result.map { it.videoId })
        // Filed on the album it is played from, and no longer a playlist entry of the video's.
        assertEquals("MPREb_album", result[0].albumId)
        assertEquals("The Album", result[0].albumName)
        assertEquals(null, result[0].setVideoId)
        assertFalse(result[0].isVideo)
        // A video whose song was not found stays as it is.
        assertEquals(rows[2], result[2])
    }

    @Test
    fun aSongIsNeverTakenTwice() {
        val rows = listOf(video("v1", "Two (Official Video)"), song("s2", "Two"), video("v3", "One"), video("v4", "One (Live)"))
        val found = mapOf("v1" to song("s2", "Two"), "v3" to song("s1", "One"), "v4" to song("s1", "One"))
        val result = YtMusicRepository.albumRowsAsSongs(rows, found)

        // The album already lists s2, so v1 keeps its video; s1 goes to the first video that found it only.
        assertEquals(listOf("v1", "s2", "s1", "v4"), result.map { it.videoId })
    }
}
