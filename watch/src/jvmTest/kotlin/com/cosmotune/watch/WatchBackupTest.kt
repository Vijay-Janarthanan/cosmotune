package com.cosmotune.watch

import com.cosmotune.watch.model.MyListEntry
import com.cosmotune.watch.model.TitleSummary
import com.cosmotune.watch.model.TitleType
import com.cosmotune.watch.model.WatchKey
import com.cosmotune.watch.model.WatchPlaylist
import com.cosmotune.watch.model.WatchProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WatchBackupTest {
    private fun card(id: Int) = TitleSummary(WatchKey.tmdbFilm(id), TitleType.FILM, "Film $id")
    private fun place(id: Int, at: Long, pos: Long) = WatchProgress(WatchKey.tmdbFilm(id), pos, 7_200_000, at, card(id))

    @Test fun aBackupReadsBackAsItWasSaved() {
        val backup = WatchBackup(savedAtMs = 5, profile = "Everyone", myList = listOf(MyListEntry(card(1).key, 1, card(1))),
            progress = listOf(place(2, 10, 60_000)), playlists = listOf(WatchPlaylist("p", "Weekend", listOf(card(3)))),
            favourites = setOf("c:iptv:x"), preferences = WatchPreferences(homeLanguages = listOf("te")), addons = "cosmotune://watch/pack?d=abc")
        assertEquals(backup, WatchBackup.decode(WatchBackup.encode(backup)))
        assertEquals("1 in My list, 1 place in shows and films, 1 playlist, 1 favourite channel, 3 add-ons", backup.summary(3))
    }

    @Test fun restoringAddsAndKeepsTheNewerPlace() {
        val mine = WatchBackup(myList = listOf(MyListEntry(card(1).key, 1, card(1))), progress = listOf(place(2, 100, 50_000), place(4, 100, 1)),
            playlists = listOf(WatchPlaylist("a", "Weekend", listOf(card(1)))), favourites = setOf("c:1"))
        val incoming = WatchBackup(myList = listOf(MyListEntry(card(1).key, 9, card(1)), MyListEntry(card(5).key, 9, card(5))),
            progress = listOf(place(2, 50, 10_000), place(4, 200, 90_000), place(6, 1, 5)),
            playlists = listOf(WatchPlaylist("b", "weekend", listOf(card(7))), WatchPlaylist("a", "Kids", listOf(card(8)))), favourites = setOf("c:2"))
        val merged = WatchBackup.merge(mine, incoming)
        assertEquals(listOf(1, 5), merged.myList.map { it.key.id.toInt() })
        assertEquals(50_000, merged.progress.first { it.key == WatchKey.tmdbFilm(2) }.positionMs, "the place here is newer")
        assertEquals(90_000, merged.progress.first { it.key == WatchKey.tmdbFilm(4) }.positionMs, "the backup's place is newer")
        assertEquals(3, merged.progress.size)
        assertEquals(listOf("Weekend", "Kids"), merged.playlists.map { it.name })
        assertEquals(2, merged.playlists[0].items.size)
        assertEquals("restored-a", merged.playlists[1].id, "a clashing id is not reused")
        assertEquals(setOf("c:1", "c:2"), merged.favourites)
    }

    @Test fun otherFilesAreRefused() {
        assertTrue(assertFailsWith<IllegalArgumentException> { WatchBackup.decode("{\"hello\":1}") }.message!!.contains("not a Watch backup"))
        assertFailsWith<IllegalArgumentException> { WatchBackup.decode("not json") }
        assertTrue(assertFailsWith<IllegalArgumentException> { WatchBackup.decode("{\"format\":\"cosmotune-watch-backup\",\"version\":9}") }.message!!.contains("newer"))
    }
}
