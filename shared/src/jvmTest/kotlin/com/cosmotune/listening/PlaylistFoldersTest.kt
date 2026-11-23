package com.cosmotune.listening

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaylistFoldersTest {
    private val items = listOf("PLa", "PLb", "PLc", "PLd")

    @Test fun foldersSurviveTheStore() {
        var folders = PlaylistFolders.create(emptyList(), "  Workout\t")
        folders = PlaylistFolders.create(folders, "Chill")
        folders = PlaylistFolders.setPlaylists(folders, "Workout", listOf("PLa", "PLb"))
        val back = PlaylistFolders.decode(PlaylistFolders.encode(folders))
        assertEquals(listOf(PlaylistFolder("Workout", listOf("PLa", "PLb")), PlaylistFolder("Chill")), back)
    }

    @Test fun namesAreUniqueAndBlankNamesChangeNothing() {
        val folders = PlaylistFolders.create(emptyList(), "Chill")
        assertEquals(folders, PlaylistFolders.create(folders, "chill"))
        assertEquals(folders, PlaylistFolders.create(folders, "   "))
        val two = PlaylistFolders.create(folders, "Road")
        assertEquals(two, PlaylistFolders.rename(two, "Road", "CHILL"), "another folder's name is refused")
        assertEquals(listOf("Chill", "Drive"), PlaylistFolders.rename(two, "Road", "Drive").map { it.name })
        assertEquals(listOf("Chill", "road"), PlaylistFolders.rename(two, "Road", "road").map { it.name }, "its own name in another case is fine")
    }

    @Test fun aPlaylistSitsInOneFolderAndLeavesTheTopOfThePage() {
        var folders = PlaylistFolders.create(PlaylistFolders.create(emptyList(), "A"), "B")
        folders = PlaylistFolders.setPlaylists(folders, "A", listOf("PLa", "PLb"))
        folders = PlaylistFolders.setPlaylists(folders, "B", listOf("PLb", "PLc"))
        assertEquals(listOf("PLa"), folders[0].playlistIds, "moved into B, it leaves A")
        assertEquals("B", PlaylistFolders.folderOf(folders, "PLb"))
        assertNull(PlaylistFolders.folderOf(folders, "PLd"))
        assertEquals(listOf("PLd"), PlaylistFolders.loose(items, folders) { it })
        assertEquals(listOf("PLb", "PLc"), PlaylistFolders.inFolder(items, folders, "B") { it })
        assertEquals(mapOf("A" to 1, "B" to 2), PlaylistFolders.counts(items, folders) { it })
    }

    @Test fun removingAFolderPutsItsPlaylistsBackAndBrokenLinesAreSkipped() {
        val folders = PlaylistFolders.setPlaylists(PlaylistFolders.create(emptyList(), "A"), "A", listOf("PLa"))
        assertEquals(items, PlaylistFolders.loose(items, PlaylistFolders.remove(folders, "A")) { it })
        val back = PlaylistFolders.decode("\tPLx\nGood\tPLa,,PLb\nSecond\tPLa,PLc\ngood\tPLd")
        assertEquals(listOf(PlaylistFolder("Good", listOf("PLa", "PLb")), PlaylistFolder("Second", listOf("PLc"))), back)
        assertTrue(PlaylistFolders.decode("").isEmpty())
    }
}
