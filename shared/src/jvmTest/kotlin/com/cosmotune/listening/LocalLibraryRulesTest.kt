package com.cosmotune.listening

import com.cosmotune.music.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalLibraryRulesTest {
    private fun song(path: String, length: String?) = Song(videoId = path, title = path, artist = "A", thumbnailUrl = null, durationText = length, localPath = path)

    @Test
    fun `short songs are hidden, songs of unknown length kept`() {
        val rules = LocalLibraryRules(minSeconds = 60)
        assertEquals(listOf("/m/long.mp3", "/m/unknown.mp3"), rules.apply(listOf(
            song("/m/short.mp3", "0:42"), song("/m/long.mp3", "3:21"), song("/m/unknown.mp3", null),
        )).map { it.videoId })
    }

    @Test
    fun `a hidden folder hides its subfolders too, and nothing beside it`() {
        val rules = LocalLibraryRules(hiddenFolders = setOf("/sdcard/Recordings"))
        assertEquals(listOf("/sdcard/RecordingsOld/a.mp3", "/sdcard/Music/c.mp3"), rules.apply(listOf(
            song("/sdcard/Recordings/x.m4a", "3:00"), song("/sdcard/Recordings/2026/y.m4a", "3:00"),
            song("/sdcard/RecordingsOld/a.mp3", "3:00"), song("/sdcard/Music/c.mp3", "3:00"),
        )).map { it.videoId })
    }

    @Test
    fun `windows paths compare like any other`() {
        assertEquals("C:/Users/me/Music", LocalLibraryRules.folderOf("C:\\Users\\me\\Music\\a.flac"))
        assertEquals("me/Music", LocalLibraryRules.label("C:/Users/me/Music"))
    }

    @Test
    fun `lengths read in minutes and hours`() {
        assertEquals(201, LocalLibraryRules.lengthSeconds("3:21"))
        assertEquals(3723, LocalLibraryRules.lengthSeconds("1:02:03"))
        assertEquals(null, LocalLibraryRules.lengthSeconds("live"))
    }
}
